import fs from 'node:fs';
import cp from 'node:child_process';
import path from 'node:path';

// Explicit old PIDs are a scope fence: never terminate whichever process happens to use a port.
const [backendArg, metroArg, classpathFile] = process.argv.slice(2);
if (!/^\d+$/.test(backendArg || '') || !/^\d+$/.test(metroArg || '') ||
    !classpathFile?.startsWith('/private/tmp/nolate-eta-real-e2e.')) throw Error('Explicit scope required');
const be = '/Users/mac/IdeaProjects/NoLate/NoLate_BE';
const fe = '/Users/mac/IdeaProjects/NoLate/NoLate_FE';
const javaRoot = '/Users/mac/.sdkman/candidates/java/21.0.2-open';
const root = fs.mkdtempSync('/private/tmp/nolate-local-restart.');
fs.chmodSync(root, 0o700);
const emit = value => console.log(JSON.stringify(value));
const pause = () => new Promise(resolve => setTimeout(resolve, 500));
const listeners = port => {
  const result = cp.spawnSync('/usr/sbin/lsof', ['-nP', `-iTCP:${port}`, '-sTCP:LISTEN', '-t'], {encoding:'utf8'});
  if (![0,1].includes(result.status)) throw Error('Listener check failed');
  return [...new Set(result.stdout.trim().split(/\s+/).filter(Boolean).map(Number))];
};
const expect = (port, pid, commandMarker) => {
  if (JSON.stringify(listeners(port)) !== JSON.stringify([pid])) throw Error('Listener ownership changed');
  const command = cp.execFileSync('ps', ['-p', String(pid), '-o', 'command='], {encoding:'utf8'});
  if (!command.includes(commandMarker)) throw Error('Unexpected process identity');
};
const waitFor = async (check, seconds) => {
  const end = Date.now() + seconds * 1000;
  while (Date.now() < end) { if (await check()) return; await pause(); }
  throw Error('Bounded readiness timeout');
};
const start = (executable, args, cwd, env, name) => {
  const fd = fs.openSync(path.join(root, name + '.log'), 'wx', 0o600);
  const child = cp.spawn(executable, args, {cwd, env, detached:true, stdio:['ignore',fd,fd]});
  fs.closeSync(fd); child.unref(); return child.pid;
};
try {
  const oldBackend = Number(backendArg), oldMetro = Number(metroArg);
  expect(5522, oldBackend, 'com.noLate.NoLateApplication');
  expect(8085, oldMetro, 'expo start');
  const classpath = fs.readFileSync(classpathFile, 'utf8').trim();
  const compile = cp.spawnSync(path.join(javaRoot, 'bin/javac'), ['-classpath', classpath, '-d', root,
    path.join(be,'e2e/StartLocalBackend.java')], {encoding:'utf8'});
  if (compile.status !== 0) throw Error('Launcher compilation failed before stopping servers');
  emit({state:'validated',root,oldBackend,oldMetro});
  // Recheck immediately before SIGTERM; no force-kill or process-wide cleanup is allowed.
  expect(5522, oldBackend, 'com.noLate.NoLateApplication'); process.kill(oldBackend, 'SIGTERM');
  await waitFor(() => listeners(5522).length === 0, 45);
  const backendPid = start(path.join(javaRoot, 'bin/java'), ['-Xmx1536m', '-classpath', root + ':' + classpath,
    'StartLocalBackend', root], be, process.env, 'backend');
  emit({state:'backend_starting',backendPid});
  await waitFor(async () => {
    if (!fs.existsSync(path.join(root,'backend-ready.json'))) return false;
    try { const response=await fetch('http://127.0.0.1:5522/health/readiness', {signal:AbortSignal.timeout(2000)});
      return response.ok && (await response.json()).status === 'UP'; } catch { return false; }
  }, 120);
  expect(8085, oldMetro, 'expo start'); process.kill(oldMetro, 'SIGTERM');
  await waitFor(() => listeners(8085).length === 0, 30);
  const metroPid = start('npx', ['expo','start','--dev-client','--localhost','--port','8085'], fe,
    {...process.env, JAVA_HOME:javaRoot, EXPO_PUBLIC_LOCAL_API_BASE_URL:'http://127.0.0.1:5522',
      EXPO_PUBLIC_TRANSIT_PROVIDER:'odsay', REACT_NATIVE_PACKAGER_HOSTNAME:'127.0.0.1', CI:'1'}, 'metro');
  await waitFor(async () => {
    try { const response=await fetch('http://127.0.0.1:8085/status', {signal:AbortSignal.timeout(2000)});
      return response.ok && await response.text() === 'packager-status:running'; } catch { return false; }
  }, 120);
  const report={state:'restarted',root,backendPid,metroLauncherPid:metroPid,metroListenerPid:listeners(8085)[0],
    backend:JSON.parse(fs.readFileSync(path.join(root,'backend-ready.json'),'utf8')),
    metroTransitProvider:'odsay',apiBase:'http://127.0.0.1:5522'};
  fs.writeFileSync(path.join(root,'restart-report.json'), JSON.stringify(report,null,2), {flag:'wx',mode:0o600});
  emit(report);
} catch (error) { emit({state:'failed',root,failure:error.message}); process.exitCode=1; }
