import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {maintenanceOptions,checkMaintenanceConnections,maintenanceSqlPrefix,maintenanceConnectionArgs} from './journey-maintenance-policy.mjs';
const script=fileURLToPath(new URL('./journey-migrate-confirmed-maintenance.mjs',import.meta.url));
function blocked(args,expected) {
 const result=spawnSync(process.execPath,[script,...args],{encoding:'utf8',timeout:5000,env:{PATH:'/usr/bin:/bin'}});
 assert.equal(result.status,1);assert.equal(result.stderr,'');
 const value=JSON.parse(result.stdout);
 assert.equal(value.result,'BLOCKED');assert.equal(value.code,expected);
 assert.equal(value.applied,0);assert.equal(value.sharedDDLStarted,false);assert.equal(value.partialDDLMayExist,false);
 assert.equal(value.automaticRollback,false);return result.stdout;
}
test('maintenance helper rejects absent flag and relative config before any database action',()=>{
 blocked([],'EXPLICIT_MAINTENANCE_AND_CONFIG_REQUIRED');
 blocked(['--apply-confirmed-maintenance','relative.properties'],'EXPLICIT_MAINTENANCE_AND_CONFIG_REQUIRED');
 blocked(['--dry-run','/private/tmp/not-opened'],'EXPLICIT_MAINTENANCE_AND_CONFIG_REQUIRED');
});
test('unknown target is rejected without credential or config content output',()=>{
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'nolate-maintenance-guard-'));
 const config=path.join(directory,'synthetic.properties');
 try {
  fs.writeFileSync(config,'MY_SQL_END_POINT=example.invalid\nMY_SQL_PORT=3306\nMY_SQL_DB_NAME=late_project\nMY_SQL_DB_USERNAME=synthetic\nMY_SQL_DB_PASSWORD=synthetic-never-transmit\n',{mode:0o600});
  const output=blocked(['--apply-confirmed-maintenance',config],'TARGET_MISMATCH');
  assert.equal(output.includes('synthetic-never-transmit'),false);assert.equal(output.includes('example.invalid'),false);
 } finally {fs.unlinkSync(config);fs.rmdirSync(directory);}
});
test('missing config file returns sanitized failure without paths or stack trace',()=>{
 const missing='/private/tmp/nolate-does-not-exist-config-20261012';
 const output=blocked(['--apply-confirmed-maintenance',missing],'MAINTENANCE_FAILED_KEEP_SERVICES_STOPPED');
 assert.equal(output.includes(missing),false);assert.equal(output.includes('Error:'),false);
});
test('idle exception requires exact explicit flag and does not weaken default',()=>{
 assert.deepEqual(maintenanceOptions(['--apply-confirmed-maintenance','/safe/config']),{configPath:'/safe/config',allowIdle:false});
 assert.deepEqual(maintenanceOptions(['--apply-confirmed-maintenance','/safe/config','--allow-idle-connections']),{configPath:'/safe/config',allowIdle:true});
 for(const args of [['--apply-confirmed-maintenance','/safe/config','--force'],['--apply-confirmed-maintenance','relative','--allow-idle-connections'],['--apply-confirmed-maintenance','/safe/config','--allow-idle-connections','extra']])
  assert.throws(()=>maintenanceOptions(args),/EXPLICIT_MAINTENANCE/);
 assert.throws(()=>checkMaintenanceConnections(['Sleep\t1'],false),/ACTIVE_CONNECTIONS/);
 assert.equal(checkMaintenanceConnections([],false),0);
});
test('approved idle exception allows at most 3 Sleep sessions, never active or malformed sessions',()=>{
 assert.equal(checkMaintenanceConnections(['Sleep\t3'],true),3);
 for(const rows of [['Sleep\t4'],['Sleep\t3','Query\t1'],['Execute\t1'],['Sleep\tbad'],['Sleep\t-1'],['Sleep\t9007199254740992']])
  assert.throws(()=>checkMaintenanceConnections(rows,true));
 assert.equal(maintenanceSqlPrefix,'SET SESSION lock_wait_timeout=5; SET SESSION innodb_lock_wait_timeout=5;\n');
});
test('mysqldump excludes unsupported connect-timeout but preserves encryption and target',()=>{
 const args=['--protocol=TCP','--ssl-mode=REQUIRED','--connect-timeout=5','-h','example.invalid','-P','3306','-u','synthetic'];
 assert.deepEqual(maintenanceConnectionArgs('mysql',args),args);
 assert.deepEqual(maintenanceConnectionArgs('mysqldump',args),args.filter(arg=>arg!=='--connect-timeout=5'));
 assert.equal(args.includes('--connect-timeout=5'),true);
});
