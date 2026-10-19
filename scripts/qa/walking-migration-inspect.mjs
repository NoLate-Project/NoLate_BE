// Read-only inspection of the existing explicitly configured database. Never prints credentials or rows.
import fs from 'node:fs';
import {spawnSync} from 'node:child_process';
const file = '/Users/mac/IdeaProjects/NoLate/NoLate_BE/env.local.properties';
const config = Object.fromEntries(fs.readFileSync(file, 'utf8').split(/\r?\n/)
  .filter(line => line && !line.startsWith('#')).map(line => {
    const at = line.indexOf('='); return [line.slice(0, at).trim(), line.slice(at + 1).trim()];
  }));
if (config.MY_SQL_END_POINT !== 'backjin.iptime.org' || config.MY_SQL_DB_NAME !== 'late_project'
  || config.MY_SQL_PORT !== '3306' || !config.MY_SQL_DB_USERNAME || !config.MY_SQL_DB_PASSWORD) throw Error('TARGET_MISMATCH');
const query = `SELECT 'server', VERSION(), DATABASE(), @@read_only;
SHOW SESSION STATUS LIKE 'Ssl_cipher';
SELECT 'migration_table', COUNT(*) FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name='application_schema_migrations';
SELECT 'journey_table', table_name FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_name LIKE '%journey%';
SELECT 'journey_marker', version FROM application_schema_migrations WHERE version LIKE '%journey%';
SELECT 'other_connections', COUNT(*) FROM information_schema.processlist
 WHERE db=DATABASE() AND id<>CONNECTION_ID();`;
const result = spawnSync('/opt/homebrew/bin/docker', ['run','--rm','-i','-e','MYSQL_PWD',
  'mysql:8.4','mysql','--protocol=TCP','--ssl-mode=REQUIRED','--connect-timeout=5',
  '-h',config.MY_SQL_END_POINT,'-P',config.MY_SQL_PORT,'-u',config.MY_SQL_DB_USERNAME,
  '-N','-B',config.MY_SQL_DB_NAME], {
  input: query, encoding:'utf8', timeout:20000,
  env:{PATH:'/opt/homebrew/bin:/usr/bin:/bin',HOME:'/Users/mac',MYSQL_PWD:config.MY_SQL_DB_PASSWORD},
});
if(result.status!==0) { console.log('READ_ONLY_INSPECTION_FAILED; no schema writes attempted'); process.exitCode=1; }
else console.log(result.stdout.trim());
