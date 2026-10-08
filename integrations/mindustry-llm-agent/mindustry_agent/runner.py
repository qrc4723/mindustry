from __future__ import annotations

import argparse
from dataclasses import replace
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import time
from typing import Any
from uuid import uuid4

from .config import AgentConfig
from .http_client import HttpJsonError, MindustryClient
from .learning import (
    ActionFailureTracker, InfrastructureBacklog, StateEventTracker,
    compact_action_outcome, defense_outcomes_for_prompt, events_for_prompt,
)
from .llm import OpenAICompatibleLlm
from .policy import compact_state


STOCKPILE_RTS_REFRESH_SECONDS = 0.5


def decision_delay(state: dict[str, Any], configured_interval: float, requested_wait: float) -> float:
    variant = state.get("game_mode_variant") if isinstance(state.get("game_mode_variant"), dict) else {}
    if variant.get("id") == "stockpile_rts_pvp":
        return STOCKPILE_RTS_REFRESH_SECONDS
    return max(configured_interval, requested_wait)


def preflight_rts_queue_actions(
    state: dict[str, Any], actions: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Skip queue commands that cannot start and unchanged persistent orders.

    This is feasibility filtering only: it does not choose a factory, unit, count,
    or strategy for the model.  The first valid command per free slot is preserved.
    """
    variant = state.get("game_mode_variant") if isinstance(state.get("game_mode_variant"), dict) else {}
    if variant.get("id") != "stockpile_rts_pvp":
        return list(actions), []

    production = state.get("offensive_production")
    if not isinstance(production, dict):
        return list(actions), []

    factory_slots: dict[tuple[int, int], bool] = {}
    factory_plans: dict[tuple[int, int], dict[str, int]] = {}
    for factory in production.get("factories", []):
        if not isinstance(factory, dict):
            continue
        supported_plans: dict[str, int] = {}
        for plan in factory.get("plans", []):
            if not isinstance(plan, dict) or not isinstance(plan.get("unit"), str):
                continue
            supported_plans[str(plan["unit"])] = int(
                plan.get("maximum_trainable_from_current_stockpile", 0) or 0
            )
        for instance in factory.get("existing_instances", []):
            if not isinstance(instance, dict):
                continue
            x, y = instance.get("x"), instance.get("y")
            if isinstance(x, int) and isinstance(y, int):
                factory_slots[(x, y)] = bool(instance.get("available_for_new_order"))
                factory_plans[(x, y)] = supported_plans

    reconstructor_slots: dict[tuple[int, int], bool] = {}
    reconstructor_upgrades: dict[tuple[int, int], dict[tuple[str, str], int]] = {}
    for reconstructor in production.get("reconstructors", []):
        if not isinstance(reconstructor, dict):
            continue
        supported_upgrades: dict[tuple[str, str], int] = {}
        for upgrade in reconstructor.get("upgrades", []):
            if not isinstance(upgrade, dict):
                continue
            from_unit, to_unit = upgrade.get("from_unit"), upgrade.get("to_unit")
            if not isinstance(from_unit, str) or not isinstance(to_unit, str):
                continue
            supported_upgrades[(from_unit, to_unit)] = int(
                upgrade.get("maximum_startable_now_from_resources_and_units", 0) or 0
            )
        for instance in reconstructor.get("existing_instances", []):
            if not isinstance(instance, dict):
                continue
            x, y = instance.get("x"), instance.get("y")
            if isinstance(x, int) and isinstance(y, int):
                reconstructor_slots[(x, y)] = bool(instance.get("available_for_new_order"))
                reconstructor_upgrades[(x, y)] = supported_upgrades

    placement_options: dict[str, dict[str, Any]] = {}
    raw_placement = state.get("rts_production_placement_options")
    if isinstance(raw_placement, dict):
        columns = raw_placement.get("columns")
        if isinstance(columns, list):
            for row in raw_placement.get("rows", []):
                if not isinstance(row, list) or len(row) != len(columns):
                    continue
                option = dict(zip(columns, row))
                option_id = option.get("placement_option_id")
                if isinstance(option_id, str):
                    placement_options[option_id] = option

    factory_references = {
        f"factory:{x}:{y}": (x, y) for x, y in factory_slots
    }
    reconstructor_references = {
        f"reconstructor:{x}:{y}": (x, y) for x, y in reconstructor_slots
    }
    commandable_units = [
        unit for unit in state.get("friendly_units", [])
        if isinstance(unit, dict) and unit.get("commandable") and isinstance(unit.get("id"), int)
    ]
    defender = state.get("core_defender") if isinstance(state.get("core_defender"), dict) else {}
    defender_id = defender.get("id") if defender.get("active") else None
    commandable_counts: dict[str, int] = {}
    commandable_ids: set[int] = set()
    for unit in commandable_units:
        if unit.get("id") == defender_id:
            continue
        commandable_ids.add(int(unit["id"]))
        unit_type = str(unit.get("type", ""))
        commandable_counts[unit_type] = commandable_counts.get(unit_type, 0) + 1

    executable: list[dict[str, Any]] = []
    skipped: list[dict[str, Any]] = []
    claimed_factories: set[tuple[int, int]] = set()
    claimed_reconstructors: set[tuple[int, int]] = set()
    squads = {
        str(squad.get("squad_id")): squad
        for squad in state.get("rts_squads", [])
        if isinstance(squad, dict) and squad.get("squad_id")
    }
    for index, requested_action in enumerate(actions):
        action = dict(requested_action)
        action_type = action.get("type")
        if action_type == "place" and action.get("placement_option_id") is not None:
            option_id = str(action.get("placement_option_id"))
            option = placement_options.get(option_id)
            compatible = option.get("compatible_structures", []) if option else []
            if option is None or action.get("block") not in compatible:
                skipped.append({
                    "original_index": index,
                    "action": requested_action,
                    "reason": "invalid_or_incompatible_placement_option_id",
                    "message": (
                        "Skipped because placement_option_id is not a currently observed option compatible "
                        "with the selected block. Re-observe and choose an ID from the latest state."
                    ),
                })
                continue
            action["x"], action["y"] = option.get("x"), option.get("y")
            action.pop("placement_option_id", None)
        if action_type in {"train_units", "upgrade_units"} and action.get("facility_id") is not None:
            references = factory_references if action_type == "train_units" else reconstructor_references
            coordinate = references.get(str(action.get("facility_id")))
            if coordinate is None:
                skipped.append({
                    "original_index": index,
                    "action": requested_action,
                    "reason": "facility_reference_not_present_in_observed_state",
                    "message": (
                        "Skipped because facility_id is not an existing observed facility of the required type. "
                        "Re-observe and select a current facility_id."
                    ),
                })
                continue
            action["x"], action["y"] = coordinate
            action.pop("facility_id", None)
        if action_type == "command_units" and action.get("squad_id") in squads and not action.get("unit_ids"):
            squad = squads[str(action["squad_id"])]
            same_target = (
                action.get("mode") in {"retreat", "stop"}
                or (
                    action.get("mode") == "attack"
                    and action.get("target_unit_id") is not None
                    and action.get("target_unit_id") == squad.get("target_unit_id")
                )
                or (
                    action.get("target_x") == (squad.get("target") or {}).get("x")
                    and action.get("target_y") == (squad.get("target") or {}).get("y")
                )
            )
            same_order = (
                (
                    int(squad.get("member_count", 0) or 0) > 0
                    or int(squad.get("pending_training_units", 0) or 0) > 0
                )
                and action.get("mode") == squad.get("mode")
                and same_target
                and int(action.get("engagement_radius", 14))
                    == int(squad.get("engagement_radius", 14) or 14)
            )
            if same_order:
                skipped.append({
                    "original_index": index,
                    "action": action,
                    "reason": "standing_squad_order_already_active",
                    "message": (
                        "Skipped because this active or producing squad already has the identical persistent order. "
                        "The engine continues it without reissuing; send another command only to change intent "
                        "or provide explicit unit_ids to replace/reinforce membership."
                    ),
                })
                continue
        if action_type == "command_units":
            existing_squad = squads.get(str(action.get("squad_id", "")))
            existing_members = int((existing_squad or {}).get("member_count", 0) or 0)
            pending_members = int((existing_squad or {}).get("pending_training_units", 0) or 0)
            explicit_ids = action.get("unit_ids")
            if isinstance(explicit_ids, list) and explicit_ids:
                has_selectable_units = any(unit_id in commandable_ids for unit_id in explicit_ids)
            elif existing_squad is not None:
                has_selectable_units = existing_members > 0 or pending_members > 0
            else:
                requested_type = str(action.get("unit", ""))
                has_selectable_units = (
                    sum(commandable_counts.values()) > 0
                    if requested_type == "all"
                    else commandable_counts.get(requested_type, 0) > 0
                )
            if not has_selectable_units:
                skipped.append({
                    "original_index": index,
                    "action": requested_action,
                    "reason": "no_matching_commandable_units_in_observed_state",
                    "message": (
                        "Skipped because no produced commandable unit in the observed state matches this order. "
                        "A named squad with pending training can receive a standing order; otherwise wait until "
                        "production completes or choose living unit IDs."
                    ),
                })
                continue
        if action_type not in {"train_units", "upgrade_units"}:
            executable.append(action)
            continue
        coordinate = (action.get("x"), action.get("y"))
        slots = factory_slots if action_type == "train_units" else reconstructor_slots
        claimed = claimed_factories if action_type == "train_units" else claimed_reconstructors
        facility = "factory" if action_type == "train_units" else "reconstructor"
        if coordinate not in slots:
            reason = f"{facility}_not_present_in_observed_state"
        elif not slots[coordinate]:
            reason = f"{facility}_queue_busy_in_observed_state"
        elif coordinate in claimed:
            reason = f"duplicate_{facility}_order_in_same_decision"
        else:
            if action_type == "train_units":
                requested_unit = str(action.get("unit", ""))
                supported = factory_plans.get(coordinate, {})
                if requested_unit not in supported:
                    skipped.append({
                        "original_index": index,
                        "action": action,
                        "reason": "unsupported_unit_plan_in_observed_state",
                        "message": (
                            "Skipped because this factory does not expose the requested unit plan. "
                            "Choose a unit listed for this facility in offensive_production."
                        ),
                    })
                    continue
                requested_count = int(action.get("count", 1) or 1)
                if requested_count > supported[requested_unit]:
                    skipped.append({
                        "original_index": index,
                        "action": action,
                        "reason": "insufficient_training_resources_in_observed_state",
                        "message": (
                            "Skipped because the requested count exceeds the currently observed stockpile limit "
                            "for this factory plan. Re-observe or request no more than the reported maximum."
                        ),
                    })
                    continue
            if action_type == "upgrade_units":
                pair = (str(action.get("from_unit", "")), str(action.get("to_unit", "")))
                supported = reconstructor_upgrades.get(coordinate, {})
                if pair not in supported:
                    skipped.append({
                        "original_index": index,
                        "action": action,
                        "reason": "unsupported_upgrade_pair_in_observed_state",
                        "message": (
                            "Skipped because this reconstructor does not expose the requested exact upgrade pair. "
                            "Choose a pair listed for this facility in offensive_production."
                        ),
                    })
                    continue
                requested_count = int(action.get("count", 1) or 1)
                if requested_count > supported[pair]:
                    skipped.append({
                        "original_index": index,
                        "action": action,
                        "reason": "insufficient_upgrade_inputs_or_resources_in_observed_state",
                        "message": (
                            "Skipped because the requested count exceeds the observed maximum startable now for "
                            "this exact pair. Re-observe or request no more than the reported maximum."
                        ),
                    })
                    continue
            claimed.add(coordinate)
            executable.append(action)
            continue
        skipped.append({
            "original_index": index,
            "action": action,
            "reason": reason,
            "message": (
                f"Skipped before execution because the selected {facility} cannot accept a new order "
                "from the state used for this decision. Choose a currently available slot on the next turn."
            ),
        })
    return executable, skipped


def prepare_decision_actions(
    state: dict[str, Any], decision: dict[str, Any]
) -> tuple[dict[str, Any], list[dict[str, Any]], list[dict[str, Any]]]:
    """Resolve stable action references even when no action needs to be skipped."""
    requested_actions = decision["actions"]
    executable_actions, preflight_skips = preflight_rts_queue_actions(
        state, requested_actions
    )
    return {**decision, "actions": executable_actions}, requested_actions, preflight_skips


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class JsonlRunLog:
    def __init__(self, run_dir: Path, episode_id: str, model: str) -> None:
        run_dir.mkdir(parents=True, exist_ok=True)
        safe_episode = "".join(ch for ch in episode_id if ch.isalnum() or ch in "-_")[:80]
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        self.path = run_dir / f"{stamp}_{safe_episode}_{model.replace(':', '-')}.jsonl"

    def write(self, record: dict[str, Any]) -> None:
        with self.path.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")


def run(config: AgentConfig, *, once: bool, dry_run: bool, max_turns: int | None) -> Path:
    game = MindustryClient(config.game_url, config.game_token, config.game_team)
    llm = OpenAICompatibleLlm(
        base_url=config.llm_base_url,
        api_key=config.llm_api_key,
        model=config.llm_model,
        timeout_seconds=config.llm_timeout_seconds,
        max_tokens=config.llm_max_tokens,
        reasoning_effort=config.llm_reasoning_effort,
        json_mode=config.json_mode,
    )

    health = game.health()
    state = game.state()
    episode_id = str(state.get("episode_id") or health.get("episode_id") or "unknown")
    run_log = JsonlRunLog(config.run_dir, episode_id, config.llm_model)
    run_log.write({
        "kind": "run_start",
        "at": utc_now(),
        "episode_id": episode_id,
        "model": config.llm_model,
        "team": config.game_team,
        "game_url": config.game_url,
        "dry_run": dry_run,
        "cross_episode_memory": False,
    })
    print(f"run log: {run_log.path}")

    recent_results: list[dict[str, Any]] = []
    event_tracker = StateEventTracker()
    infrastructure_backlog = InfrastructureBacklog()
    action_failures = ActionFailureTracker()
    event_history: list[dict[str, Any]] = []
    strategic_context: dict[str, Any] = {}

    turns = 0
    while True:
        state = game.state()
        if str(state.get("episode_id")) != episode_id:
            previous = state.get("previous_episode_result")
            previous = previous if isinstance(previous, dict) else {}
            if str(previous.get("episode_id")) == episode_id:
                winner = previous.get("winner")
                result = "won" if winner == config.game_team else "lost" if winner else "ended"
                run_log.write({
                    "kind": "run_end", "at": utc_now(), "reason": "game_over_after_map_rotation",
                    "result": result, "winner": winner, "previous_episode_result": previous,
                })
            else:
                run_log.write({"kind": "run_end", "at": utc_now(), "reason": "episode_changed"})
            break
        if state.get("game_over") or state.get("result") in {"won", "lost"}:
            run_log.write({"kind": "run_end", "at": utc_now(), "reason": "game_over", "state": state})
            break
        if not state.get("playing"):
            if once:
                raise RuntimeError("Mindustry has no actively playing map.")
            time.sleep(2)
            continue

        observed_events = event_tracker.observe(state)
        infrastructure_backlog.update(observed_events, state.get("wave"))
        event_history.extend(observed_events)
        event_history = event_history[-80:]
        decision_state = dict(state)
        decision_state["infrastructure_backlog"] = infrastructure_backlog.for_prompt()
        decision_state["recent_defense_outcomes"] = defense_outcomes_for_prompt(event_history)
        try:
            compact_decision_state = compact_state(decision_state)
            llm_result = llm.decide(
                compact_decision_state, recent_results, events_for_prompt(event_history),
                strategic_context, action_failures.for_prompt(),
            )
        except (ValueError, HttpJsonError) as error:
            run_log.write({
                "kind": "turn_error", "at": utc_now(), "turn": turns,
                "episode_id": episode_id, "error": type(error).__name__, "message": str(error),
            })
            if (
                isinstance(error, HttpJsonError)
                and error.status_code == 429
                and "session usage limit" in str(error).lower()
            ):
                run_log.write({
                    "kind": "run_end", "at": utc_now(),
                    "reason": "model_session_usage_limit", "turn": turns,
                })
                print(f"turn={turns} model session usage limit reached; stopping")
                break
            if isinstance(error, HttpJsonError) and error.status_code in {404, 410}:
                run_log.write({
                    "kind": "run_end", "at": utc_now(),
                    "reason": "model_unavailable", "turn": turns,
                    "status_code": error.status_code,
                })
                print(f"turn={turns} model is unavailable ({error.status_code}); stopping")
                break
            print(f"turn={turns} model response failed: {error}; continuing")
            turns += 1
            if once or (max_turns is not None and turns >= max_turns):
                break
            retry_delay = decision_delay(state, max(2.0, config.decision_interval_seconds), 2.0)
            time.sleep(retry_delay)
            continue
        decision, requested_actions, preflight_skips = prepare_decision_actions(
            compact_decision_state, llm_result.decision
        )
        previous_objective = strategic_context.get("objective")
        next_objective = decision["strategic_intent"].get("objective", "")
        same_objective = bool(next_objective) and next_objective == previous_objective
        strategic_context = {
            "meaning": (
                "Previous short-horizon intent from this episode only. Continue it only while its next observable "
                "outcome remains feasible; completed prerequisites or changed evidence require a fresh current step."
            ),
            "objective": next_objective,
            "rationale": decision["strategic_intent"].get("rationale", "")[:300],
            "success_evidence": decision["strategic_intent"].get("success_evidence", [])[:4],
            "revision_triggers": decision["strategic_intent"].get("revision_triggers", [])[:4],
            "started_at_turn": strategic_context.get("started_at_turn", turns) if same_objective else turns,
            "started_at_wave": strategic_context.get("started_at_wave", state.get("wave")) if same_objective else state.get("wave"),
            "unchanged_for_turns": int(strategic_context.get("unchanged_for_turns", 0)) + 1 if same_objective else 0,
        }
        action_observed_tick = float(state.get("tick", 0))
        if not dry_run:
            latest_before_action = game.state()
            latest_episode_id = str(latest_before_action.get("episode_id"))
            if (
                latest_episode_id != episode_id
                or latest_before_action.get("game_over")
                or latest_before_action.get("result") in {"won", "lost"}
            ):
                run_log.write({
                    "kind": "run_end",
                    "at": utc_now(),
                    "reason": "game_over_during_inference",
                    "state": latest_before_action,
                    "discarded_turn": turns,
                    "discarded_decision": decision,
                    "llm_latency_seconds": llm_result.latency_seconds,
                    "llm_usage": llm_result.usage,
                })
                break
            latest_compact_state = compact_state(latest_before_action)
            action_observed_tick = float(latest_before_action.get("tick", action_observed_tick))
            refreshed_actions, execution_time_skips = preflight_rts_queue_actions(
                latest_compact_state, decision["actions"]
            )
            for skipped_action in execution_time_skips:
                skipped_action["phase"] = "immediately_before_execution"
            decision["actions"] = refreshed_actions
            preflight_skips.extend(execution_time_skips)
        request_id = str(uuid4())
        action_response: dict[str, Any]
        if dry_run:
            action_response = {"request_id": request_id, "dry_run": True, "results": []}
        else:
            try:
                action_response = game.act(
                    request_id,
                    decision["actions"],
                    expected_episode_id=episode_id,
                    observed_tick=action_observed_tick,
                    agent_telemetry={
                        "model": config.llm_model,
                        "turn": turns,
                        "latency_seconds": llm_result.latency_seconds,
                        "strategy": decision["strategic_intent"].get("objective", ""),
                        "reasoning_summary": decision["reasoning_summary"],
                        "requested_action_count": len(requested_actions),
                        "executed_action_count": len(decision["actions"]),
                        "preflight_skipped_count": len(preflight_skips),
                    },
                )
            except HttpJsonError as error:
                action_response = {
                    "request_id": request_id,
                    "ok": False,
                    "error": "action_request_rejected",
                    "message": str(error),
                    "results": [],
                }

        effective_wait_seconds = decision_delay(
            state, config.decision_interval_seconds, decision["wait_seconds"]
        )
        record = {
            "kind": "turn",
            "at": utc_now(),
            "turn": turns,
            "episode_id": episode_id,
            "state": state,
            "observed_events": observed_events,
            "infrastructure_backlog": infrastructure_backlog.for_prompt(),
            "recent_defense_outcomes": defense_outcomes_for_prompt(event_history),
            "decision": decision,
            "model_requested_actions": requested_actions,
            "preflight_skips": preflight_skips,
            "action_response": action_response,
            "observed_tick": state.get("tick"),
            "action_revalidated_tick": action_observed_tick,
            "action_applied_tick": action_response.get("tick"),
            "llm_latency_seconds": llm_result.latency_seconds,
            "llm_usage": llm_result.usage,
            "llm_json_repair_attempted": llm_result.repair_attempted,
            "llm_validation_skips": list(llm_result.validation_skips),
            "effective_wait_seconds": effective_wait_seconds,
        }
        run_log.write(record)
        action_failures.update(
            decision["actions"], action_response.get("results", []), turn=turns, wave=state.get("wave"),
        )
        action_failures.update_preflight(preflight_skips, turn=turns, wave=state.get("wave"))
        compact_outcome = compact_action_outcome(state.get("wave"), decision, action_response, turns)
        if preflight_skips:
            compact_outcome["preflight_skips"] = preflight_skips[:8]
        recent_results.append(compact_outcome)
        recent_results = recent_results[-4:]
        successful = sum(1 for item in action_response.get("results", []) if item.get("ok"))
        print(
            f"turn={turns} wave={state.get('wave')} actions={len(decision['actions'])} "
            f"preflight_skipped={len(preflight_skips)} successful={successful} "
            f"llm={llm_result.latency_seconds:.2f}s "
            f"summary={decision['reasoning_summary']}"
        )

        turns += 1
        if once or (max_turns is not None and turns >= max_turns):
            run_log.write({"kind": "run_end", "at": utc_now(), "reason": "turn_limit"})
            break
        time.sleep(effective_wait_seconds)

    return run_log.path


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run the single-LLM Mindustry baseline agent.")
    parser.add_argument("--once", action="store_true", help="Make one decision and exit.")
    parser.add_argument("--dry-run", action="store_true", help="Call the LLM but do not execute actions.")
    parser.add_argument("--max-turns", type=int, help="Stop after this many decisions.")
    parser.add_argument("--model", help="Override LLM_MODEL for this runner.")
    parser.add_argument("--reasoning-effort", help="Override LLM_REASONING_EFFORT for this runner.")
    parser.add_argument("--team", help="Mindustry team assigned to this runner.")
    parser.add_argument("--game-token", help="Bearer token mapped to the assigned Mindustry team.")
    return parser


def main() -> None:
    # The server launches Python with a Windows pipe whose default encoding may be CP949.
    # Cloud model summaries can contain Unicode punctuation; never let console rendering
    # terminate the control loop.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    args = build_parser().parse_args()
    config = AgentConfig.from_env()
    config = replace(
        config,
        llm_model=args.model or config.llm_model,
        llm_reasoning_effort=args.reasoning_effort or config.llm_reasoning_effort,
        game_team=args.team or config.game_team,
        game_token=args.game_token if args.game_token is not None else config.game_token,
    )
    run(config, once=args.once, dry_run=args.dry_run, max_turns=args.max_turns)
