from __future__ import annotations

import argparse
from pathlib import Path

from .config import AgentConfig


def reflect_log(path: Path, config: AgentConfig) -> Path:
    raise RuntimeError(
        "Log reflection memory is disabled: run logs remain audit records and are not converted into agent memory."
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Log-to-memory reflection is disabled.")
    parser.add_argument("path", type=Path)
    args = parser.parse_args()
    reflect_log(args.path, AgentConfig.from_env())


if __name__ == "__main__":
    main()
