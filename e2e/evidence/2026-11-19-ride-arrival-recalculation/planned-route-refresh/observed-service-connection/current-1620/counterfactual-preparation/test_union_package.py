"""Controlled data-shape integrity only, HTTP zero, never actual accuracy evidence."""
import copy,json,unittest
from pathlib import Path
from compare_actual_station_union import build_union_package,key
class UnionPackage(unittest.TestCase):
 def fixture(self):
  r=Path('/private/tmp/nolate-eta-real-e2e.uf14uvsd');w=json.loads((r/'evaluation-3.json').read_text());f=w['plannedRefreshReplay']['frames'][-1];shadow=f['stations'].pop(1);return w,shadow
 def test_original_clocks_plan_results_and_inputs_are_immutable(self):
  w,s=self.fixture();original=copy.deepcopy(w);p=build_union_package(w,s);self.assertEqual(w,original)
  f=w['plannedRefreshReplay']['frames'][-1]
  for k,v in f.items():
   if k!='stations':self.assertEqual(p['frame'][k],v)
  self.assertEqual([key(x) for x in p['frame']['stations']],[key(f['stations'][0]),key(s),key(f['stations'][1])])
  self.assertEqual(p['classification'],'RECORDED_CONCURRENT_STATION_INPUT_UNION')
 def test_existing_or_different_endpoint_cannot_replace_primary_data(self):
  for kind in ['existing','different-station','wrong-direction','wrong-line','wrong-limit']:
   w,s=self.fixture()
   if kind=='existing':w['plannedRefreshReplay']['frames'][-1]['stations'].insert(1,copy.deepcopy(s))
   if kind=='different-station':s['station']='신림'
   if kind=='wrong-direction':s['directionCode']='UP'
   if kind=='wrong-line':s['line']='2호선'
   if kind=='wrong-limit':s['limit']=40
   with self.subTest(kind=kind),self.assertRaises(ValueError):build_union_package(w,s)
 def test_an_unexpected_query_order_cannot_be_silently_repaired(self):
  w,s=self.fixture();w['plannedRefreshReplay']['frames'][-1]['stations'].reverse()
  with self.assertRaises(ValueError):build_union_package(w,s)
if __name__=='__main__':unittest.main()
