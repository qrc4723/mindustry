from __future__ import annotations

import argparse
import json
from pathlib import Path
from statistics import mean
from typing import Any


def summarize(path: Path) -> dict[str, Any]:
    turns: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            record = json.loads(line)
            if record.get("kind") == "turn":
                turns.append(record)
    waves = [int(turn["state"].get("wave", 0)) for turn in turns]
    latencies = [float(turn.get("llm_latency_seconds", 0)) for turn in turns]
    results = [
        item
        for turn in turns
        for item in turn.get("action_response", {}).get("results", [])
    ]
    successes = sum(1 for item in results if item.get("ok"))
    return {
        "path": str(path),
        "turns": len(turns),
        "max_observed_wave": max(waves, default=0),
        "mean_llm_latency_seconds": mean(latencies) if latencies else 0,
        "actions": len(results),
        "successful_actions": successes,
        "action_success_rate": successes / len(results) if results else 0,
        "last_result": turns[-1]["state"].get("result") if turns else "unknown",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Summarize one Mindustry JSONL run.")
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    print(json.dumps(summarize(args.path), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
