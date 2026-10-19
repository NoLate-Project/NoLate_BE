// Pure policy: idle sessions are allowed only by an explicit operator opt-in.
export function maintenanceOptions(args) {
 const allowIdle=args.length===3&&args[2]==='--allow-idle-connections';
 if((args.length!==2&&!allowIdle)||args[0]!=='--apply-confirmed-maintenance'
   ||typeof args[1]!=='string'||!args[1].startsWith('/'))
  throw Error('EXPLICIT_MAINTENANCE_AND_CONFIG_REQUIRED');
 return {configPath:args[1],allowIdle};
}
export function checkMaintenanceConnections(rows,allowIdle) {
 let total=0;
 for(const row of rows) {
  const [command,countText]=row.split('\t');const count=Number(countText);
  if(!command||!/^\d+$/.test(countText??'')||!Number.isSafeInteger(count)||count<0)
   throw Error('CONNECTION_INSPECTION_INVALID');
  total+=count;
  if(count>0&&(!allowIdle||command!=='Sleep'))throw Error('DATABASE_ACTIVE_CONNECTIONS_KEEP_SERVICES_STOPPED');
 }
 if(total>(allowIdle?3:0))throw Error('DATABASE_CONNECTION_LIMIT_KEEP_SERVICES_STOPPED');
 return total;
}
export const maintenanceSqlPrefix='SET SESSION lock_wait_timeout=5; SET SESSION innodb_lock_wait_timeout=5;\n';
export function maintenanceConnectionArgs(command,connection) {
 return command==='mysqldump'?connection.filter(arg=>!arg.startsWith('--connect-timeout=')):connection;
}
