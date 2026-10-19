import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

// Fixed loopback test harness only. Never accepts credentials or a configurable server URL.
const base = 'http://127.0.0.1:15543';
if (process.argv.slice(2).join(' ') !== '--reset-isolated-fixture') {
  throw new Error('Run after simulator E2E: node scripts/qa/automatic-journey-harness-smoke.mjs --reset-isolated-fixture');
}

async function request(path, body) {
  const response = await fetch(base + path, {
    ...(body === undefined ? {} : {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
    signal: AbortSignal.timeout(10_000),
    redirect: 'error',
  });
  return { status: response.status, body: await response.json() };
}

function assertClean(state) {
  assert.equal(state.fixtureOnly, true);
  for (const name of ['prepareCount', 'departureCount', 'activationCount', 'cancelCount']) {
    assert.equal(state[name], 0, name);
  }
  assert.equal(state.lastState, 'NONE');
}

let resetStarted = false;
let smokePassed = false;
try {
  const health = await request('/__fixture/health');
  assert.equal(health.status, 200);
  assert.equal(health.body.fixtureOnly, true);
  assert.equal(health.body.service, 'automatic-journey');
  assert.equal(health.body.scheduleId, '9001');
  resetStarted = true;
  const reset = await request('/__fixture/reset', {});
  assert.equal(reset.status, 200);
  assertClean(reset.body);

  const owner = randomUUID();
  const session = randomUUID();
  const digest = 'a'.repeat(64);
  const prefix = '/api/schedules/9001/automatic-journey';
  const prepared = await request(`${prefix}/prepare`, {
    requestId: session, clientOwnerId: owner, routeInputDigest: digest, execution: 'SIMULATOR',
  });
  assert.equal(prepared.status, 200);
  const preparation = prepared.body.data;
  assert.equal(preparation.state, 'ARMED');
  assert.equal(preparation.sequence, 0);
  const armed = (await request('/__fixture/state')).body;
  assert.equal(armed.prepareCount, 1);
  assert.equal(armed.departureCount, 0);
  assert.equal(armed.activationCount, 0);
  assert.equal(armed.cancelCount, 0);

  const fence = {
    generation: preparation.generation, routeRevision: preparation.routeRevision,
    clientOwnerId: owner, routeInputDigest: digest,
  };
  const event = {
    ...fence,
    eventKey: `${session}:${preparation.generation}:${preparation.routeRevision}:AUTO_DEPARTURE`,
    sequence: 1, type: 'AUTO_DEPARTURE', occurredAt: new Date().toISOString(), execution: 'SIMULATOR',
  };
  assert.equal((await request('/__fixture/control', { mode: 'FAIL_BEFORE_COMMIT_ONCE' })).status, 200);
  assert.equal((await request(`${prefix}/${session}/departure`, event)).status, 503);
  const uncommitted = (await request('/__fixture/state')).body;
  assert.equal(uncommitted.lastState, 'ARMED');
  assert.equal(uncommitted.departureCount, 0);
  assert.equal(uncommitted.activationCount, 0);
  assert.equal(uncommitted.cancelCount, 0);

  assert.equal((await request('/__fixture/control', { mode: 'LOSE_RESPONSE_AFTER_COMMIT_ONCE' })).status, 200);
  assert.equal((await request(`${prefix}/${session}/departure`, event)).status, 503);
  const committed = (await request('/__fixture/state')).body;
  assert.equal(committed.lastState, 'ACTIVE');
  assert.equal(committed.departureCount, 1);
  assert.equal(committed.activationCount, 1);
  assert.equal(committed.cancelCount, 1);

  const retry = await request(`${prefix}/${session}/departure`, event);
  assert.equal(retry.status, 200);
  assert.equal(retry.body.data.duplicate, true);
  assert.equal(retry.body.data.accepted, true);
  const conflicting = { ...event, occurredAt: new Date(Date.parse(event.occurredAt) + 1).toISOString() };
  assert.equal((await request(`${prefix}/${session}/departure`, conflicting)).status, 409);

  const stopped = await request(`${prefix}/${session}/stop`, fence);
  assert.equal(stopped.status, 200);
  assert.equal(stopped.body.data.state, 'STOPPED');
  const historical = await request(`${prefix}/${session}/departure`, event);
  assert.equal(historical.status, 200);
  assert.equal(historical.body.data.duplicate, true);
  const terminal = (await request('/__fixture/state')).body;
  assert.equal(terminal.lastState, 'STOPPED');
  assert.equal(terminal.departureCount, 1);
  assert.equal(terminal.activationCount, 1);
  assert.equal(terminal.cancelCount, 1);
  smokePassed = true;
} catch (error) {
  // Deliberately avoid logging bodies, client identifiers, or external environment data.
  console.error(JSON.stringify({ smoke: 'FAIL', error: error.message, code: error.cause?.code ?? null }));
  process.exitCode = 1;
} finally {
  if (resetStarted) {
    try {
      const reset = await request('/__fixture/reset', {});
      assert.equal(reset.status, 200);
      assertClean(reset.body);
      console.log(JSON.stringify({ smoke: smokePassed ? 'PASS' : 'FAIL', afterReset: reset.body }));
    } catch (error) {
      console.error(JSON.stringify({ cleanup: 'FAIL', error: error.message, code: error.cause?.code ?? null }));
      process.exitCode = 1;
    }
  }
}
