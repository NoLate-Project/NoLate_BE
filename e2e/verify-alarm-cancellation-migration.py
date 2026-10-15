#!/usr/bin/env python3
"""Exercise additive SQL only in the explicitly named network-isolated ephemeral container."""
import json
from pathlib import Path
import subprocess

NAME = 'nolate-eta-cancel-migration-20261006'
VERSION = '2026-10-05-departure-alarm-cancellation-reason-v1'
meta = json.loads(subprocess.check_output(['docker', 'inspect', NAME]))[0]
assert meta['HostConfig']['NetworkMode'] == 'none'
assert meta['HostConfig']['AutoRemove'] is True and not meta['HostConfig']['Binds']
assert not meta['HostConfig']['PortBindings']


def sql(command, database=None, success=True):
    args = ['docker', 'exec', '-i', NAME, 'mysql', '-uroot', '-N', '-B']
    if database:
        args += [database]
    result = subprocess.run(args, input=command, text=True, capture_output=True, timeout=30)
    assert (result.returncode == 0) is success, result.stderr
    return result.stdout.strip()


migration = (Path(__file__).resolve().parents[1] / 'docs/schedule/migrations/2026-10-05-departure-alarm-cancellation-reason.sql').read_text()
sql('CREATE DATABASE absent; CREATE DATABASE fixture; CREATE DATABASE incompatible;')
sql(migration, 'absent', False)
assert sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='absent' AND table_name='application_schema_migrations'") == '0'
seed = '''CREATE TABLE application_schema_migrations (
    version VARCHAR(100) PRIMARY KEY, description VARCHAR(255) NOT NULL, applied_at DATETIME(6) NOT NULL);
CREATE TABLE departure_alarm_sync_state (id BIGINT PRIMARY KEY, operation VARCHAR(10) NOT NULL);
INSERT INTO departure_alarm_sync_state VALUES (1,'CANCEL'),(2,'UPSERT');
'''
sql(seed, 'fixture')
sql(migration, 'fixture', False)
assert sql(f"SELECT COUNT(*) FROM application_schema_migrations WHERE version='{VERSION}'", 'fixture') == '0'
sql("INSERT INTO application_schema_migrations VALUES ('2026-08-04-departure-alarm-plan-v2','plan v2',NOW(6))", 'fixture')
sql(migration, 'fixture')
sql(migration, 'fixture')
assert sql(f"SELECT COUNT(*) FROM application_schema_migrations WHERE version='{VERSION}'", 'fixture') == '1'
assert sql('SELECT COUNT(*) FROM departure_alarm_sync_state WHERE cancellation_reason IS NULL', 'fixture') == '2'
sql("INSERT INTO departure_alarm_sync_state VALUES (3,'CANCEL','TRANSIT_TRANSFER_FAILURE')", 'fixture')
sql("INSERT INTO departure_alarm_sync_state VALUES (4,'UPSERT','TRANSIT_TRANSFER_FAILURE')", 'fixture', False)
sql("INSERT INTO departure_alarm_sync_state VALUES (4,'CANCEL','UNRECOGNIZED')", 'fixture', False)
sql(seed + "INSERT INTO application_schema_migrations VALUES ('2026-08-04-departure-alarm-plan-v2','plan v2',NOW(6)); ALTER TABLE departure_alarm_sync_state ADD COLUMN cancellation_reason VARCHAR(10) NULL;", 'incompatible')
sql(migration, 'incompatible', False)
assert sql(f"SELECT COUNT(*) FROM application_schema_migrations WHERE version='{VERSION}'", 'incompatible') == '0'
print(json.dumps({'classification': 'ISOLATED_MYSQL_8_MIGRATION', 'result': 'PASSED',
                  'productionDatabaseModified': False, 'network': 'none',
                  'missingTablesBlocked': True, 'missingPredecessorBlocked': True,
                  'incompatibleColumnBlocked': True, 'idempotent': True,
                  'legacyReasonsRemainNull': True, 'invalidReasonRejected': True,
                  'upsertWithReasonRejected': True}, indent=2))
