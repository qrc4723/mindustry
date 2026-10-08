from __future__ import annotations

from collections import Counter
import json
from typing import Any


def _building_key(building: dict[str, Any]) -> tuple[str, int, int]:
    return (str(building.get("block", "unknown")), int(building.get("x", 0)), int(building.get("y", 0)))


def _direction_from_core(state: dict[str, Any], x: Any, y: Any) -> str | None:
    core = state.get("core") or {}
    if not isinstance(x, (int, float)) or not isinstance(y, (int, float)):
        return None
    core_x, core_y = core.get("x"), core.get("y")
    if not isinstance(core_x, (int, float)) or not isinstance(core_y, (int, float)):
        return None
    dx = 0 if x == core_x else (1 if x > core_x else -1)
    dy = 0 if y == core_y else (1 if y > core_y else -1)
    return f"{dx}:{dy}"


def _nearest_enemy_evidence(state: dict[str, Any]) -> dict[str, Any] | None:
    core = state.get("core") or {}
    core_x, core_y = core.get("x"), core.get("y")
    if not isinstance(core_x, (int, float)) or not isinstance(core_y, (int, float)):
        return None
    nearest: dict[str, Any] | None = None
    nearest_distance2 = float("inf")
    for enemy in state.get("enemy_units", []):
        if not isinstance(enemy, dict):
            continue
        x, y = enemy.get("x"), enemy.get("y")
        if not isinstance(x, (int, float)) or not isinstance(y, (int, float)):
            continue
        distance2 = (x - core_x) ** 2 + (y - core_y) ** 2
        if distance2 < nearest_distance2:
            nearest_distance2 = distance2
            nearest = {
                "type": enemy.get("type"), "x": x, "y": y,
                "distance_to_core_tiles": distance2 ** 0.5,
                "direction_from_core": _direction_from_core(state, x, y),
            }
    return nearest


