import unittest
from unittest.mock import patch

from mindustry_agent.http_client import MindustryClient


class MindustryClientTest(unittest.TestCase):
    @patch("mindustry_agent.http_client.request_json")
    def test_action_request_carries_optional_spectator_telemetry(self, request_json) -> None:
        request_json.return_value = {"ok": True, "results": []}
        client = MindustryClient("http://127.0.0.1:8765", "token", "sharded")
        telemetry = {
            "model": "glm-5.3-flash:cloud",
            "turn": 7,
            "latency_seconds": 4.2,
            "strategy": "pressure the enemy core",
        }
        client.act(
            "request-1", [], expected_episode_id="episode-1", observed_tick=100.0,
            agent_telemetry=telemetry,
        )
        payload = request_json.call_args.kwargs["payload"]
        self.assertEqual(payload["agent_telemetry"], telemetry)
        self.assertEqual(payload["actions"], [])


if __name__ == "__main__":
    unittest.main()
