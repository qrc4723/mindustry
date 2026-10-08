import json
import unittest
from unittest.mock import patch

from mindustry_agent.llm import OpenAICompatibleLlm, system_prompt_for_state


def response(content: str, prompt_tokens: int = 1, completion_tokens: int = 1) -> dict:
    return {
        "choices": [{"message": {"content": content}}],
        "usage": {
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "total_tokens": prompt_tokens + completion_tokens,
        },
    }


class LlmTest(unittest.TestCase):
    def llm(self) -> OpenAICompatibleLlm:
        return OpenAICompatibleLlm(
            base_url="http://localhost:11434/v1",
            api_key="test",
            model="test-model",
            timeout_seconds=5,
            max_tokens=100,
            reasoning_effort="none",
            json_mode=True,
        )

    @patch("mindustry_agent.llm.request_json")
    def test_repairs_malformed_json_and_counts_total_usage(self, request_json) -> None:
        request_json.side_effect = [
            response('{"reasoning_summary":"broken",', 10, 4),
            response('{"reasoning_summary":"fixed","actions":[],"wait_seconds":1}', 5, 3),
        ]
        result = self.llm().decide({"rules": {"pvp": True}}, [])
        self.assertTrue(result.repair_attempted)
        self.assertEqual(result.decision["reasoning_summary"], "fixed")
        self.assertEqual(result.usage["total_tokens"], 22)
        self.assertEqual(request_json.call_count, 2)
        repair_system = request_json.call_args_list[1].kwargs["payload"]["messages"][0]["content"]
        self.assertIn("exact validation failure", repair_system)
        self.assertIn("clear_item_input_network", repair_system)
        self.assertIn("train_units", repair_system)
        self.assertIn("upgrade_units", repair_system)

    @patch("mindustry_agent.llm.request_json")
    def test_valid_json_does_not_trigger_repair(self, request_json) -> None:
        request_json.return_value = response(
            '{"reasoning_summary":"ok","actions":[],"wait_seconds":1}', 7, 2
        )
        result = self.llm().decide({}, [])
        self.assertFalse(result.repair_attempted)
        self.assertEqual(request_json.call_count, 1)

    @patch("mindustry_agent.llm.request_json")
    def test_keeps_valid_actions_when_repaired_json_has_one_invalid_action(self, request_json) -> None:
        request_json.side_effect = [
            response('{"actions":', 4, 2),
            response(json.dumps({
                "reasoning_summary": "keep the valid order",
                "actions": [
                    {"type": "command_core_unit", "mode": "defend_core"},
                    {
                        "type": "resupply_turrets", "ammo": "copper",
                        "max_items": 20, "reserve_copper": "enough",
                        "below_fraction": 0.5,
                    },
                ],
                "wait_seconds": 0.5,
            }), 6, 3),
        ]

        result = self.llm().decide({}, [])

        self.assertEqual(result.decision["actions"], [
            {"type": "command_core_unit", "mode": "defend_core"}
        ])
        self.assertEqual(len(result.validation_skips), 1)
        self.assertEqual(result.validation_skips[0]["original_index"], 1)
        self.assertIn("reserve_copper must be a number", result.validation_skips[0]["message"])

    def test_pvp_uses_dedicated_offensive_prompt(self) -> None:
        prompt = system_prompt_for_state({"rules": {"pvp": True, "waves": False}})
        self.assertIn("destroy every opposing core", prompt)
        self.assertIn("command_units", prompt)
        self.assertIn("Static fortification", prompt)
        self.assertIn("wireless link endpoint, not a cable tile", prompt)
        self.assertIn("transport_components", prompt)
        self.assertIn("persistent and asynchronous", prompt)
        self.assertIn("clear_item_input_network", prompt)
        self.assertNotIn("wave_forecast", prompt)
        self.assertNotIn("scheduled enemy waves", prompt)

    def test_survival_uses_dedicated_wave_prompt(self) -> None:
        prompt = system_prompt_for_state({"rules": {"pvp": False, "waves": True}})
        self.assertIn("maximize survival against scheduled enemy waves", prompt)
        self.assertIn("wave_forecast", prompt)
        self.assertNotIn("destroy every opposing core", prompt)
        self.assertNotIn("Static fortification", prompt)

    def test_stockpile_rts_uses_unit_strategy_prompt(self) -> None:
        prompt = system_prompt_for_state({
            "rules": {"pvp": True, "waves": False},
            "game_mode_variant": {"id": "stockpile_rts_pvp"},
        })
        self.assertIn("strategic real-time", prompt)
        self.assertIn("rts_control_points", prompt)
        self.assertIn("never directly end the match", prompt)
        self.assertIn("provide no resource income", prompt)
        self.assertIn("10% faster unit training", prompt)
        self.assertIn("pause while that owned point is contested", prompt)
        self.assertIn("up to 1.5x", prompt)
        self.assertIn("rts_control_benefits", prompt)
        self.assertIn("objective-side cover", prompt)
        self.assertIn("rts_battlefield", prompt)
        self.assertIn("three-lane showcase", prompt)
        self.assertIn("persistent squad_id", prompt)
        self.assertIn("recent_combat_losses", prompt)
        self.assertIn("recent_unit_command_receipts", prompt)
        self.assertIn("distance progress", prompt)
        self.assertIn("There are deliberately no mineable resources", prompt)
        self.assertIn("12,000 of every non-hidden item", prompt)
        self.assertIn("should not be delayed merely to conserve resources", prompt)
        self.assertIn("train_units", prompt)
        self.assertIn("pending_training_units", prompt)
        self.assertIn("ready_for_new_order", prompt)
        self.assertIn("upgrade_units", prompt)
        self.assertIn("rts_training_queues", prompt)
        self.assertIn("rts_upgrade_queues", prompt)
        self.assertIn("available_for_new_order", prompt)
        self.assertIn("maximum_startable_now_from_resources_and_units", prompt)
        self.assertIn("rts_construction_queue", prompt)
        self.assertIn("A later action in the same response does not wait for queued", prompt)
        self.assertIn("Observe the completed building in a later state", prompt)
        self.assertIn("refreshes state 0.5 seconds", prompt)
        self.assertIn("unit_catalog", prompt)
        self.assertIn("rts_production_placement_options", prompt)
        self.assertIn("rts_defense.structure_catalog", prompt)
        self.assertIn("valid anchors on or near the current ground route", prompt)
        self.assertIn("resupply_turrets", prompt)
        self.assertIn("not a required defense ratio", prompt)
        self.assertIn("rather than always taking the nearest row", prompt)
        self.assertIn("Write every human-readable value in Korean", prompt)
        self.assertIn("identifiers exactly as", prompt)
        self.assertIn("Use receding-horizon control", prompt)
        self.assertIn("not a multi-stage roadmap", prompt)
        self.assertIn("The action limit is not a target", prompt)
        self.assertIn("actions array supports multiple independent actions", prompt)
        self.assertIn("No-action turns should be exceptional", prompt)
        self.assertIn("newly observed living unit_ids explicitly", prompt)
        self.assertIn("do not fill the construction queue", prompt)
        self.assertIn("placement_option_id", prompt)
        self.assertIn("facility_id", prompt)
        self.assertNotIn("Conveyor rotation is", prompt)
        self.assertNotIn("Maintain a live causal path from economy to victory", prompt)

    @patch("mindustry_agent.llm.request_json")
    def test_decide_sends_prompt_selected_from_current_mode(self, request_json) -> None:
        request_json.return_value = response(
            '{"reasoning_summary":"ok","actions":[],"wait_seconds":1}'
        )
        self.llm().decide({"rules": {"pvp": True}}, [])
        payload = request_json.call_args.kwargs["payload"]
        system_prompt = payload["messages"][0]["content"]
        self.assertIn("real-time Mindustry PvP match", system_prompt)
        self.assertNotIn("wave_forecast", system_prompt)

    @patch("mindustry_agent.llm.request_json")
    def test_only_latest_route_geometry_is_kept_in_prompt(self, request_json) -> None:
        request_json.return_value = response(
            '{"reasoning_summary":"ok","actions":[],"wait_seconds":1}'
        )
        outcomes = [
            {"turn": turn, "evidence_results": [{"local_path_map": f"map-{turn}"}]}
            for turn in range(4)
        ]
        self.llm().decide({"rules": {"pvp": True}}, outcomes)
        payload = request_json.call_args.kwargs["payload"]
        user_payload = json.loads(payload["messages"][1]["content"])
        recent = user_payload["recent_action_results"]
        self.assertTrue(all("evidence_results" not in row for row in recent[:-1]))
        self.assertEqual(recent[-1]["evidence_results"][0]["local_path_map"], "map-3")


if __name__ == "__main__":
    unittest.main()
