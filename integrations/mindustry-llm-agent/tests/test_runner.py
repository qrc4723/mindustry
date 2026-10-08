import unittest

from mindustry_agent.runner import (
    decision_delay,
    preflight_rts_queue_actions,
    prepare_decision_actions,
)


class RunnerTimingTest(unittest.TestCase):
    def test_preflight_resolution_is_applied_when_nothing_is_skipped(self) -> None:
        state = {
            "game_mode_variant": {"id": "stockpile_rts_pvp"},
            "rts_production_placement_options": {
                "columns": ["placement_option_id", "x", "y", "compatible_structures"],
                "rows": [["production:0", 20, 30, ["ground-factory"]]],
            },
            "offensive_production": {"factories": [], "reconstructors": []},
            "rts_squads": [],
            "friendly_units": [],
        }
        original = {
            "actions": [{
                "type": "place", "block": "ground-factory",
                "placement_option_id": "production:0", "rotation": 0,
            }],
            "reasoning_summary": "build",
        }
        prepared, requested, skipped = prepare_decision_actions(state, original)
        self.assertEqual(requested, original["actions"])
        self.assertEqual(skipped, [])
        self.assertEqual((prepared["actions"][0]["x"], prepared["actions"][0]["y"]), (20, 30))
        self.assertNotIn("placement_option_id", prepared["actions"][0])

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
            "friendly_units": [
                {"id": 101, "type": "dagger", "commandable": True},
            ],
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

    def test_rts_preflight_resolves_observed_references_and_skips_empty_commands(self) -> None:
        state = {
            "game_mode_variant": {"id": "stockpile_rts_pvp"},
            "rts_production_placement_options": {
                "columns": [
                    "placement_option_id", "x", "y", "distance_tiles",
                    "distance_band", "direction_sector", "compatible_structures",
                ],
                "rows": [["production:0", 20, 30, 12.0, 0, 1, ["ground-factory"]]],
            },
            "offensive_production": {
                "factories": [{"existing_instances": [
                    {
                        "facility_id": "factory:10:20", "x": 10, "y": 20,
                        "available_for_new_order": True,
                    },
                ]}],
                "reconstructors": [{"existing_instances": [
                    {
                        "facility_id": "reconstructor:14:20", "x": 14, "y": 20,
                        "available_for_new_order": True,
                    },
                ]}],
            },
            "rts_squads": [],
            "friendly_units": [],
        }
        actions = [
            {
                "type": "place", "block": "ground-factory",
                "placement_option_id": "production:0", "rotation": 0,
            },
            {
                "type": "train_units", "facility_id": "factory:10:20",
                "unit": "dagger", "count": 2,
            },
            {
                "type": "upgrade_units", "facility_id": "reconstructor:14:20",
                "from_unit": "dagger", "to_unit": "mace", "count": 1,
            },
            {
                "type": "command_units", "unit": "dagger", "mode": "attack_move",
                "target_x": 80, "target_y": 40, "max_units": 4,
            },
        ]
        executable, skipped = preflight_rts_queue_actions(state, actions)
        self.assertEqual(executable[0]["x"], 20)
        self.assertEqual(executable[0]["y"], 30)
        self.assertNotIn("placement_option_id", executable[0])
        self.assertEqual((executable[1]["x"], executable[1]["y"]), (10, 20))
        self.assertEqual((executable[2]["x"], executable[2]["y"]), (14, 20))
        self.assertEqual(
            [item["reason"] for item in skipped],
            ["no_matching_commandable_units_in_observed_state"],
        )

    def test_rts_preflight_preserves_matching_training_rally_and_rejects_conflict(self) -> None:
        state = {
            "game_mode_variant": {"id": "stockpile_rts_pvp"},
            "offensive_production": {"factories": [{"existing_instances": [{
                "x": 10, "y": 20, "available_for_new_order": True,
            }]}], "reconstructors": []},
            "rts_squads": [{
                "squad_id": "alpha", "mode": "rally", "member_count": 2,
                "target": {"x": 30, "y": 40},
            }],
            "friendly_units": [],
        }
        actions = [
            {
                "type": "train_units", "x": 10, "y": 20, "unit": "dagger", "count": 2,
                "rally_x": 30, "rally_y": 40, "rally_radius": 4, "squad_id": "alpha",
            },
            {
                "type": "train_units", "x": 10, "y": 20, "unit": "dagger", "count": 2,
                "rally_x": 31, "rally_y": 40, "rally_radius": 4, "squad_id": "alpha",
            },
        ]
        executable, skipped = preflight_rts_queue_actions(state, actions)
        self.assertEqual(executable, [actions[0]])
        self.assertEqual(skipped[0]["reason"], "training_squad_not_rally_compatible")


if __name__ == "__main__":
    unittest.main()