class StateEventTracker:
    """Turns raw consecutive observations into factual events; it makes no strategic decisions."""

    def __init__(self) -> None:
        self.previous: dict[str, Any] | None = None
        self.wave_number: Any = None
        self.wave_start_core_health = 0.0
        self.wave_peak_enemy_units = 0

    def observe(self, state: dict[str, Any]) -> list[dict[str, Any]]:
        if self.previous is None:
            self.previous = state
            self.wave_number = state.get("wave")
            self.wave_start_core_health = float((state.get("core") or {}).get("health") or 0)
            self.wave_peak_enemy_units = len(state.get("enemy_units", []))
            return [{"type": "episode_observed", "wave": state.get("wave")}]

        previous = self.previous
        event_wave = previous.get("wave") if previous.get("wave") != state.get("wave") else state.get("wave")
        events: list[dict[str, Any]] = []
        previous_buildings = {
            _building_key(item): item for item in previous.get("buildings", []) if isinstance(item, dict)
        }
        current_buildings = {
            _building_key(item): item for item in state.get("buildings", []) if isinstance(item, dict)
        }
        for key in sorted(previous_buildings.keys() - current_buildings.keys()):
            building = previous_buildings[key]
            events.append({
                "type": "building_lost", "block": key[0], "x": key[1], "y": key[2],
                "rotation": building.get("rotation", 0),
                "previous_health": building.get("health"), "previous_items": building.get("items", {}),
                "wave": event_wave,
                "direction_from_core": _direction_from_core(state, key[1], key[2]),
            })
        for key in sorted(current_buildings.keys() - previous_buildings.keys()):
            building = current_buildings[key]
            events.append({"type": "building_added", "block": key[0], "x": key[1], "y": key[2]})
        for key in sorted(previous_buildings.keys() & current_buildings.keys()):
            before = previous_buildings[key]
            after = current_buildings[key]
            health_before = float(before.get("health") or 0)
            health_after = float(after.get("health") or 0)
            if health_after + 1 < health_before:
                events.append({
                    "type": "building_damaged", "block": key[0], "x": key[1], "y": key[2],
                    "health_before": health_before, "health_after": health_after,
                    "wave": event_wave,
                    "direction_from_core": _direction_from_core(state, key[1], key[2]),
                })
            ammo_before = before.get("ammo_fraction")
            ammo_after = after.get("ammo_fraction")
            if isinstance(ammo_before, (int, float)) and isinstance(ammo_after, (int, float)):
                if ammo_after < ammo_before - 0.2:
                    events.append({
                        "type": "turret_ammo_consumed", "block": key[0], "x": key[1], "y": key[2],
                        "fraction_before": ammo_before, "fraction_after": ammo_after,
                    })
            stall_before = before.get("stall_reason")
            stall_after = after.get("stall_reason")
            if stall_before != stall_after and (stall_before not in {None, "none"} or stall_after not in {None, "none"}):
                events.append({
                    "type": "production_status_changed", "block": key[0], "x": key[1], "y": key[2],
                    "from": stall_before, "to": stall_after,
                    "consumption_status": after.get("consumption_status", []),
                })

        core_before = previous.get("core") or {}
        core_after = state.get("core") or {}
        health_before = float(core_before.get("health") or 0)
        health_after = float(core_after.get("health") or 0)
        if health_after + 1 < health_before:
            events.append({
                "type": "core_damaged", "health_before": health_before, "health_after": health_after,
                "damage": health_before - health_after,
                "wave": event_wave, "nearest_enemy": _nearest_enemy_evidence(state),
            })
        if previous.get("wave") != state.get("wave"):
            previous_enemy_count = len(previous.get("enemy_units", []))
            self.wave_peak_enemy_units = max(self.wave_peak_enemy_units, previous_enemy_count)
            events.append({
                "type": "wave_outcome", "wave": previous.get("wave"),
                "peak_enemy_units_observed": self.wave_peak_enemy_units,
                "core_health_start": self.wave_start_core_health,
                "core_health_end": health_after,
                "core_damage": max(0.0, self.wave_start_core_health - health_after),
            })
            events.append({"type": "wave_changed", "from": previous.get("wave"), "to": state.get("wave")})
            self.wave_number = state.get("wave")
            self.wave_start_core_health = health_after
            self.wave_peak_enemy_units = len(state.get("enemy_units", []))
        else:
            self.wave_peak_enemy_units = max(self.wave_peak_enemy_units, len(state.get("enemy_units", [])))

        unit_before = previous.get("core_defender") or {}
        unit_after = state.get("core_defender") or {}
        if unit_before.get("task") != unit_after.get("task"):
            events.append({
                "type": "core_unit_task_changed",
                "from": unit_before.get("task"), "to": unit_after.get("task"),
                "resource": unit_after.get("task_resource"),
            })
        carried_before = int(unit_before.get("carried_amount") or 0)
        carried_after = int(unit_after.get("carried_amount") or 0)
        if carried_before != carried_after:
            events.append({
                "type": "core_unit_cargo_changed",
                "item": unit_after.get("carried_item") or unit_before.get("carried_item"),
                "from": carried_before, "to": carried_after,
            })

        before_items = core_before.get("items") or {}
        after_items = core_after.get("items") or {}
        for item in sorted(set(before_items) | set(after_items)):
            delta = int(after_items.get(item, 0)) - int(before_items.get(item, 0))
            if delta:
                events.append({"type": "core_item_changed", "item": item, "delta": delta, "amount": after_items.get(item, 0)})

        self.previous = state
        critical = [event for event in events if event.get("type") in {"building_lost", "core_damaged", "wave_outcome", "wave_changed"}]
        routine = [event for event in events if event not in critical]
        return critical + routine[-max(0, 40 - len(critical)):]


class InfrastructureBacklog:
    """Retains unresolved destruction facts without deciding whether rebuilding is worthwhile."""

    def __init__(self) -> None:
        self._losses: dict[tuple[int, int], dict[str, Any]] = {}

    def update(self, events: list[dict[str, Any]], wave: Any = None) -> None:
        for event in events:
            if not isinstance(event, dict):
                continue
            event_type = event.get("type")
            x, y = event.get("x"), event.get("y")
            if not isinstance(x, int) or not isinstance(y, int):
                continue
            key = (x, y)
            if event_type == "building_added":
                self._losses.pop(key, None)
            elif event_type == "building_lost":
                self._losses[key] = {
                    "block": event.get("block"), "x": x, "y": y,
                    "rotation": event.get("rotation", 0),
                    "previous_health": event.get("previous_health"),
                    "lost_at_wave": wave,
                }

    def for_prompt(self) -> dict[str, Any]:
        losses = list(self._losses.values())
        counts = Counter(str(loss.get("block", "unknown")) for loss in losses)
        return {
            "meaning": "unresolved observed losses; rebuilding, replacing, redesigning, or accepting each loss is the LLM's choice",
            "unresolved_loss_count": len(losses),
            "counts_by_block": dict(counts),
            "losses": losses[-120:],
            "older_loss_coordinates_omitted": max(0, len(losses) - 120),
        }


