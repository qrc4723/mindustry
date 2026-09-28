from __future__ import annotations

import argparse
import json
from pathlib import Path
from statistics import mean
from typing import Any


def summarize(path: Path) -> dict[str, Any]:
    turns: list[dict[str, Any]] = []
    end_record: dict[str, Any] | None = None
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            record = json.loads(line)
            if record.get("kind") == "turn":
                turns.append(record)
            elif record.get("kind") == "run_end":
                end_record = record
    waves = [int(turn["state"].get("wave") or 0) for turn in turns]
    latencies = [float(turn.get("llm_latency_seconds", 0)) for turn in turns]
    results = [
        item
        for turn in turns
        for item in turn.get("action_response", {}).get("results", [])
    ]
    successes = sum(1 for item in results if item.get("ok"))
    final_state = end_record.get("state", {}) if end_record else {}
    if not isinstance(final_state, dict):
        final_state = {}
    previous = final_state.get("previous_episode_result", {})
    if not isinstance(previous, dict):
        previous = {}
    last_result = final_state.get("result")
    if not last_result:
        last_result = turns[-1]["state"].get("result") if turns else "unknown"
    return {
        "path": str(path),
        "turns": len(turns),
        "max_observed_wave": max(waves, default=0),
        "mean_llm_latency_seconds": mean(latencies) if latencies else 0,
        "actions": len(results),
        "successful_actions": successes,
        "action_success_rate": successes / len(results) if results else 0,
        "last_result": last_result,
        "winner": previous.get("winner") or final_state.get("winner"),
        "end_reason": end_record.get("reason") if end_record else None,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Summarize one Mindustry JSONL run.")
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    print(json.dumps(summarize(args.path), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
