from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path


def _boolean(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() not in {"0", "false", "no", "off"}


@dataclass(frozen=True)
class AgentConfig:
    game_url: str
    game_token: str
    game_team: str
    llm_base_url: str
    llm_api_key: str
    llm_model: str
    llm_timeout_seconds: float
    llm_max_tokens: int
    llm_reasoning_effort: str
    decision_interval_seconds: float
    json_mode: bool
    run_dir: Path

    @classmethod
    def from_env(cls) -> "AgentConfig":
        return cls(
            game_url=os.getenv("MINDUSTRY_API_URL", "http://127.0.0.1:8765").rstrip("/"),
            game_token=os.getenv("MINDUSTRY_AGENT_TOKEN", "").strip(),
            game_team=os.getenv("MINDUSTRY_TEAM", "sharded").strip(),
            llm_base_url=os.getenv("LLM_BASE_URL", "http://127.0.0.1:11434/v1").rstrip("/"),
            llm_api_key=os.getenv("LLM_API_KEY", "ollama").strip(),
            llm_model=os.getenv("LLM_MODEL", "deepseek-v4-flash:cloud").strip(),
            llm_timeout_seconds=float(os.getenv("LLM_TIMEOUT_SECONDS", "180")),
            llm_max_tokens=int(os.getenv("LLM_MAX_TOKENS", "2400")),
            llm_reasoning_effort=os.getenv("LLM_REASONING_EFFORT", "none").strip(),
            decision_interval_seconds=float(os.getenv("DECISION_INTERVAL_SECONDS", "0.5")),
            json_mode=_boolean("LLM_JSON_MODE", True),
            run_dir=Path(os.getenv("RUN_DIR", "runs")),
        )