def events_for_prompt(history: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Keep critical facts and recent context; ordering remains chronological."""
    critical_types = {"building_lost", "core_damaged", "wave_outcome", "wave_changed"}
    critical_indices = [
        index for index, event in enumerate(history)
        if isinstance(event, dict) and event.get("type") in critical_types
    ][-24:]
    recent_indices = list(range(max(0, len(history) - 20), len(history)))
    return [history[index] for index in sorted(set(critical_indices + recent_indices))]


def defense_outcomes_for_prompt(history: list[dict[str, Any]]) -> dict[str, Any]:
    """Summarize only current-episode combat observations; nothing is persisted across runs."""
    losses: Counter[str] = Counter()
    damaged: Counter[str] = Counter()
    core_damage = 0.0
    ammo_events = 0
    wave_changes: list[dict[str, Any]] = []
    wave_outcomes: list[dict[str, Any]] = []
    by_wave: dict[str, dict[str, Any]] = {}
    for event in history:
        if not isinstance(event, dict):
            continue
        event_type = event.get("type")
        wave_key = str(event.get("wave", "unknown"))
        wave = by_wave.setdefault(wave_key, {
            "wave": event.get("wave"), "core_damage": 0.0,
            "building_losses_by_block": {}, "damage_events_by_block": {},
            "observed_attack_directions": {},
        })
        if event_type == "building_lost":
            block = str(event.get("block", "unknown"))
            losses[block] += 1
            wave["building_losses_by_block"][block] = wave["building_losses_by_block"].get(block, 0) + 1
        elif event_type == "building_damaged":
            block = str(event.get("block", "unknown"))
            damaged[block] += 1
            wave["damage_events_by_block"][block] = wave["damage_events_by_block"].get(block, 0) + 1
        elif event_type == "core_damaged":
            amount = float(event.get("damage") or 0)
            core_damage += amount
            wave["core_damage"] += amount
            nearest = event.get("nearest_enemy")
            direction = nearest.get("direction_from_core") if isinstance(nearest, dict) else None
            if direction:
                wave["observed_attack_directions"][direction] = wave["observed_attack_directions"].get(direction, 0) + 1
        elif event_type == "turret_ammo_consumed":
            ammo_events += 1
        elif event_type == "wave_changed":
            wave_changes.append({"from": event.get("from"), "to": event.get("to")})
        elif event_type == "wave_outcome":
            wave_outcomes.append({
                key: event.get(key) for key in (
                    "wave", "peak_enemy_units_observed", "core_health_start",
                    "core_health_end", "core_damage",
                )
            })
    return {
        "meaning": (
            "Observed outcomes from this episode only, not a strategy rule or cross-episode memory. "
            "Use them as evidence of what the current defense actually endured."
        ),
        "building_losses_by_block": dict(losses),
        "damage_events_by_block": dict(damaged),
        "observed_core_damage": core_damage,
        "turret_ammo_consumption_events": ammo_events,
        "recent_wave_transitions": wave_changes[-6:],
        "completed_wave_outcomes": wave_outcomes[-6:],
        "outcomes_by_wave": [
            wave for wave in by_wave.values()
            if wave["core_damage"] or wave["building_losses_by_block"] or wave["damage_events_by_block"]
        ][-8:],
    }


def _no_effect_reason(action: dict[str, Any], result: dict[str, Any]) -> str | None:
    if not result.get("ok"):
        return None
    action_type = action.get("type")
    if action_type == "resupply_turrets" and int(result.get("items_transferred") or 0) == 0:
        return "no turret received ammunition"
    if action_type == "command_core_unit" and result.get("changed") is False:
        return "unit was already assigned to this task"
    return None


class ActionFailureTracker:
    """Retains repeated execution failures as evidence without prescribing a response."""

    def __init__(self) -> None:
        self._failures: dict[str, dict[str, Any]] = {}

    def update(
        self, actions: list[dict[str, Any]], results: list[dict[str, Any]], *, turn: int, wave: Any,
    ) -> None:
        for result in results:
            if not isinstance(result, dict):
                continue
            index = result.get("index")
            action = actions[index] if isinstance(index, int) and 0 <= index < len(actions) else result.get("attempted_action")
            if not isinstance(action, dict):
                continue
            signature = _action_signature(action)
            no_effect = _no_effect_reason(action, result)
            if bool(result.get("ok")) and no_effect is None:
                self._failures.pop(signature, None)
                continue
            self._record(
                action,
                error="no_effect" if no_effect else str(result.get("error", "unknown")),
                message=no_effect or str(result.get("message", "")),
                diagnostics=result.get("diagnostics", {}), turn=turn, wave=wave,
            )

    def update_preflight(self, skips: list[dict[str, Any]], *, turn: int, wave: Any) -> None:
        """Keep within-episode feedback for model-authored actions filtered before execution."""
        for skipped in skips:
            if not isinstance(skipped, dict) or not isinstance(skipped.get("action"), dict):
                continue
            self._record(
                skipped["action"], error=str(skipped.get("reason", "preflight_skip")),
                message=str(skipped.get("message", "")), diagnostics={
                    "phase": skipped.get("phase", "after_model_response")
                }, turn=turn, wave=wave,
            )

    def _record(
        self, action: dict[str, Any], *, error: str, message: str,
        diagnostics: Any, turn: int, wave: Any,
    ) -> None:
        signature = _action_signature(action)
        previous = self._failures.get(signature, {})
        consecutive = (
            int(previous.get("consecutive_turns", 0)) + 1
            if previous.get("last_turn") == turn - 1 else 1
        )
        self._failures[signature] = {
            "action": _compact_action(action),
            "error": error,
            "message": message[:300],
            "diagnostics": diagnostics if isinstance(diagnostics, dict) else {},
            "total_failures": int(previous.get("total_failures", 0)) + 1,
            "consecutive_turns": consecutive,
            "first_failed_turn": previous.get("first_failed_turn", turn),
            "last_turn": turn,
            "last_wave": wave,
        }

    def for_prompt(self) -> dict[str, Any]:
        failures = sorted(
            self._failures.values(),
            key=lambda item: (int(item.get("consecutive_turns", 0)), int(item.get("total_failures", 0)), int(item.get("last_turn", -1))),
            reverse=True,
        )
        groups: dict[tuple[str, str], dict[str, Any]] = {}
        for failure in failures:
            action = failure.get("action", {})
            key = (str(action.get("type", "unknown")), str(failure.get("error", "unknown")))
            group = groups.setdefault(key, {
                "action_type": key[0], "error": key[1], "unresolved_actions": 0,
                "total_failures": 0, "examples": [],
            })
            group["unresolved_actions"] += 1
            group["total_failures"] += int(failure.get("total_failures", 0))
            if len(group["examples"]) < 3:
                group["examples"].append(action)
        return {
            "meaning": (
                "Unresolved observed action failures. Repeating an unchanged action tests nothing; "
                "revise coordinates, endpoints, components, or objective unless new evidence changes feasibility."
            ),
            "groups": list(groups.values())[:10],
            "most_repeated": failures[:12],
            "additional_failures_omitted": max(0, len(failures) - 12),
        }


def compact_action_outcome(
    wave: Any, decision: dict[str, Any], response: dict[str, Any], turn: int,
) -> dict[str, Any]:
    """Summarize a turn without replaying large action and placement arrays into the next prompt."""
    actions = decision.get("actions", [])
    if not isinstance(actions, list):
        actions = []
    results = response.get("results", [])
    if not isinstance(results, list):
        results = []
    successes: Counter[str] = Counter()
    failures: list[dict[str, Any]] = []
    no_effects: list[dict[str, Any]] = []
    evidence_results: list[dict[str, Any]] = []
    for result in results:
        if not isinstance(result, dict):
            continue
        index = result.get("index")
        action = actions[index] if isinstance(index, int) and 0 <= index < len(actions) else result.get("attempted_action", {})
        action_type = str(action.get("type", result.get("type", "unknown"))) if isinstance(action, dict) else str(result.get("type", "unknown"))
        no_effect = _no_effect_reason(action, result) if isinstance(action, dict) else None
        if result.get("ok") and no_effect is None:
            successes[action_type] += 1
            if action_type == "inspect_item_route" and len(evidence_results) < 2:
                evidence = {
                    key: result.get(key) for key in (
                        "type", "source", "target", "transport", "local_path_map", "instruction"
                    )
                }
                evidence["local_path_map"] = _compact_local_path_map(evidence.get("local_path_map"))
                evidence_results.append(evidence)
            elif action_type == "preview_conveyor_path" and len(evidence_results) < 2:
                evidence_results.append({
                    key: result.get(key) for key in (
                        "type", "source", "target", "transport", "preflight", "preview_token", "next_action"
                    )
                })
        elif result.get("ok") and len(no_effects) < 8:
            no_effects.append({
                "action": _compact_action(action), "effect": "none", "reason": no_effect,
            })
        elif len(failures) < 8:
            failures.append({
                "action": _compact_action(action) if isinstance(action, dict) else {"type": action_type},
                "error": result.get("error"), "message": str(result.get("message", ""))[:240],
                "diagnostics": result.get("diagnostics", {}),
            })
    intent = decision.get("strategic_intent", {})
    return {
        "turn": turn,
        "wave": wave,
        "objective": str(intent.get("objective", ""))[:300] if isinstance(intent, dict) else "",
        "action_count": len(actions),
        "successful_by_type": dict(successes),
        "no_effect_count": len(no_effects),
        "no_effect_examples": no_effects,
        "evidence_results": evidence_results,
        "failure_count": sum(1 for result in results if isinstance(result, dict) and not result.get("ok")),
        "failure_examples": failures,
    }


def _compact_local_path_map(value: Any) -> Any:
    """Column-pack exact route evidence so geometry stays usable without verbose repeated keys."""
    if not isinstance(value, dict) or not isinstance(value.get("tile_exceptions"), list):
        return value
    columns = [
        "x", "y", "valid_rotations", "terrain_status", "terrain_block", "floor", "ore",
        "building", "building_items", "building_output_to",
    ]
    rows: list[list[Any]] = []
    for exception in value["tile_exceptions"]:
        if not isinstance(exception, dict):
            continue
        building = exception.get("building") if isinstance(exception.get("building"), dict) else {}
        output = (
            exception.get("building_output_to")
            if isinstance(exception.get("building_output_to"), dict) else {}
        )
        rows.append([
            exception.get("x"), exception.get("y"),
            exception.get("valid_transport_rotations", []), exception.get("terrain_status"),
            exception.get("terrain_block"), exception.get("floor"), exception.get("ore"),
            [
                building.get("block"), building.get("x"), building.get("y"),
                exception.get("building_size"), exception.get("building_rotation"),
            ] if building else None,
            exception.get("building_items", []),
            [output.get("block"), output.get("x"), output.get("y")] if output else None,
        ])
    return {
        key: value.get(key) for key in (
            "coordinate_system", "bounds", "default_unlisted_tile", "exceptions_omitted", "note",
        ) if value.get(key) is not None
    } | {
        "exception_table": {"columns": columns, "rows": rows},
    }


def _compact_action(action: dict[str, Any]) -> dict[str, Any]:
    keys = (
        "type", "block", "x", "y", "rotation", "resource", "drill", "transport",
        "source_x", "source_y", "target_x", "target_y", "turret", "ammo", "wall", "mode", "item",
    )
    result = {key: action.get(key) for key in keys if action.get(key) is not None}
    placements = action.get("placements")
    if isinstance(placements, list):
        result["path_length"] = len(placements)
        if placements and isinstance(placements[0], dict):
            result["path_start"] = {
                key: placements[0].get(key) for key in ("x", "y", "rotation") if placements[0].get(key) is not None
            }
        if placements and isinstance(placements[-1], dict):
            result["path_end"] = {
                key: placements[-1].get(key) for key in ("x", "y", "rotation") if placements[-1].get(key) is not None
            }
    return result


def _action_signature(action: dict[str, Any]) -> str:
    return json.dumps(_compact_action(action), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
