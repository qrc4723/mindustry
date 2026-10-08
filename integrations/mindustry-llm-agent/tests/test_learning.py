import unittest

from mindustry_agent.learning import (
    ActionFailureTracker, InfrastructureBacklog, StateEventTracker,
    compact_action_outcome, defense_outcomes_for_prompt, events_for_prompt,
)


class LearningTest(unittest.TestCase):
    def test_tracks_repeated_failures_and_clears_on_success(self) -> None:
        tracker = ActionFailureTracker()
        action = {"type": "route_items", "source_x": 4, "source_y": 5, "target_x": 8, "target_y": 9, "transport": "conveyor"}
        failure = {"index": 0, "ok": False, "error": "plan_became_invalid", "message": "blocked", "diagnostics": {"x": 6, "y": 5}}
        tracker.update([action], [failure], turn=2, wave=3)
        tracker.update([action], [failure], turn=3, wave=3)
        prompt = tracker.for_prompt()
        self.assertEqual(prompt["most_repeated"][0]["total_failures"], 2)
        self.assertEqual(prompt["most_repeated"][0]["consecutive_turns"], 2)
        self.assertEqual(prompt["most_repeated"][0]["diagnostics"]["x"], 6)
        tracker.update([action], [{"index": 0, "ok": True, "type": "route_items"}], turn=4, wave=3)
        self.assertEqual(tracker.for_prompt()["most_repeated"], [])

    def test_tracks_successful_requests_that_changed_nothing(self) -> None:
        tracker = ActionFailureTracker()
        resupply = {
            "type": "resupply_turrets", "ammo": "copper", "max_items": 30,
            "reserve_copper": 40, "below_fraction": 0.5,
        }
        tracker.update([resupply], [{
            "index": 0, "ok": True, "type": "resupply_turrets", "items_transferred": 0,
        }], turn=1, wave=2)
        repeated = tracker.for_prompt()["most_repeated"][0]
        self.assertEqual(repeated["error"], "no_effect")
        self.assertIn("no turret", repeated["message"])

    def test_tracks_preflight_skips_as_within_episode_feedback(self) -> None:
        tracker = ActionFailureTracker()
        action = {
            "type": "command_units", "unit": "dagger", "squad_id": "alpha",
            "mode": "attack_move", "target_x": 84, "target_y": 40,
        }
        tracker.update_preflight([{
            "action": action,
            "reason": "standing_squad_order_already_active",
            "message": "persistent order already active",
            "phase": "immediately_before_execution",
        }], turn=4, wave=0)
        repeated = tracker.for_prompt()["most_repeated"][0]
        self.assertEqual(repeated["error"], "standing_squad_order_already_active")
        self.assertEqual(repeated["diagnostics"]["phase"], "immediately_before_execution")

    def test_compacts_large_action_outcome(self) -> None:
        actions = [{"type": "place", "block": "conveyor", "x": x, "y": 5, "rotation": 2} for x in range(32)]
        results = [{"index": x, "ok": False, "error": "invalid_placement", "message": "occupied"} for x in range(32)]
        compact = compact_action_outcome(4, {"actions": actions, "strategic_intent": {"objective": "route graphite"}}, {"results": results}, 7)
        self.assertEqual(compact["failure_count"], 32)
        self.assertEqual(len(compact["failure_examples"]), 8)
        self.assertNotIn("actions", compact)

    def test_preserves_route_inspection_and_preview_evidence_for_next_turn(self) -> None:
        actions = [
            {"type": "inspect_item_route"},
            {"type": "preview_conveyor_path"},
        ]
        response = {"results": [
            {
                "index": 0, "ok": True, "type": "inspect_item_route",
                "local_path_map": {"bounds": {"min_x": 1}, "tile_exceptions": []},
            },
            {
                "index": 1, "ok": True, "type": "preview_conveyor_path",
                "preflight": {"possible_input_items": ["coal"]},
                "preview_token": "token-1",
            },
        ]}
        compact = compact_action_outcome(2, {"actions": actions}, response, 4)
        self.assertEqual(compact["evidence_results"][0]["type"], "inspect_item_route")
        route_map = compact["evidence_results"][0]["local_path_map"]
        self.assertIn("exception_table", route_map)
        self.assertNotIn("tile_exceptions", route_map)
        self.assertEqual(compact["evidence_results"][1]["preview_token"], "token-1")

    def test_detects_destroyed_building_and_core_damage(self) -> None:
        tracker = StateEventTracker()
        tracker.observe({
            "wave": 2,
            "core": {"health": 1000, "items": {"copper": 50}},
            "core_defender": {"task": "mine", "task_resource": "copper", "carried_amount": 3, "carried_item": "copper"},
            "buildings": [{"block": "duo", "x": 4, "y": 5, "health": 250, "ammo_fraction": 1.0}],
        })
        events = tracker.observe({
            "wave": 3,
            "core": {"health": 800, "items": {"copper": 45}},
            "core_defender": {"task": "defend_core", "carried_amount": 0},
            "buildings": [],
        })
        event_types = {event["type"] for event in events}
        self.assertIn("building_lost", event_types)
        self.assertIn("core_damaged", event_types)
        self.assertIn("wave_outcome", event_types)
        self.assertIn("wave_changed", event_types)
        self.assertIn("core_unit_task_changed", event_types)
        self.assertIn("core_unit_cargo_changed", event_types)

    def test_retains_losses_until_replacement_is_observed(self) -> None:
        backlog = InfrastructureBacklog()
        backlog.update([{
            "type": "building_lost", "block": "mechanical-drill", "x": 7, "y": 8,
            "rotation": 1, "previous_health": 20,
        }], wave=4)
        self.assertEqual(backlog.for_prompt()["unresolved_loss_count"], 1)
        backlog.update([{"type": "building_added", "block": "pneumatic-drill", "x": 7, "y": 8}], wave=5)
        self.assertEqual(backlog.for_prompt()["unresolved_loss_count"], 0)

    def test_critical_events_survive_recent_event_compaction(self) -> None:
        history = [{"type": "building_lost", "block": "duo", "x": 1, "y": 2}]
        history.extend({"type": "core_item_changed", "item": "copper", "delta": 1} for _ in range(50))
        selected = events_for_prompt(history)
        self.assertEqual(selected[0]["type"], "building_lost")

    def test_summarizes_only_observed_episode_defense_outcomes(self) -> None:
        summary = defense_outcomes_for_prompt([
            {"type": "building_lost", "block": "duo", "wave": 4},
            {"type": "building_damaged", "block": "copper-wall", "wave": 4},
            {"type": "core_damaged", "damage": 125, "wave": 4,
             "nearest_enemy": {"direction_from_core": "1:1"}},
            {"type": "turret_ammo_consumed"},
        ])
        self.assertEqual(summary["building_losses_by_block"], {"duo": 1})
        self.assertEqual(summary["observed_core_damage"], 125)
        self.assertIn("this episode only", summary["meaning"])
        self.assertEqual(summary["outcomes_by_wave"][0]["core_damage"], 125)
        self.assertEqual(summary["outcomes_by_wave"][0]["observed_attack_directions"], {"1:1": 1})

    def test_detects_production_stall_change(self) -> None:
        tracker = StateEventTracker()
        tracker.observe({
            "buildings": [{
                "block": "graphite-press", "x": 4, "y": 5, "health": 300,
                "stall_reason": "noInput", "consumption_status": [],
            }],
        })
        events = tracker.observe({
            "buildings": [{
                "block": "graphite-press", "x": 4, "y": 5, "health": 300,
                "stall_reason": "none", "consumption_status": [{"satisfied": True}],
            }],
        })
        changed = next(event for event in events if event["type"] == "production_status_changed")
        self.assertEqual(changed["from"], "noInput")
        self.assertEqual(changed["to"], "none")


if __name__ == "__main__":
    unittest.main()
