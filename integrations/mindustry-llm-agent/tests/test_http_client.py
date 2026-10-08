import unittest
from unittest.mock import patch

from mindustry_agent.http_client import HttpJsonError, MindustryClient


class MindustryClientTest(unittest.TestCase):
    @patch("mindustry_agent.http_client.time.sleep")
    @patch("mindustry_agent.http_client.request_json")
    def test_state_retries_transient_server_timeout(self, request_json, sleep) -> None:
        request_json.side_effect = [
            HttpJsonError("temporary", status_code=503),
            {"playing": True},
        ]
        client = MindustryClient("http://127.0.0.1:8765", "token", "crux")
        self.assertEqual(client.state(), {"playing": True})
        self.assertEqual(request_json.call_count, 2)
        sleep.assert_called_once_with(0.35)

    @patch("mindustry_agent.http_client.time.sleep")
    @patch("mindustry_agent.http_client.request_json")
    def test_state_does_not_retry_non_transient_error(self, request_json, sleep) -> None:
        request_json.side_effect = HttpJsonError("missing", status_code=404)
        client = MindustryClient("http://127.0.0.1:8765", "token", "sharded")
        with self.assertRaises(HttpJsonError):
            client.state()
        self.assertEqual(request_json.call_count, 1)
        sleep.assert_not_called()

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
