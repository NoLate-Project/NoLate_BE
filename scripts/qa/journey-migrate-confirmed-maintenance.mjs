// Operator-only maintenance action. Never called from app startup, a build or a test.
// Run ONLY after the human confirms API/workers are STOPPED, not merely permission to stop.
// An explicitly authorized maximum of 3 idle sessions may remain. Never kill connections.
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {createHash,randomBytes} from 'node:crypto';
import {maintenanceOptions,checkMaintenanceConnections,maintenanceSqlPrefix,maintenanceConnectionArgs} from './journey-maintenance-policy.mjs';
const docker='/opt/homebrew/bin/docker';
const steps=[
 ['2026-10-03-journey-guidance-sessions.sql','2026-10-03-journey-guidance-sessions-v1'],
 ['2026-10-04-automatic-journey-preparations.sql','2026-10-04-automatic-journey-preparations-v1'],
 ['2026-10-06-journey-activity-lanes.sql','2026-10-06-journey-activity-lanes-v1'],
 ['2026-10-12-journey-walking-snapshots.sql','2026-10-12-journey-walking-snapshots-v1'],
 ['2026-10-12-mixed-walking-binding.sql','2026-10-12-mixed-walking-binding-v1'],
];
const newTables=['journey_guidance_sessions','automatic_journey_preparations','journey_activity_lanes','journey_walking_snapshots'];
let backupDirectory;
let applied=0;
let sharedDDLStarted=false;
let currentStep;
let restoreContainer;
let databaseFailure;
try {
 const options=maintenanceOptions(process.argv.slice(2));
 const config=Object.fromEntries(fs.readFileSync(options.configPath,'utf8').split(/\r?\n/).filter(l=>l&&!l.startsWith('#')).map(l=>{
  const at=l.indexOf('=');return [l.slice(0,at).trim(),l.slice(at+1).trim()];
 }));
 if(config.MY_SQL_END_POINT!=='backjin.iptime.org'||config.MY_SQL_DB_NAME!=='late_project'||config.MY_SQL_PORT!=='3306'
   ||!config.MY_SQL_DB_USERNAME||!config.MY_SQL_DB_PASSWORD)throw Error('TARGET_MISMATCH');
 const env={PATH:'/opt/homebrew/bin:/usr/bin:/bin',HOME:process.env.HOME,MYSQL_PWD:config.MY_SQL_DB_PASSWORD};
 const connection=['--protocol=TCP','--ssl-mode=REQUIRED','--connect-timeout=5','-h',config.MY_SQL_END_POINT,'-P','3306','-u',config.MY_SQL_DB_USERNAME];
 function run(command,args,options={}){
  // mysqldump does not support mysql CLI's --connect-timeout option.
  // Its whole process still has a bounded timeout; never weaken TLS.
  const commandConnection=maintenanceConnectionArgs(command,connection);
  const r=spawnSync(docker,['run','--rm','-i','--pull=never','-e','MYSQL_PWD','mysql:8.4',command,...commandConnection,...args],
   {env,encoding:'utf8',timeout:120000,maxBuffer:4*1024*1024,...options});
  if(r.status!==0) {
   const failure=r.stderr?.toString()??'';
   databaseFailure={operation:command,exitCode:r.status,mysqlErrorNumber:failure.match(/(?:ERROR\s+|\()(\d{4})(?:\b|\))/)?.[1],
    accessDenied:/access denied|privilege/i.test(failure),columnStatistics:/column.statistics|COLUMN_STATISTICS/i.test(failure),
    routineAccess:/routine|SHOW CREATE FUNCTION|SHOW CREATE PROCEDURE/i.test(failure),
    spawnError:r.error?.code};
   if(/ERROR (1205|1213)\b/.test(failure))throw Error('DATABASE_LOCK_CONFLICT_KEEP_SERVICES_STOPPED');
   throw Error('DATABASE_OPERATION_FAILED_KEEP_SERVICES_STOPPED');
  }return r.stdout;
 }
 const sql=query=>run('mysql',['-N','-B','late_project'],{input:maintenanceSqlPrefix+query}).trim();
 function idle(){
  const rows=sql('SELECT COMMAND,COUNT(*) FROM information_schema.processlist WHERE db=DATABASE() AND id<>CONNECTION_ID() GROUP BY COMMAND;');
  return checkMaintenanceConnections(rows?rows.split('\n'):[],options.allowIdle);
 }
 const idleConnections=idle();
 console.log(JSON.stringify({preflight:'PASS',idleConnections,allowIdleConnections:options.allowIdle,ddlLockWaitSeconds:5,forcedConnectionTermination:false}));
 if(sql(`SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN (${newTables.map(t=>`'${t}'`).join(',')});`)!=='0')throw Error('PARTIAL_OR_APPLIED_SCHEMA_REQUIRES_INSPECTION');
 const before=sql('SELECT version FROM application_schema_migrations ORDER BY version;').split('\n').filter(Boolean);
 if(steps.some(([,version])=>before.includes(version)))throw Error('PARTIAL_OR_APPLIED_MARKERS_REQUIRES_INSPECTION');
 const tableNames=sql("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name;").split('\n');
 if(!tableNames.every(t=>/^[a-zA-Z0-9_]+$/.test(t)))throw Error('UNSUPPORTED_TABLE_IDENTIFIER');
 const counts=()=>sql(tableNames.filter(t=>t!=='application_schema_migrations').map(t=>`SELECT '${t}',COUNT(*) FROM \`${t}\`;`).join('\n'));
 const beforeCounts=counts();
 backupDirectory=fs.mkdtempSync('/private/tmp/nolate-journey-db-backup-');fs.chmodSync(backupDirectory,0o700);
 const backupFile=path.join(backupDirectory,'before.sql');const fd=fs.openSync(backupFile,'wx',0o600);
 try {run('mysqldump',['--single-transaction','--quick','--routines','--triggers','--events','--hex-blob','--no-tablespaces',
  '--set-gtid-purged=OFF','--skip-dump-date','late_project'],{stdio:['pipe',fd,'pipe'],timeout:300000});}
 finally {fs.fsyncSync(fd);fs.closeSync(fd);}
 if(fs.statSync(backupFile).size<100)throw Error('BACKUP_INVALID_NO_MIGRATIONS_APPLIED');
 const backupHash=createHash('sha256').update(fs.readFileSync(backupFile)).digest('hex');
 fs.writeFileSync(path.join(backupDirectory,'manifest.json'),JSON.stringify({database:'late_project',createdAt:new Date().toISOString(),
  backupSHA256:backupHash,backupBytes:fs.statSync(backupFile).size,restoreTested:false,tls:'REQUIRED; server certificate identity not verified',
  idleConnections,allowIdleConnections:options.allowIdle,ddlLockWaitSeconds:5,
  oldMarkers:before,oldTableRowCounts:beforeCounts,steps:steps.map(([file,version])=>({file,version}))},null,2),{mode:0o600});
 console.log(JSON.stringify({backupCreated:true,sharedDDLStarted:false}));
 // Restore the backup into an ephemeral, network-isolated server before shared DDL.
 // No published ports, host mounts or production credentials enter this server.
 const restoreName=`nolate-backup-check-${randomBytes(8).toString('hex')}`;
 restoreContainer=restoreName;
 const restorePassword=randomBytes(32).toString('hex');
 const restoreEnv={PATH:env.PATH,HOME:env.HOME,MYSQL_ROOT_PASSWORD:restorePassword,MYSQL_PWD:restorePassword};
 function isolated(args,options={}) {
  const r=spawnSync(docker,args,{env:restoreEnv,encoding:'utf8',timeout:120000,maxBuffer:4*1024*1024,...options});
  if(r.status!==0)throw Error('BACKUP_RESTORE_FAILED_NO_MIGRATIONS_APPLIED');return r.stdout;
 }
 try {
  isolated(['run','--rm','-d','--pull=never','--network','none','--name',restoreName,'-e','MYSQL_ROOT_PASSWORD','mysql:8.4']);
  let ready=false;
  for(let attempt=0;attempt<60;attempt++) {
   const r=spawnSync(docker,['exec','-e','MYSQL_PWD',restoreName,'mysql','--protocol=TCP','-h','127.0.0.1','-u','root','-N','-B','-e','SELECT 1;'],
    {env:restoreEnv,encoding:'utf8',timeout:5000});
   if(r.status===0&&r.stdout.trim()==='1'){ready=true;break;}
   await new Promise(resolve=>setTimeout(resolve,1000));
  }
  if(!ready)throw Error('BACKUP_RESTORE_FAILED_NO_MIGRATIONS_APPLIED');
  const command=['exec','-i','-e','MYSQL_PWD',restoreName,'mysql','-u','root','-N','-B'];
  isolated([...command,'-e','CREATE DATABASE late_project CHARACTER SET utf8mb4;']);
  const input=fs.openSync(backupFile,'r');
  try {isolated([...command,'late_project'],{stdio:[input,'pipe','pipe'],timeout:300000});}
  finally {fs.closeSync(input);}
  const restoredCounts=isolated([...command,'late_project'],{input:tableNames.filter(t=>t!=='application_schema_migrations')
   .map(t=>`SELECT '${t}',COUNT(*) FROM \`${t}\`;`).join('\n')}).trim();
  const restoredMarkers=isolated([...command,'late_project'],{input:'SELECT version FROM application_schema_migrations ORDER BY version;'}).trim();
  if(restoredCounts!==beforeCounts||restoredMarkers!==before.join('\n'))throw Error('BACKUP_RESTORE_VERIFICATION_FAILED_NO_MIGRATIONS_APPLIED');
  const manifest=JSON.parse(fs.readFileSync(path.join(backupDirectory,'manifest.json'),'utf8'));
  manifest.restoreTested=true;manifest.restoreChecks=['SQL import','existing row counts','schema markers'];
  fs.writeFileSync(path.join(backupDirectory,'manifest.json'),JSON.stringify(manifest,null,2),{mode:0o600});
  console.log(JSON.stringify({backupRestoreTested:true,sharedDDLStarted:false}));
 } finally {
  const stopped=spawnSync(docker,['stop','-t','5',restoreName],{env:restoreEnv,encoding:'utf8',timeout:20000});
  const remaining=spawnSync(docker,['ps','-a','--filter',`name=^/${restoreName}$`,'--format','{{.Names}}'],
   {env:restoreEnv,encoding:'utf8',timeout:10000});
  if(stopped.status!==0||remaining.status!==0||remaining.stdout.trim())throw Error('BACKUP_RESTORE_CONTAINER_CLEANUP_REQUIRED_NO_MIGRATIONS_APPLIED');
  restoreContainer=undefined;
 }
 idle();
 if(counts()!==beforeCounts||sql('SELECT version FROM application_schema_migrations ORDER BY version;')!==before.join('\n'))
  throw Error('SOURCE_CHANGED_DURING_BACKUP_NO_MIGRATIONS_APPLIED');
 // Backup exists and is fsynced before the first DDL. Never auto-restore/drop after partial DDL.
 const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../../docs/schedule/migrations');
 for(const [file,version] of steps){
  idle();currentStep=version;sharedDDLStarted=true;sql(fs.readFileSync(path.join(root,file),'utf8'));
  if(sql(`SELECT COUNT(*) FROM application_schema_migrations WHERE version='${version}';`)!=='1')throw Error('POSTCONDITION_FAILED_KEEP_SERVICES_STOPPED');
  applied++;console.log(JSON.stringify({step:applied,version,result:'APPLIED'}));
 }
 if(counts()!==beforeCounts)throw Error('EXISTING_ROW_COUNTS_CHANGED_KEEP_SERVICES_STOPPED');
 const after=sql('SELECT version FROM application_schema_migrations ORDER BY version;').split('\n').filter(Boolean);
 if(after.length!==before.length+5||!before.every(v=>after.includes(v)))throw Error('MARKER_VERIFICATION_FAILED_KEEP_SERVICES_STOPPED');
 const columns=sql("SELECT table_name,COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('journey_guidance_sessions','automatic_journey_preparations','journey_activity_lanes','journey_walking_snapshots') GROUP BY table_name ORDER BY table_name;");
 if(columns!==['automatic_journey_preparations\t22','journey_activity_lanes\t25','journey_guidance_sessions\t24','journey_walking_snapshots\t7'].join('\n'))throw Error('COLUMN_VERIFICATION_FAILED_KEEP_SERVICES_STOPPED');
 const manifest=JSON.parse(fs.readFileSync(path.join(backupDirectory,'manifest.json'),'utf8'));
 manifest.migrationResult='PASS';manifest.applied=applied;manifest.completedAt=new Date().toISOString();
 manifest.existingRowCountsPreserved=true;manifest.finalMarkers=after;manifest.finalColumns=columns;
 fs.writeFileSync(path.join(backupDirectory,'manifest.json'),JSON.stringify(manifest,null,2),{mode:0o600});
 console.log(JSON.stringify({result:'PASS',applied,backupDirectory,backupSHA256:backupHash,existingRowCountsPreserved:true,
  featureEnabled:false,applicationDeployed:false,backupRestoreTested:true}));
} catch(error){
 const code=error instanceof Error&&/^[A-Z_]+$/.test(error.message)?error.message:'MAINTENANCE_FAILED_KEEP_SERVICES_STOPPED';
 console.log(JSON.stringify({result:'BLOCKED',code,applied,backupDirectory,sharedDDLStarted,currentStep,
  partialDDLMayExist:sharedDDLStarted,restoreContainer,databaseFailure,automaticRollback:false}));process.exitCode=1;
}
