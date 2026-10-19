import fs from 'node:fs';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
const docker='/opt/homebrew/bin/docker';
const name=`nolate-journey-migration-${randomUUID()}`;
const run=(args,input) => spawnSync(docker,args,{input,encoding:'utf8',timeout:30000});
const sql=(text,db='fixture',ok=true) => {
  const r=run(['exec','-i',name,'mysql','--protocol=TCP','-h127.0.0.1','-uroot','-N','-B',...(db?[db]:[])],text);
  assert.equal(r.status===0,ok,`isolated SQL expectation: ${r.stderr}`); return r.stdout.trim();
};
const migrations=['2026-10-03-journey-guidance-sessions','2026-10-04-automatic-journey-preparations',
  '2026-10-06-journey-activity-lanes','2026-10-12-journey-walking-snapshots','2026-10-12-mixed-walking-binding']
  .map(f=>fs.readFileSync(new URL(`../../docs/schedule/migrations/${f}.sql`,import.meta.url),'utf8'));
assert.equal(run(['run','-d','--rm','--name',name,'--network','none','-e','MYSQL_ALLOW_EMPTY_PASSWORD=yes','mysql:8.4']).status,0);
try {
  let ready=false;
  for(let i=0;i<60;i++) {
    if(run(['exec',name,'mysql','--protocol=TCP','-h127.0.0.1','-uroot','-N','-B','-e','SELECT 1']).status===0){ready=true;break;}
    await new Promise(r=>setTimeout(r,500));
  }
  assert(ready);
  const meta=JSON.parse(run(['inspect',name]).stdout)[0];
  assert.equal(meta.HostConfig.NetworkMode,'none'); assert.equal(meta.HostConfig.AutoRemove,true);
  assert.equal(Object.keys(meta.HostConfig.PortBindings??{}).length,0); assert(!meta.HostConfig.Binds?.length);
  sql('CREATE DATABASE fixture; CREATE DATABASE absent; CREATE DATABASE partial;',null);
  const seed=`CREATE TABLE application_schema_migrations(version VARCHAR(100) PRIMARY KEY,
    description VARCHAR(255) NOT NULL,applied_at DATETIME(6) NOT NULL);`;
  sql(migrations[3],'absent',false);
  sql(seed); sql(migrations[3],'fixture',false);
  assert.equal(sql("SELECT COUNT(*) FROM application_schema_migrations"),'0');
  // Synthetic backup/restore rehearsal before DDL; never reads the configured shared DB.
  sql(`CREATE TABLE existing_fixture(id BIGINT PRIMARY KEY, title VARCHAR(255), payload BLOB);
    INSERT INTO existing_fixture VALUES(1,'한글·도보 안내',UNHEX('000AFF')), (2,'quote '' and slash \\ value',NULL);
    CREATE VIEW existing_fixture_view AS SELECT id,title FROM existing_fixture;
    CREATE TRIGGER existing_fixture_guard BEFORE INSERT ON existing_fixture FOR EACH ROW SET NEW.title=TRIM(NEW.title);
    CREATE PROCEDURE existing_fixture_count() SELECT COUNT(*) FROM existing_fixture;
    CREATE EVENT existing_fixture_event ON SCHEDULE EVERY 1 DAY DISABLE DO SELECT 1;`);
  const beforeRows=sql('SELECT id,HEX(title),HEX(payload) FROM existing_fixture ORDER BY id;');
  const dump=run(['exec',name,'mysqldump','--protocol=TCP','-h127.0.0.1','-uroot',
    '--single-transaction','--quick','--routines','--triggers','--events','--hex-blob','--no-tablespaces',
    '--set-gtid-purged=OFF','--skip-dump-date','fixture']);
  assert.equal(dump.status,0,'synthetic backup must succeed before DDL');
  sql('CREATE DATABASE backup_restore;',null);
  sql(dump.stdout,'backup_restore');
  assert.equal(sql('SELECT id,HEX(title),HEX(payload) FROM existing_fixture ORDER BY id;','backup_restore'),beforeRows);
  assert.equal(sql('SELECT COUNT(*) FROM existing_fixture_view;','backup_restore'),'2');
  assert.equal(sql('CALL existing_fixture_count();','backup_restore'),'2');
  assert.equal(sql("SELECT COUNT(*) FROM information_schema.events WHERE event_schema=DATABASE() AND event_name='existing_fixture_event';",'backup_restore'),'1');
  sql("INSERT INTO existing_fixture VALUES(3,' trimmed ',NULL);",'backup_restore');
  assert.equal(sql('SELECT title FROM existing_fixture WHERE id=3;','backup_restore'),'trimmed');
  for(const migration of migrations) sql(migration);
  assert.equal(sql('SELECT id,HEX(title),HEX(payload) FROM existing_fixture ORDER BY id;'),beforeRows);
  assert.equal(sql('SELECT COUNT(*) FROM application_schema_migrations'),'5');
  assert.equal(sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='automatic_journey_preparations' AND column_name='walking_snapshot_id' AND is_nullable='YES' AND collation_name='ascii_bin'"),'1');
  sql(migrations[4],'fixture',false);
  assert.equal(sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='journey_walking_snapshots'"),'7');
  sql(`INSERT INTO journey_walking_snapshots(version,member_id,schedule_id,snapshot_id,expires_at_ms,contract_json)
    VALUES(0,7,9001,'11111111-1111-1111-1111-111111111111',1,'{}');`);
  sql(`INSERT INTO journey_walking_snapshots(version,member_id,schedule_id,snapshot_id,expires_at_ms,contract_json)
    VALUES(0,7,9001,'22222222-2222-2222-2222-222222222222',1,'{}');`,'fixture',false);
  sql(`INSERT INTO journey_walking_snapshots(version,member_id,schedule_id,snapshot_id,expires_at_ms,contract_json)
    VALUES(0,8,9002,'11111111-1111-1111-1111-111111111111',1,'{}');`,'fixture',false);
  sql(migrations[3],'fixture',false);
  assert.equal(sql('SELECT COUNT(*) FROM journey_walking_snapshots'),'1');
  assert.equal(sql('SELECT COUNT(*) FROM application_schema_migrations'),'5');
  sql(seed+"INSERT INTO application_schema_migrations VALUES('2026-10-06-journey-activity-lanes-v1','fixture',NOW(6)); CREATE TABLE journey_walking_snapshots(id BIGINT PRIMARY KEY);",'partial');
  sql(migrations[3],'partial',false);
  assert.equal(sql("SELECT COUNT(*) FROM application_schema_migrations WHERE version='2026-10-12-journey-walking-snapshots-v1'",'partial'),'0');
  console.log(JSON.stringify({result:'PASS',mysql:'8.4',network:'none',migrationsApplied:5,
    missingSchemaBlocked:true,missingPredecessorBlocked:true,partialSchemaBlocked:true,
    repeatBlocked:true,ownerUnique:true,idUnique:true,existingRowPreserved:true,
    syntheticBackupRestore:true,restoredViewsRoutinesTriggersEvents:true,productionModified:false}));
} finally {
  // Exact randomly-created container, never a shared database or Docker volume.
  assert.equal(run(['stop','--time','5',name]).status,0);
}
