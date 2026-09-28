import json
import tempfile
import unittest
from pathlib import Path

from mindustry_agent.report import summarize


class ReportTest(unittest.TestCase):
    def test_uses_authoritative_run_end_state_for_result(self) -> None:
        records = [
            {
                "kind": "turn", "llm_latency_seconds": 2.0,
                "state": {"wave": None, "result": "running"},
                "action_response": {"results": [{"ok": True}]},
            },
            {
                "kind": "run_end", "reason": "game_over_during_inference",
                "state": {
                    "result": "won", "game_over": True,
                    "previous_episode_result": {"winner": "sharded"},
                },
            },
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "run.jsonl"
            path.write_text(
                "".join(json.dumps(record) + "\n" for record in records),
                encoding="utf-8",
            )
            summary = summarize(path)
        self.assertEqual(summary["last_result"], "won")
        self.assertEqual(summary["winner"], "sharded")
        self.assertEqual(summary["end_reason"], "game_over_during_inference")


if __name__ == "__main__":
    unittest.main()
