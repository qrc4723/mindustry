import unittest

from mindustry_agent.runner import decision_delay, preflight_rts_queue_actions


class RunnerTimingTest(unittest.TestCase):
    def test_stockpile_rts_ignores_model_requested_long_wait(self) -> None:
        state = {"game_mode_variant": {"id": "stockpile_rts_pvp"}}
        self.assertEqual(decision_delay(state, 0.5, 30.0), 0.5)

    def test_normal_mode_preserves_deliberate_wait(self) -> None:
        state = {"game_mode_variant": {"id": "standard"}}
        self.assertEqual(decision_delay(state, 0.5, 30.0), 30.0)

    def test_rts_preflight_skips_busy_missing_and_duplicate_queue_orders(self) -> None:
        state = {
            "game_mode_variant": {"id": "stockpile_rts_pvp"},
            "offensive_production": {
                "factories": [{"existing_instances": [
                    {"x": 10, "y": 20, "available_for_new_order": False},
                    {"x": 11, "y": 20, "available_for_new_order": True},
                ]}],
                "reconstructors": [{"existing_instances": [
                    {"x": 15, "y": 20, "available_for_new_order": True},
                ]}],
            },
            "rts_squads": [{
                "squad_id": "center", "mode": "defend", "member_count": 3,
                "target": {"x": 81, "y": 125}, "engagement_radius": 14,
            }],
        }
        actions = [
            {"type": "train_units", "x": 10, "y": 20, "unit": "dagger", "count": 2},
            {"type": "train_units", "x": 11, "y": 20, "unit": "dagger", "count": 2},
            {"type": "train_units", "x": 11, "y": 20, "unit": "nova", "count": 1},
            {"type": "upgrade_units", "x": 99, "y": 99, "from_unit": "dagger", "to_unit": "mace", "count": 1},
            {"type": "upgrade_units", "x": 15, "y": 20, "from_unit": "dagger", "to_unit": "mace", "count": 1},
            {"type": "command_units", "unit": "all", "mode": "retreat", "max_units": 20},
            {
                "type": "command_units", "unit": "all", "squad_id": "center",
                "mode": "defend", "target_x": 81, "target_y": 125,
                "target_radius": 2, "engagement_radius": 14, "max_units": 3,
            },
        ]
        executable, skipped = preflight_rts_queue_actions(state, actions)
        self.assertEqual([action["type"] for action in executable], [
            "train_units", "upgrade_units", "command_units",
        ])
        self.assertEqual([item["reason"] for item in skipped], [
            "factory_queue_busy_in_observed_state",
            "duplicate_factory_order_in_same_decision",
            "reconstructor_not_present_in_observed_state",
            "standing_squad_order_already_active",
        ])


if __name__ == "__main__":
    unittest.main()
