import unittest
from acceptance_planning import plan_execution, GIB


class PlanningTest(unittest.TestCase):
    profiles = ['core-testnet', 'production-quotes']

    def plan(self, **overrides):
        args = dict(required_profiles=self.profiles,
                    targeted_checks={p: True for p in self.profiles},
                    stable_peers={p: dict(identity='a'*64, passed_identity='a'*64, passed=True) for p in self.profiles},
                    available_memory_bytes=16*GIB)
        args.update(overrides)
        return plan_execution(**args)

    def test_stable_current_peers_can_overlap_but_still_need_guard(self):
        result = self.plan()
        self.assertEqual(result['mode'], 'parallel')
        self.assertTrue(result['resource_admission_required'])
        self.assertTrue(result['peer_failure_cancels_batch'])

    def test_missing_failing_changed_peer_and_memory_fall_back(self):
        cases = [dict(stable_peers={}), dict(targeted_checks={}),
                 dict(available_memory_bytes=16*GIB-1),
                 dict(stable_peers={p: dict(identity='a'*64, passed_identity='b'*64, passed=True) for p in self.profiles}),
                 dict(stable_peers={p: dict(identity='a'*64, passed_identity='a'*64, passed=False) for p in self.profiles})]
        for case in cases:
            with self.subTest(case=case):
                self.assertEqual(self.plan(**case)['mode'], 'sequential')

    def test_single_profile_never_adds_acceptance(self):
        result = self.plan(required_profiles=['production-quotes'])
        self.assertEqual(result['profiles'], ['production-quotes'])
        self.assertEqual(result['max_concurrency'], 1)

    def test_unknown_and_duplicate_profiles_rejected(self):
        for profiles in [['unknown'], ['core-testnet', 'core-testnet']]:
            with self.assertRaises(ValueError):self.plan(required_profiles=profiles)
