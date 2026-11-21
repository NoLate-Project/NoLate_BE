"""Controlled offline fences only; never invokes main/HTTP or changes a captured evaluation."""
import copy,datetime as dt,importlib.util,json,unittest
from pathlib import Path
p=Path(__file__).with_name('capture_counterfactual_station.py');spec=importlib.util.spec_from_file_location('cf',p);cf=importlib.util.module_from_spec(spec);spec.loader.exec_module(cf)
class CounterfactualFences(unittest.TestCase):
 def fixture(self):
  w=copy.deepcopy(json.loads((cf.ROOT/'evaluation-1.json').read_text()));w['historyCount']=2;w['effectiveSnapshot']['inProgressPlannedRoute']=w['effectiveSnapshot']['lastTimetablePlannedRoute'];w['nextCheckAt']='2026-11-20T08:10:32Z';w['evaluatedAt']='2026-11-20T08:09:32Z';return w
 def clock_fixture(self):
  return ({'requestedAt':'2026-11-20T08:19:30.000Z','receivedAt':'2026-11-20T08:19:30.200Z'},
   {'classification':'REAL_PROVIDER_ISOLATED_WORKER','database':'H2_MEMORY_ONLY','clockMode':'SYSTEM_WALL_CLOCK','providersMocked':False,'journeyProvider':'odsay','memberId':296,'scheduleId':363,'historyCount':3,'evaluatedAt':'2026-11-20T08:19:32Z','plannedRefreshReplay':{'frames':[{}, {'evaluatedAt':'2026-11-20T08:19:30.100Z','incomplete':False}]}},
   [{'freshnessEvidence':'PROVIDER_SOURCE_TIMESTAMP','sourceUpdatedAt':'2026-11-20T08:18:55Z'}])
 def test_prospective_window_uses_the_existing_real_plan_with_no_http(self):
  self.assertEqual(cf.prospective_window(self.fixture(),cf.instant('2026-11-20T08:09:33Z'))['at'],'2026-11-20T08:19:30+00:00')
 def test_synthetic_manual_other_database_or_provider_failure_cannot_arm(self):
  for field,value in [('providersMocked',True),('database','MYSQL'),('clockMode','TEST_CLOCK'),('historyCount',3),('providerFailures',[{}])]:
   w=self.fixture();w[field]=value
   with self.subTest(field=field),self.assertRaises(ValueError):cf.prospective_window(w,cf.instant('2026-11-20T08:09:33Z'))
 def test_matching_current_decision_and_commit_are_eligible_without_clock_changes(self):
  args=self.clock_fixture();before=copy.deepcopy(args);self.assertEqual(cf.eligibility(*args),[]);self.assertEqual(args,before)
 def test_late_request_receipt_and_future_source_are_excluded(self):
  for case in ['late-request','after-commit','future-source','wrong-evaluation','incomplete']:
   meta,w,rows=self.clock_fixture()
   if case=='late-request':meta['requestedAt']='2026-11-20T08:19:32Z'
   if case=='after-commit':meta['receivedAt']='2026-11-20T08:19:33Z'
   if case=='future-source':rows[0]['sourceUpdatedAt']='2026-11-20T08:19:30.150Z'
   if case=='wrong-evaluation':w['historyCount']=2
   if case=='incomplete':w['plannedRefreshReplay']['frames'][-1]['incomplete']=True
   self.assertTrue(cf.eligibility(meta,w,rows),case)
 def test_missing_or_wrong_real_scope_cannot_qualify(self):
  for field in ['classification','database','clockMode','providersMocked','journeyProvider','memberId','scheduleId']:
   args=self.clock_fixture();args[1].pop(field);self.assertEqual(cf.eligibility(*args),['WRONG_REAL_EVALUATION_SCOPE'])
 def test_past_window_and_no_longer_relevant_inbound_context_do_not_trigger_reads(self):
  with self.assertRaises(ValueError):cf.prospective_window(self.fixture(),cf.instant('2026-11-20T08:20:31Z'))
  w=self.fixture();w['effectiveSnapshot']['inProgressPlannedRoute']['segments'][1]['rideTiming']['predictedArrivalAt']='2026-11-20T08:40:00Z'
  with self.assertRaises(ValueError):cf.prospective_window(w,cf.instant('2026-11-20T08:09:33Z'))
if __name__=='__main__':unittest.main()
