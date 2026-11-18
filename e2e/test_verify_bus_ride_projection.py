import unittest

from verify_bus_ride_projection import evaluate, instant


class BusRideProjectionTest(unittest.TestCase):
    def setUp(self):
        self.catalog = {'routeId': '100100057', 'matchedCatalog': {}}
        self.observation = {'requestedRoute': '100100057', 'requestedStops': ['23287', '23288'], 'calls': []}
        for ars, station, order, waits in (
                ('23287', '122000184', '71', ('310', '1126')),
                ('23288', '122000185', '72', ('406', '1221'))):
            stop = {'busRouteId': '100100057', 'station': station, 'seq': order, 'direction': '복정역환승센터'}
            self.catalog['matchedCatalog'][ars] = stop
            self.observation['calls'].append({
                'status': 200, 'providerCode': '0', 'requestedArs': ars,
                'receivedAt': '2026-11-15T22:39:27.900+09:00', 'rows': [{
                    'busRouteId': stop['busRouteId'], 'arsId': ars, 'stId': station, 'staOrd': order,
                    'vehicleSlot1': 'a01b029099da', 'traTime1': waits[0],
                    'vehicleSlot2': 'f49d404087cf', 'traTime2': waits[1],
                    'mkTm': '2026-11-15 22:39:25.0',
                }],
            })
        self.board, self.alight = [call['rows'][0] for call in self.observation['calls']]

    def evaluate(self, departure='2026-11-15T22:40:00+09:00'):
        return evaluate(self.observation, self.catalog, instant(departure), 157, 360)

    def test_rejects_missing_provider_clock_even_with_same_vehicles_and_receipt_times(self):
        self.board['mkTm'] = None
        self.board['repTm1'] = '2021-12-26 20:06:18.0'
        result = self.evaluate()
        self.assertEqual('SOURCE_TIMESTAMP_MISSING', result['status'])
        self.assertEqual([95, 96], result['sameVehicleCountdownDifferencesSeconds'])
        self.assertNotIn('destinationAt', result)

    def test_projects_only_same_vehicle_and_keeps_original_egress(self):
        # Provider ordering can change, so identity must follow the vehicle across slots.
        self.alight.update(vehicleSlot1='f49d404087cf', traTime1='1221', vehicleSlot2='a01b029099da', traTime2='406')
        result = self.evaluate()
        self.assertEqual('SHADOW_PROJECTION_AVAILABLE', result['status'])
        self.assertEqual('2026-11-15T22:44:35+09:00', result['boardingAt'])
        self.assertEqual('2026-11-15T22:46:11+09:00', result['alightingAt'])
        self.assertEqual('2026-11-15T22:52:11+09:00', result['destinationAt'])
        self.assertFalse(result['usedForProductionEta'])

    def test_keeps_the_existing_boarding_buffer(self):
        self.assertEqual('NO_CATCHABLE_MATCHING_VEHICLE', self.evaluate('2026-11-15T23:00:00+09:00')['status'])
        # The first bus would be reachable by walking alone, but lacks the 60-second boarding margin.
        result = self.evaluate('2026-11-15T22:41:55+09:00')
        self.assertEqual('2026-11-15T22:58:11+09:00', result['boardingAt'])

    def test_excludes_separate_or_stale_source_snapshots(self):
        self.alight['mkTm'] = '2026-11-15 22:39:26'
        self.assertEqual('SOURCE_SNAPSHOTS_DIFFER', self.evaluate()['status'])
        self.alight['mkTm'] = '2026-11-15 22:36:26'
        self.assertEqual('SOURCE_TIMESTAMP_STALE_OR_FUTURE', self.evaluate()['status'])

    def test_excludes_wrong_stop_and_ambiguous_vehicle_identity(self):
        self.alight['stId'] = '122000184'
        self.assertEqual('ROUTE_OR_STOP_MISMATCH', self.evaluate()['status'])
        self.alight['stId'] = '122000185'
        self.alight['vehicleSlot1'] = self.alight['vehicleSlot2']
        self.assertEqual('NO_MATCHING_VEHICLE', self.evaluate()['status'])

    def test_projects_route_wide_snapshot_and_rejects_duplicate_stop_rows(self):
        self.observation = {
            'api': 'getArrInfoByRouteAll', 'route': '100100057',
            'requestedStops': ['23287', '23288'], 'httpStatus': 200, 'providerCode': '0',
            'receivedAt': '2026-11-16T09:19:54.040414+09:00',
            'matchedRows': [self.alight, self.board],
        }
        self.board.update(mkTm='2026-11-16 09:19:43.0', traTime1='5', traTime2='319')
        self.alight.update(mkTm=self.board['mkTm'], traTime1='141', traTime2='455')

        result = self.evaluate('2026-11-16T09:20:00+09:00')
        self.assertEqual('SHADOW_PROJECTION_AVAILABLE', result['status'])
        self.assertEqual([136, 136], result['sameVehicleCountdownDifferencesSeconds'])
        self.assertEqual('2026-11-16T09:25:02+09:00', result['boardingAt'])
        self.assertEqual('2026-11-16T09:33:18+09:00', result['destinationAt'])
        self.assertFalse(result['usedForProductionEta'])

        self.observation['matchedRows'].append(self.board.copy())
        self.assertEqual('ROUTE_OR_STOP_MISMATCH', self.evaluate('2026-11-16T09:20:00+09:00')['status'])


if __name__ == '__main__':
    unittest.main()
