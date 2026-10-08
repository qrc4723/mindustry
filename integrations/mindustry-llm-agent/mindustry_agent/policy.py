from __future__ import annotations

import ast
import hashlib
import json
import math
import re
from typing import Any


MAX_ACTIONS = 32
MAX_EXPLICIT_PATH_TILES = 192
MAX_MACRO_COST = 5000
MAX_REASONING_SUMMARY = 1200
MAX_STRATEGY_TEXT = 500


class DecisionError(ValueError):
    pass


def extract_json_object(text: str) -> dict[str, Any]:
    candidate = text.strip()
    fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", candidate, re.DOTALL | re.IGNORECASE)
    if fenced:
        candidate = fenced.group(1)
    else:
        start = candidate.find("{")
        if start < 0:
            raise DecisionError("The model response contains no JSON object.")
        decoder = json.JSONDecoder()
        try:
            parsed, _ = decoder.raw_decode(candidate[start:])
        except json.JSONDecodeError as error:
            parsed = _safe_python_object_fallback(candidate[start:], error)
        if not isinstance(parsed, dict):
            raise DecisionError("The model response must be a JSON object.")
        return parsed

    try:
        parsed = json.loads(candidate)
    except json.JSONDecodeError as error:
        parsed = _safe_python_object_fallback(candidate, error)
    if not isinstance(parsed, dict):
        raise DecisionError("The model response must be a JSON object.")
    return parsed


def _safe_python_object_fallback(candidate: str, json_error: json.JSONDecodeError) -> Any:
    """Accept the common GLM failure mode of emitting a Python dict literal instead of JSON."""
    end = candidate.rfind("}")
    if end < 0:
        raise DecisionError(f"Invalid model JSON: {json_error}") from json_error
    try:
        parsed = ast.literal_eval(candidate[:end + 1])
    except (SyntaxError, ValueError) as error:
        raise DecisionError(f"Invalid model JSON: {json_error}") from error
    if not isinstance(parsed, dict):
        raise DecisionError("The model response must be a JSON object.")
    return parsed


def validate_decision(raw: dict[str, Any]) -> dict[str, Any]:
    reasoning = raw.get("reasoning_summary", "")
    if not isinstance(reasoning, str):
        raise DecisionError("reasoning_summary must be a string.")

    world_model = raw.get("world_model", {})
    if not isinstance(world_model, dict):
        world_model = {}
    normalized_world_model = {
        key: _short_string_list(world_model.get(key, []), 6)
        for key in ("capabilities", "constraints", "opportunities", "uncertainties")
    }

    strategic_intent = raw.get("strategic_intent", {})
    if not isinstance(strategic_intent, dict):
        strategic_intent = {}
    objective = strategic_intent.get("objective", "")
    rationale = strategic_intent.get("rationale", "")
    if not isinstance(objective, str) or not isinstance(rationale, str):
        raise DecisionError("strategic_intent objective and rationale must be strings.")
    normalized_intent = {
        "objective": objective[:MAX_STRATEGY_TEXT],
        "rationale": rationale[:MAX_STRATEGY_TEXT],
        "success_evidence": _short_string_list(strategic_intent.get("success_evidence", []), 5),
        "revision_triggers": _short_string_list(strategic_intent.get("revision_triggers", []), 5),
    }

    wait_seconds = _bounded_number(raw.get("wait_seconds", 0.5), "wait_seconds", 0.5, 120.0)

    actions = raw.get("actions", [])
    if not isinstance(actions, list):
        raise DecisionError("actions must be an array.")
    if len(actions) > MAX_ACTIONS:
        raise DecisionError(f"At most {MAX_ACTIONS} actions are allowed.")

    normalized: list[dict[str, Any]] = []
    for index, action in enumerate(actions):
        if not isinstance(action, dict):
            raise DecisionError(f"actions[{index}] must be an object.")
        action_type = action.get("type")
        if action_type == "place":
            block = action.get("block")
            if not isinstance(block, str) or not block:
                raise DecisionError(f"actions[{index}].block must be a non-empty string.")
            rotation = _integer(action.get("rotation", 0), f"actions[{index}].rotation") % 4
            placement_option_id = action.get("placement_option_id")
            if placement_option_id is not None:
                normalized.append({
                    "type": "place", "block": block,
                    "placement_option_id": _required_string(
                        placement_option_id, f"actions[{index}].placement_option_id"
                    ),
                    "rotation": rotation,
                })
            else:
                normalized.append({
                    "type": "place", "block": block,
                    "x": _integer(action.get("x"), f"actions[{index}].x"),
                    "y": _integer(action.get("y"), f"actions[{index}].y"),
                    "rotation": rotation,
                })
        elif action_type == "remove":
            normalized.append({
                "type": action_type,
                "x": _integer(action.get("x"), f"actions[{index}].x"),
                "y": _integer(action.get("y"), f"actions[{index}].y"),
            })
        elif action_type in {"build_mine_to_core", "build_mine_to_target"}:
            normalized_action = {
                "type": action_type,
                "resource": _required_string(action.get("resource"), f"actions[{index}].resource"),
                "drill": _required_string(action.get("drill"), f"actions[{index}].drill"),
                "transport": _required_string(action.get("transport"), f"actions[{index}].transport"),
                "max_cost": _bounded_integer(action.get("max_cost"), f"actions[{index}].max_cost", 1, MAX_MACRO_COST),
                "reserve_copper": _bounded_integer(action.get("reserve_copper"), f"actions[{index}].reserve_copper", 0, 1000),
            }
            if action_type == "build_mine_to_target":
                normalized_action["target_x"] = _integer(action.get("target_x"), f"actions[{index}].target_x")
                normalized_action["target_y"] = _integer(action.get("target_y"), f"actions[{index}].target_y")
            normalized.append(normalized_action)
        elif action_type in {"route_items", "route_items_isolated", "route_liquid"}:
            normalized_action = {
                "type": action_type,
                "source_x": _integer(action.get("source_x"), f"actions[{index}].source_x"),
                "source_y": _integer(action.get("source_y"), f"actions[{index}].source_y"),
                "target_x": _integer(action.get("target_x"), f"actions[{index}].target_x"),
                "target_y": _integer(action.get("target_y"), f"actions[{index}].target_y"),
                "transport": _required_string(action.get("transport"), f"actions[{index}].transport"),
                "max_cost": _bounded_integer(action.get("max_cost"), f"actions[{index}].max_cost", 1, MAX_MACRO_COST),
                "reserve_copper": _bounded_integer(action.get("reserve_copper"), f"actions[{index}].reserve_copper", 0, 1000),
            }
            if action_type == "route_items_isolated":
                normalized_action["resource"] = _required_string(
                    action.get("resource"), f"actions[{index}].resource"
                )
            normalized.append(normalized_action)
        elif action_type == "upgrade_input_network":
            normalized.append({
                "type": action_type,
                "target_x": _integer(action.get("target_x"), f"actions[{index}].target_x"),
                "target_y": _integer(action.get("target_y"), f"actions[{index}].target_y"),
                "transport": _required_string(action.get("transport"), f"actions[{index}].transport"),
                "max_cost": _bounded_integer(action.get("max_cost"), f"actions[{index}].max_cost", 1, MAX_MACRO_COST),
                "reserve_copper": _bounded_integer(action.get("reserve_copper", 0), f"actions[{index}].reserve_copper", 0, 1000),
            })
        elif action_type == "clear_item_input_network":
            normalized.append({
                "type": action_type,
                "target_x": _integer(action.get("target_x"), f"actions[{index}].target_x"),
                "target_y": _integer(action.get("target_y"), f"actions[{index}].target_y"),
                "max_segments": _bounded_integer(
                    action.get("max_segments"), f"actions[{index}].max_segments", 1, 300
                ),
            })
        elif action_type == "connect_power":
            normalized.append({
                "type": action_type,
                "node_x": _integer(action.get("node_x"), f"actions[{index}].node_x"),
                "node_y": _integer(action.get("node_y"), f"actions[{index}].node_y"),
                "target_x": _integer(action.get("target_x"), f"actions[{index}].target_x"),
                "target_y": _integer(action.get("target_y"), f"actions[{index}].target_y"),
            })
        elif action_type == "set_unit_factory_plan":
            normalized.append({
                "type": action_type,
                "x": _integer(action.get("x"), f"actions[{index}].x"),
                "y": _integer(action.get("y"), f"actions[{index}].y"),
                "unit": _required_string(action.get("unit"), f"actions[{index}].unit"),
            })
        elif action_type == "train_units":
            normalized_action = {
                "type": action_type,
                "unit": _required_string(action.get("unit"), f"actions[{index}].unit"),
                "count": _bounded_integer(
                    action.get("count"), f"actions[{index}].count", 1, 50
                ),
            }
            if action.get("facility_id") is not None:
                normalized_action["facility_id"] = _required_string(
                    action.get("facility_id"), f"actions[{index}].facility_id"
                )
            else:
                normalized_action["x"] = _integer(action.get("x"), f"actions[{index}].x")
                normalized_action["y"] = _integer(action.get("y"), f"actions[{index}].y")
            has_rally_x = action.get("rally_x") is not None
            has_rally_y = action.get("rally_y") is not None
            if has_rally_x != has_rally_y:
                raise DecisionError(
                    f"actions[{index}].rally_x and rally_y must be supplied together."
                )
            if has_rally_x:
                normalized_action["rally_x"] = _integer(
                    action.get("rally_x"), f"actions[{index}].rally_x"
                )
                normalized_action["rally_y"] = _integer(
                    action.get("rally_y"), f"actions[{index}].rally_y"
                )
                normalized_action["rally_radius"] = _bounded_integer(
                    action.get("rally_radius", 4),
                    f"actions[{index}].rally_radius", 2, 12,
                )
            if action.get("squad_id") is not None:
                squad_id = _required_string(
                    action.get("squad_id"), f"actions[{index}].squad_id"
                )
                if not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", squad_id):
                    raise DecisionError(
                        f"actions[{index}].squad_id must contain 1-40 letters, digits, underscores, or hyphens."
                    )
                if not has_rally_x:
                    raise DecisionError(
                        f"actions[{index}].squad_id requires rally_x and rally_y."
                    )
                normalized_action["squad_id"] = squad_id
            normalized.append(normalized_action)
        elif action_type == "upgrade_units":
            normalized_action = {
                "type": action_type,
                "from_unit": _required_string(
                    action.get("from_unit"), f"actions[{index}].from_unit"
                ),
                "to_unit": _required_string(
                    action.get("to_unit"), f"actions[{index}].to_unit"
                ),
                "count": _bounded_integer(
                    action.get("count"), f"actions[{index}].count", 1, 50
                ),
            }
            if action.get("facility_id") is not None:
                normalized_action["facility_id"] = _required_string(
                    action.get("facility_id"), f"actions[{index}].facility_id"
                )
            else:
                normalized_action["x"] = _integer(action.get("x"), f"actions[{index}].x")
                normalized_action["y"] = _integer(action.get("y"), f"actions[{index}].y")
            normalized.append(normalized_action)
        elif action_type == "command_units":
            mode = action.get("mode")
            if mode not in {"attack", "attack_move", "rally", "defend", "retreat", "stop"}:
                raise DecisionError(
                    f"actions[{index}].mode must be attack, attack_move, rally, defend, retreat, or stop."
                )
            normalized_action = {
                "type": action_type,
                "unit": _required_string(action.get("unit"), f"actions[{index}].unit"),
                "mode": mode,
                "max_units": _bounded_integer(
                    action.get("max_units", 20), f"actions[{index}].max_units", 1, 200
                ),
            }
            if "squad_id" in action:
                normalized_action["squad_id"] = _required_string(
                    action.get("squad_id"), f"actions[{index}].squad_id"
                )
            if "engagement_radius" in action:
                normalized_action["engagement_radius"] = _bounded_integer(
                    action.get("engagement_radius"),
                    f"actions[{index}].engagement_radius", 2, 40,
                )
            if "unit_ids" in action:
                unit_ids = action.get("unit_ids")
                if not isinstance(unit_ids, list) or len(unit_ids) > 200:
                    raise DecisionError(f"actions[{index}].unit_ids must be an array of at most 200 IDs.")
                normalized_action["unit_ids"] = list(dict.fromkeys(
                    _integer(value, f"actions[{index}].unit_ids") for value in unit_ids
                ))
            if mode == "attack" and "target_unit_id" in action:
                normalized_action["target_unit_id"] = _integer(
                    action.get("target_unit_id"), f"actions[{index}].target_unit_id"
                )
            if mode in {"attack", "attack_move", "rally", "defend"}:
                normalized_action["target_x"] = _integer(
                    action.get("target_x"), f"actions[{index}].target_x"
                )
                normalized_action["target_y"] = _integer(
                    action.get("target_y"), f"actions[{index}].target_y"
                )
                normalized_action["target_radius"] = _bounded_integer(
                    action.get("target_radius", 2), f"actions[{index}].target_radius", 0, 30
                )
            normalized.append(normalized_action)
        elif action_type == "configure_item_filter":
            normalized.append({
                "type": action_type,
                "x": _integer(action.get("x"), f"actions[{index}].x"),
                "y": _integer(action.get("y"), f"actions[{index}].y"),
                "item": _required_string(action.get("item"), f"actions[{index}].item"),
            })
        elif action_type == "inspect_item_route":
            normalized.append({
                "type": action_type,
                "source_x": _integer(action.get("source_x"), f"actions[{index}].source_x"),
                "source_y": _integer(action.get("source_y"), f"actions[{index}].source_y"),
                "target_x": _integer(action.get("target_x"), f"actions[{index}].target_x"),
                "target_y": _integer(action.get("target_y"), f"actions[{index}].target_y"),
                "transport": _required_string(action.get("transport"), f"actions[{index}].transport"),
                "margin": _bounded_integer(action.get("margin", 6), f"actions[{index}].margin", 2, 12),
            })
        elif action_type in {"preview_conveyor_path", "place_conveyor_path"}:
            placements = action.get("placements")
            if not isinstance(placements, list) or not 1 <= len(placements) <= MAX_EXPLICIT_PATH_TILES:
                raise DecisionError(
                    f"actions[{index}].placements must contain 1 to {MAX_EXPLICIT_PATH_TILES} path tiles."
                )
            normalized_placements: list[dict[str, int]] = []
            for path_index, placement in enumerate(placements):
                if not isinstance(placement, dict):
                    raise DecisionError(f"actions[{index}].placements[{path_index}] must be an object.")
                normalized_placements.append({
                    "x": _integer(placement.get("x"), f"actions[{index}].placements[{path_index}].x"),
                    "y": _integer(placement.get("y"), f"actions[{index}].placements[{path_index}].y"),
                    "rotation": _integer(
                        placement.get("rotation"),
                        f"actions[{index}].placements[{path_index}].rotation",
                    ) % 4,
                })
            allowed_items = action.get("allowed_items")
            if not isinstance(allowed_items, list) or not allowed_items or not all(
                isinstance(item, str) and item for item in allowed_items
            ):
                raise DecisionError(f"actions[{index}].allowed_items must be a non-empty string array.")
            normalized_action = {
                "type": action_type,
                "source_x": _integer(action.get("source_x"), f"actions[{index}].source_x"),
                "source_y": _integer(action.get("source_y"), f"actions[{index}].source_y"),
                "target_x": _integer(action.get("target_x"), f"actions[{index}].target_x"),
                "target_y": _integer(action.get("target_y"), f"actions[{index}].target_y"),
                "transport": _required_string(action.get("transport"), f"actions[{index}].transport"),
                "placements": normalized_placements,
                "allowed_items": list(dict.fromkeys(allowed_items)),
                "max_cost": _bounded_integer(action.get("max_cost"), f"actions[{index}].max_cost", 1, MAX_MACRO_COST),
                "reserve_copper": _bounded_integer(
                    action.get("reserve_copper"), f"actions[{index}].reserve_copper", 0, 1000
                ),
            }
            if action_type == "place_conveyor_path":
                normalized_action["preview_token"] = _required_string(
                    action.get("preview_token"), f"actions[{index}].preview_token"
                )
            normalized.append(normalized_action)
        elif action_type == "resupply_turrets":
            ammo = action.get("ammo")
            if not isinstance(ammo, str) or not ammo:
                raise DecisionError(f"actions[{index}].ammo must be a non-empty string.")
            normalized.append({
                "type": action_type,
                "ammo": ammo,
                "max_items": _bounded_integer(action.get("max_items"), f"actions[{index}].max_items", 1, 200),
                "reserve_copper": _bounded_integer(action.get("reserve_copper"), f"actions[{index}].reserve_copper", 0, 1000),
                "below_fraction": _bounded_number(action.get("below_fraction"), f"actions[{index}].below_fraction", 0, 1),
            })
        elif action_type == "command_core_unit":
            mode = action.get("mode")
            if mode not in {"idle", "mine", "supply", "defend_core", "intercept", "repair", "rebuild"}:
                raise DecisionError(
                    f"actions[{index}].mode must be idle, mine, supply, defend_core, intercept, repair, or rebuild."
                )
            normalized_action = {"type": action_type, "mode": mode}
            if mode in {"mine", "supply"}:
                resource = action.get("resource")
                if not isinstance(resource, str) or not resource:
                    raise DecisionError(f"actions[{index}].resource is required for {mode} mode.")
                normalized_action["resource"] = resource
            if mode == "supply":
                normalized_action["target_x"] = _integer(
                    action.get("target_x"), f"actions[{index}].target_x"
                )
                normalized_action["target_y"] = _integer(
                    action.get("target_y"), f"actions[{index}].target_y"
                )
            normalized.append(normalized_action)
        else:
            raise DecisionError(
                f"actions[{index}].type must be place, remove, build_mine_to_core, build_mine_to_target, "
                "route_items, route_items_isolated, clear_item_input_network, upgrade_input_network, inspect_item_route, preview_conveyor_path, place_conveyor_path, route_liquid, connect_power, set_unit_factory_plan, command_units, configure_item_filter, "
                "train_units, upgrade_units, resupply_turrets, or command_core_unit."
            )

    return {
        "reasoning_summary": reasoning[:MAX_REASONING_SUMMARY],
        "world_model": normalized_world_model,
        "strategic_intent": normalized_intent,
        "actions": normalized,
        "wait_seconds": wait_seconds,
    }


def validate_decision_with_action_skips(
    raw: dict[str, Any],
) -> tuple[dict[str, Any], list[dict[str, Any]]]:
    """Preserve valid actions when a repaired model response has one bad action.

    This is deliberately only a structural fallback: it never invents or changes an
    action. Callers can log the rejected action and its exact validation error.
    """
    actions = raw.get("actions", [])
    if not isinstance(actions, list):
        raise DecisionError("actions must be an array.")
    if len(actions) > MAX_ACTIONS:
        raise DecisionError(f"At most {MAX_ACTIONS} actions are allowed.")

    without_actions = dict(raw)
    without_actions["actions"] = []
    normalized = validate_decision(without_actions)
    skipped: list[dict[str, Any]] = []
    for index, action in enumerate(actions):
        candidate = dict(raw)
        candidate["actions"] = [action]
        try:
            validated = validate_decision(candidate)
        except ValueError as error:
            skipped.append({
                "original_index": index,
                "action": action,
                "reason": "invalid_model_action_after_json_repair",
                "message": str(error).replace("actions[0]", f"actions[{index}]"),
            })
            continue
        normalized["actions"].extend(validated["actions"])
    return normalized, skipped


def _short_string_list(value: Any, limit: int) -> list[str]:
    if not isinstance(value, list):
        raise DecisionError("strategy list fields must be arrays.")
    return [item[:MAX_STRATEGY_TEXT] for item in value[:limit] if isinstance(item, str)]


def _integer(value: Any, label: str) -> int:
    parsed = _number(value, label)
    # Cloud models occasionally serialize tile coordinates and counts as numeric
    # strings or decimal-valued numbers.  Round those harmless representation
    # errors here; the game server still validates the resulting tile/action.
    return int(math.floor(parsed + 0.5) if parsed >= 0 else math.ceil(parsed - 0.5))


def _required_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value:
        raise DecisionError(f"{label} must be a non-empty string.")
    return value


def _bounded_integer(value: Any, label: str, minimum: int, maximum: int) -> int:
    parsed = _integer(value, label)
    return max(minimum, min(parsed, maximum))


def _bounded_number(value: Any, label: str, minimum: float, maximum: float) -> float:
    parsed = _number(value, label)
    return max(minimum, min(parsed, maximum))


def _number(value: Any, label: str) -> float:
    if isinstance(value, bool):
        raise DecisionError(f"{label} must be a number.")
    if isinstance(value, str):
        value = value.strip()
        if not value:
            raise DecisionError(f"{label} must be a number.")
        try:
            parsed = float(value)
        except ValueError as error:
            raise DecisionError(f"{label} must be a number.") from error
    elif isinstance(value, (int, float)):
        parsed = float(value)
    else:
        raise DecisionError(f"{label} must be a number.")
    if not math.isfinite(parsed):
        raise DecisionError(f"{label} must be a finite number.")
    return parsed


def _compact_logistics_analysis(raw: Any) -> dict[str, Any]:
    """Keep network-level facts; exact path geometry is fetched through inspect_item_route."""
    if not isinstance(raw, dict):
        return {}
    result = {
        "meaning": raw.get("meaning"),
        "transport_segment_count": raw.get("transport_segment_count"),
        "mixed_transport_segment_count": len(raw.get("mixed_transport_segments", [])),
        "saturated_transport_segment_count": len(raw.get("saturated_transport_segments", [])),
        "loaded_dead_end_segment_count": len(raw.get("loaded_dead_end_segments", [])),
        "open_output_segment_count": len(raw.get("open_output_segments", [])),
        "open_output_segments": raw.get("open_output_segments", [])[:20],
        "transport_components": raw.get("transport_components", [])[:30],
        "selective_sink_bottlenecks": [],
    }
    for bottleneck in raw.get("selective_sink_bottlenecks", []):
        if not isinstance(bottleneck, dict):
            continue
        result["selective_sink_bottlenecks"].append({
            "network_id": bottleneck.get("network_id"),
            "sink": bottleneck.get("sink"),
            "sink_status": bottleneck.get("sink_status"),
            "diagnosis": bottleneck.get("diagnosis"),
            "accepted_items": bottleneck.get("accepted_items", []),
            "observed_items_in_input_network": bottleneck.get("observed_items_in_input_network", []),
            "incompatible_items": bottleneck.get("incompatible_items", []),
            "input_segment_count": bottleneck.get("input_segment_count", 0),
            "contaminated_segment_count": len(bottleneck.get("contaminated_segments", [])),
            "saturated_segment_count": len(bottleneck.get("saturated_segments", [])),
            "saturated_segments": bottleneck.get("saturated_segments", [])[:12],
            "transport_blocks": bottleneck.get("transport_blocks", {}),
            "minimum_nominal_transport_throughput_per_second": bottleneck.get(
                "minimum_nominal_transport_throughput_per_second"
            ),
            "observed_upstream_output_rate_per_second": bottleneck.get(
                "observed_upstream_output_rate_per_second", {}
            ),
            "sink_nominal_item_demand_per_second": bottleneck.get(
                "sink_nominal_item_demand_per_second", {}
            ),
            "direct_inputs": bottleneck.get("direct_inputs", [])[:8],
            "upstream_producers": bottleneck.get("upstream_producers", [])[:12],
            "contamination_sources": bottleneck.get("contamination_sources", [])[:12],
            "resource_reachability": bottleneck.get("resource_reachability", []),
            "first_flow_break": bottleneck.get("first_flow_break"),
            "nearby_misdirected_segments": bottleneck.get("nearby_misdirected_segments", [])[:12],
        })
    result["detail_access"] = (
        "Exact belt tiles and terrain are intentionally omitted here. Use inspect_item_route for the chosen "
        "source and target; its local_path_map is returned in recent_action_results."
    )
    return result


def _pvp_objective_state(state: dict[str, Any]) -> dict[str, Any]:
    rules = state.get("rules") if isinstance(state.get("rules"), dict) else {}
    if not rules.get("pvp"):
        return {}
    core = state.get("core") if isinstance(state.get("core"), dict) else {}
    friendly = [unit for unit in state.get("friendly_units", []) if isinstance(unit, dict)]
    enemies = [unit for unit in state.get("enemy_units", []) if isinstance(unit, dict)]
    friendly_types: dict[str, int] = {}
    enemy_types: dict[str, int] = {}
    for unit in friendly:
        name = str(unit.get("type", "unknown"))
        friendly_types[name] = friendly_types.get(name, 0) + 1
    for unit in enemies:
        name = str(unit.get("type", "unknown"))
        enemy_types[name] = enemy_types.get(name, 0) + 1
    enemy_cores: list[dict[str, Any]] = []
    for enemy in state.get("enemy_teams", []):
        if not isinstance(enemy, dict):
            continue
        team = enemy.get("team") if isinstance(enemy.get("team"), dict) else {}
        cores = enemy.get("cores") if isinstance(enemy.get("cores"), list) else []
        if not cores and isinstance(enemy.get("core"), dict):
            cores = [enemy["core"]]
        for enemy_core in cores:
            if not isinstance(enemy_core, dict):
                continue
            distance = None
            if all(isinstance(value, (int, float)) for value in (
                core.get("x"), core.get("y"), enemy_core.get("x"), enemy_core.get("y"),
            )):
                dx = float(enemy_core["x"]) - float(core["x"])
                dy = float(enemy_core["y"]) - float(core["y"])
                distance = round((dx * dx + dy * dy) ** 0.5, 1)
            enemy_cores.append({
                "team": team.get("name"), "x": enemy_core.get("x"), "y": enemy_core.get("y"),
                "health": enemy_core.get("health"), "max_health": enemy_core.get("max_health"),
                "distance_tiles": distance,
            })
    factories = [
        building for building in state.get("buildings", [])
        if isinstance(building, dict) and "factory" in str(building.get("block", ""))
    ]
    reconstructors = [
        building for building in state.get("buildings", [])
        if isinstance(building, dict) and "reconstructor" in str(building.get("block", ""))
    ]
    active_commands = [
        {"id": unit.get("id"), "type": unit.get("type"), "command": unit.get("unit_command"),
         "target": unit.get("command_target")}
        for unit in friendly if unit.get("unit_command") in {"attack", "attack_move"}
    ]
    stockpile_rts = (
        isinstance(state.get("game_mode_variant"), dict)
        and state["game_mode_variant"].get("id") == "stockpile_rts_pvp"
    )
    victory_condition = "destroy_every_enemy_core"
    control_point_benefits: dict[str, Any] | None = None
    if stockpile_rts:
        self_team = state.get("self_team") if isinstance(state.get("self_team"), dict) else {}
        self_name = str(self_team.get("name", ""))
        points = [
            point for point in state.get("rts_control_points", [])
            if isinstance(point, dict)
        ]
        owner_counts: dict[str, int] = {}
        for point in points:
            owner = str(point.get("owner", "neutral"))
            owner_counts[owner] = owner_counts.get(owner, 0) + 1
        own_points = owner_counts.get(self_name, 0)
        enemy_points = max(
            (count for owner, count in owner_counts.items()
             if owner not in {self_name, "neutral", "None", ""}),
            default=0,
        )
        benefits = (
            state.get("rts_control_benefits")
            if isinstance(state.get("rts_control_benefits"), dict) else {}
        )
        control_point_benefits = {
            "direct_victory_effect": False,
            "total_control_points": len(points),
            "owned_by_self": own_points,
            "most_owned_by_one_opponent": enemy_points,
            "neutral": owner_counts.get("neutral", 0),
            "ownership_by_team": owner_counts,
            "production_and_upgrade_speed_bonus_per_owned_point_fraction": benefits.get(
                "production_and_upgrade_speed_bonus_per_owned_point_fraction", 0.1
            ),
            "friendly_unit_repair_max_health_fraction_per_second_in_owned_radius": benefits.get(
                "friendly_unit_repair_max_health_fraction_per_second_in_owned_radius", 0.01
            ),
            "current_production_and_upgrade_speed_multiplier": benefits.get(
                "current_production_and_upgrade_speed_multiplier", 1.0 + own_points * 0.1
            ),
            "teams": benefits.get("teams", {}),
        }
    factory_rows = []
    for building in factories[:20]:
        row = {
            key: building.get(key) for key in ("block", "x", "y")
        }
        if stockpile_rts:
            row["native_input_and_power_status_ignored"] = True
        else:
            row.update({
                key: building.get(key) for key in (
                    "status", "configured_unit", "unit_build_progress",
                )
            })
        factory_rows.append(row)
    enemy_core_health = sum(
        float(core_row["health"]) for core_row in enemy_cores
        if isinstance(core_row.get("health"), (int, float))
    )
    enemy_core_max_health = sum(
        float(core_row["max_health"]) for core_row in enemy_cores
        if isinstance(core_row.get("max_health"), (int, float))
    )
    enemy_core_damage_fraction = (
        max(0.0, min(1.0, 1.0 - enemy_core_health / enemy_core_max_health))
        if enemy_core_max_health > 0 else None
    )
    return {
        "meaning": "Factual PvP scoreboard and current offensive assets; no build order or readiness score is imposed.",
        "victory_condition": victory_condition,
        "enemy_core_health_progress": {
            "current_total": enemy_core_health,
            "maximum_total": enemy_core_max_health,
            "damage_fraction": enemy_core_damage_fraction,
            "any_direct_victory_damage_observed": bool(
                enemy_core_damage_fraction is not None and enemy_core_damage_fraction > 0
            ),
            "interpretation": (
                "Only enemy-core destruction completes victory; control-point ownership and unit trades are not "
                "direct victory progress."
            ),
        },
        "control_point_benefits": control_point_benefits,
        "own_cores_remaining": len(state.get("cores", [])),
        "enemy_cores_remaining": len(enemy_cores),
        "enemy_cores": enemy_cores,
        "friendly_units_by_type": friendly_types,
        "enemy_units_by_type": enemy_types,
        "commandable_friendly_units": sum(bool(unit.get("commandable")) for unit in friendly),
        "unit_factories": factory_rows,
        "unit_reconstructors": [
            {
                "block": building.get("block"), "x": building.get("x"), "y": building.get("y"),
            }
            for building in reconstructors[:20]
        ],
        "active_offensive_commands": active_commands[:20],
    }


def _compact_unit_stats(stats: Any) -> dict[str, Any]:
    """Keep decision-ready combat facts without copying every weapon mount."""
    if not isinstance(stats, dict):
        return {}
    result = {
        key: stats.get(key)
        for key in (
            "health", "armor", "speed_tiles_per_second", "range_tiles", "estimated_dps",
            "movement", "targets_air", "targets_ground", "can_attack", "can_heal",
            "build_speed", "mine_tier", "mine_speed", "item_capacity", "payload_capacity",
            "ability_count",
        )
        if stats.get(key) is not None
    }
    weapons = [
        weapon for weapon in stats.get("weapons", [])
        if isinstance(weapon, dict)
    ] if isinstance(stats.get("weapons"), list) else []
    if weapons:
        result["weapon_traits"] = {
            "mounts": len(weapons),
            "has_splash": any(float(weapon.get("splash_damage", 0) or 0) > 0 for weapon in weapons),
            "has_homing": any(bool(weapon.get("homing")) for weapon in weapons),
            "has_armor_piercing": any(bool(weapon.get("armor_piercing")) for weapon in weapons),
            "best_building_damage_multiplier": max(
                (float(weapon.get("building_damage_multiplier", 1) or 1) for weapon in weapons),
                default=1.0,
            ),
        }
    return result


def _unit_catalog_table(catalog: dict[str, dict[str, Any]]) -> dict[str, Any]:
    stat_columns = [
        "unit", "health", "armor", "speed_tiles_per_second", "range_tiles", "estimated_dps",
        "movement", "targets_air", "targets_ground", "weapon_traits",
    ]
    rows: list[list[Any]] = []
    for name in sorted(catalog):
        stats = catalog[name]
        rows.append([
            name,
            *[stats.get(column) for column in stat_columns[1:]],
        ])
    return {
        "meaning": "Authoritative compact unit comparison table; decode rows with stat_columns.",
        "stat_columns": stat_columns,
        "rows": rows,
    }


def _stockpile_rts_production_state(
    state: dict[str, Any], block_definitions: list[dict[str, Any]],
) -> dict[str, Any]:
    """Expose direct training, upgrades, and combat facts for finite-stockpile RTS PvP."""
    core = state.get("core") if isinstance(state.get("core"), dict) else {}
    core_items = core.get("items") if isinstance(core.get("items"), dict) else {}
    buildings = [building for building in state.get("buildings", []) if isinstance(building, dict)]
    training_by_position = {
        (queue.get("factory", {}).get("x"), queue.get("factory", {}).get("y")): queue
        for queue in state.get("rts_training_queues", [])
        if isinstance(queue, dict) and isinstance(queue.get("factory"), dict)
        and queue.get("status") == "training"
    }
    upgrade_by_position = {
        (queue.get("reconstructor", {}).get("x"), queue.get("reconstructor", {}).get("y")): queue
        for queue in state.get("rts_upgrade_queues", [])
        if isinstance(queue, dict) and isinstance(queue.get("reconstructor"), dict)
        and queue.get("status") == "upgrading"
    }
    unit_counts: dict[str, int] = {}
    commandable_unit_counts: dict[str, int] = {}
    for unit in state.get("friendly_units", []):
        if not isinstance(unit, dict) or not unit.get("type"):
            continue
        unit_type = str(unit["type"])
        unit_counts[unit_type] = unit_counts.get(unit_type, 0) + 1
        if unit.get("commandable"):
            commandable_unit_counts[unit_type] = commandable_unit_counts.get(unit_type, 0) + 1
    factories: list[dict[str, Any]] = []
    unit_catalog: dict[str, dict[str, Any]] = {}
    for definition in block_definitions:
        plans = definition.get("unit_plans")
        if not isinstance(plans, list) or not plans:
            continue
        existing_instances = [
            building for building in buildings if building.get("block") == definition.get("name")
        ]
        if not definition.get("affordable_now") and not existing_instances:
            continue
        plan_rows = []
        for plan in plans:
            if not isinstance(plan, dict):
                continue
            plan_stats = plan.get("unit_stats") if isinstance(plan.get("unit_stats"), dict) else {}
            if str(plan_stats.get("movement", "")).lower() in {"naval", "water"}:
                continue
            item_cost = plan.get("item_cost") if isinstance(plan.get("item_cost"), dict) else {}
            affordable_counts = [
                int(float(core_items.get(item, 0) or 0) // float(amount))
                for item, amount in item_cost.items()
                if isinstance(amount, (int, float)) and amount > 0
            ]
            plan_rows.append({
                "unit": plan.get("unit"),
                "item_cost_per_unit": item_cost,
                "time_seconds_per_unit": plan.get("time_seconds"),
                "maximum_trainable_from_current_stockpile": min(affordable_counts) if affordable_counts else 0,
            })
            if isinstance(plan.get("unit"), str) and plan_stats:
                unit_catalog.setdefault(plan["unit"], _compact_unit_stats(plan_stats))
        if not plan_rows:
            continue
        factories.append({
            "factory": definition.get("name"),
            "construction_cost_from_core": definition.get("cost", {}),
            "affordable_now": bool(definition.get("affordable_now")),
            "plans": plan_rows,
            "existing_instances": [
                {
                    "facility_id": f"factory:{building.get('x')}:{building.get('y')}",
                    "x": building.get("x"),
                    "y": building.get("y"),
                    "native_input_and_power_status_ignored": True,
                    "available_for_new_order": (
                        (building.get("x"), building.get("y")) not in training_by_position
                    ),
                    "active_queue": training_by_position.get(
                        (building.get("x"), building.get("y"))
                    ),
                }
                for building in existing_instances
            ],
        })

    reconstructors: list[dict[str, Any]] = []
    for definition in block_definitions:
        upgrades = definition.get("unit_upgrades")
        if not isinstance(upgrades, list) or not upgrades:
            continue
        existing_instances = [
            building for building in buildings if building.get("block") == definition.get("name")
        ]
        if not definition.get("affordable_now") and not existing_instances:
            continue
        upgrade_rows: list[dict[str, Any]] = []
        for upgrade in upgrades:
            if not isinstance(upgrade, dict):
                continue
            from_stats = upgrade.get("from_stats") if isinstance(upgrade.get("from_stats"), dict) else {}
            to_stats = upgrade.get("to_stats") if isinstance(upgrade.get("to_stats"), dict) else {}
            if str(from_stats.get("movement", to_stats.get("movement", ""))).lower() in {"naval", "water"}:
                continue
            item_cost = upgrade.get("item_cost") if isinstance(upgrade.get("item_cost"), dict) else {}
            affordable_counts = [
                int(float(core_items.get(item, 0) or 0) // float(amount))
                for item, amount in item_cost.items()
                if isinstance(amount, (int, float)) and amount > 0
            ]
            row = {
                "from_unit": upgrade.get("from_unit"),
                "to_unit": upgrade.get("to_unit"),
                "item_cost_per_unit": item_cost,
                "time_seconds_per_unit": upgrade.get("time_seconds"),
                "maximum_upgradable_from_current_stockpile": min(affordable_counts) if affordable_counts else 0,
                "available_input_units_now": unit_counts.get(str(upgrade.get("from_unit")), 0),
            }
            row["maximum_startable_now_from_resources_and_units"] = min(
                row["maximum_upgradable_from_current_stockpile"],
                row["available_input_units_now"],
            )
            upgrade_rows.append(row)
            if isinstance(row["from_unit"], str) and from_stats:
                unit_catalog.setdefault(row["from_unit"], _compact_unit_stats(from_stats))
            if isinstance(row["to_unit"], str) and to_stats:
                unit_catalog.setdefault(row["to_unit"], _compact_unit_stats(to_stats))
        if not upgrade_rows:
            continue
        reconstructors.append({
            "reconstructor": definition.get("name"),
            "construction_cost_from_core": definition.get("cost", {}),
            "affordable_now": bool(definition.get("affordable_now")),
            "upgrades": upgrade_rows,
            "existing_instances": [
                {
                    "facility_id": f"reconstructor:{building.get('x')}:{building.get('y')}",
                    "x": building.get("x"),
                    "y": building.get("y"),
                    "native_input_power_liquid_and_payload_status_ignored": True,
                    "available_for_new_order": (
                        (building.get("x"), building.get("y")) not in upgrade_by_position
                    ),
                    "active_queue": upgrade_by_position.get(
                        (building.get("x"), building.get("y"))
                    ),
                }
                for building in existing_instances
            ],
        })
    return {
        "meaning": (
            "Authoritative stockpile RTS production facts. Factories train base units and reconstructors upgrade "
            "existing units through only the exact listed pairs. Both commands pay listed item costs from core.items "
            "and use listed standard times without belts, inventory routing, payload conveyors, liquids, or power."
        ),
        "finite_core_stockpile": core_items,
        "current_unit_counts_by_type": unit_counts,
        "commandable_unit_counts_by_type": commandable_unit_counts,
        "unit_catalog": _unit_catalog_table(unit_catalog),
        "factories": factories,
        "reconstructors": reconstructors,
    }


def _is_stockpile_rts_production_block(block: dict[str, Any]) -> bool:
    return bool(block.get("unit_plans")) or bool(block.get("unit_upgrades"))


def _is_stockpile_rts_defense_block(block: dict[str, Any]) -> bool:
    """Keep walls and turrets that the RTS core-stockpile supply action can actually operate."""
    if block.get("defense_kind") == "wall":
        return True
    if block.get("defense_kind") != "turret" and block.get("category") != "turret":
        return False
    combat = block.get("combat") if isinstance(block.get("combat"), dict) else {}
    return any(
        isinstance(ammo, dict) and ammo.get("resource_type") == "item"
        for ammo in combat.get("ammunition", [])
    )


def _stockpile_rts_defense_state(
    state: dict[str, Any], block_definitions: list[dict[str, Any]],
) -> dict[str, Any]:
    """Expose defense choices and route geometry without assigning a defensive strategy."""
    all_buildings = [
        building for building in state.get("buildings", []) if isinstance(building, dict)
    ]
    existing_block_names = {
        str(building.get("block")) for building in all_buildings if building.get("block")
    }
    defense_blocks = [
        block for block in block_definitions
        if _is_stockpile_rts_defense_block(block)
        and (bool(block.get("affordable_now")) or str(block.get("name")) in existing_block_names)
    ]
    defense_names = {str(block.get("name")) for block in defense_blocks if block.get("name")}
    ammo_columns = [
        "resource", "estimated_dps", "splash_damage", "splash_radius_tiles",
        "building_damage_multiplier", "hits_air", "hits_ground", "armor_piercing",
        "homing", "status_effect",
    ]
    structure_columns = [
        "name", "kind", "size", "max_health", "construction_cost", "build_time_seconds",
        "affordable_now", "range_tiles", "minimum_range_tiles", "targets_air",
        "targets_ground", "base_shots_per_second", "ammo",
    ]
    structure_rows: list[list[Any]] = []
    for block in sorted(defense_blocks, key=lambda value: str(value.get("name"))):
        combat = block.get("combat") if isinstance(block.get("combat"), dict) else {}
        ammo_rows = [
            [ammo.get(column) for column in ammo_columns]
            for ammo in combat.get("ammunition", [])
            if isinstance(ammo, dict) and ammo.get("resource_type") == "item"
        ]
        structure_rows.append([
            block.get("name"), block.get("defense_kind"), block.get("size"),
            block.get("max_health"), block.get("cost", {}), block.get("build_time_seconds"),
            bool(block.get("affordable_now")), combat.get("range_tiles"),
            combat.get("minimum_range_tiles"), combat.get("targets_air"),
            combat.get("targets_ground"), combat.get("base_shots_per_second"), ammo_rows,
        ])

    buildings = [
        building for building in all_buildings if str(building.get("block")) in defense_names
    ]
    existing_counts: dict[str, int] = {}
    for building in buildings:
        name = str(building.get("block"))
        existing_counts[name] = existing_counts.get(name, 0) + 1

    raw = state.get("defense_analysis") if isinstance(state.get("defense_analysis"), dict) else {}
    approaches: list[dict[str, Any]] = []
    for direction in raw.get("approach_directions", []):
        if not isinstance(direction, dict):
            continue
        turret_tables: dict[str, Any] = {}
        raw_turrets = direction.get("valid_turret_placement_samples", {})
        if isinstance(raw_turrets, dict):
            for name, placements in raw_turrets.items():
                if str(name) not in defense_names or not isinstance(placements, list):
                    continue
                turret_tables[str(name)] = {
                    "columns": ["x", "y", "ground_route_intervals", "air_route_distance_intervals"],
                    "rows": [
                        [
                            placement.get("x"), placement.get("y"),
                            placement.get("ground_route_intervals", []),
                            placement.get("air_route_distance_intervals", []),
                        ]
                        for placement in placements[:2] if isinstance(placement, dict)
                    ],
                }
        wall_tables: dict[str, Any] = {}
        raw_walls = direction.get("valid_wall_placement_samples", {})
        if isinstance(raw_walls, dict):
            for name, placements in raw_walls.items():
                if str(name) not in defense_names or not isinstance(placements, list):
                    continue
                wall_tables[str(name)] = {
                    "columns": ["x", "y", "steps_from_core"],
                    "rows": [
                        [placement.get("x"), placement.get("y"), placement.get("steps_from_core")]
                        for placement in placements[:2] if isinstance(placement, dict)
                    ],
                }
        approaches.append({
            key: direction.get(key)
            for key in (
                "direction_id", "spawn", "ground_route_reachable", "ground_route_length_tiles",
                "ground_entry_reference", "air_vector_from_core", "straight_line_distance_tiles",
                "existing_turret_route_engagements", "placement_sample_contract",
                "wall_placement_sample_contract",
            )
        } | {
            "valid_turret_placement_samples": turret_tables,
            "valid_wall_placement_samples": wall_tables,
        })

    return {
        "meaning": (
            "Authoritative stockpile RTS defense facts, not a defense score, ratio, quota, or build order. "
            "Construction uses the same team sequential queue as factories. Item-turret ammunition is finite and "
            "moves from core.items only when resupply_turrets is explicitly issued; walls need no ammunition."
        ),
        "structure_catalog": {
            "structure_columns": structure_columns,
            "ammo_columns": ammo_columns,
            "rows": structure_rows,
        },
        "existing_counts": existing_counts,
        "existing_structures": [
            {
                key: building.get(key)
                for key in ("block", "x", "y", "health", "max_health", "items", "status")
                if building.get(key) is not None
            }
            for building in buildings[:100]
        ],
        "existing_turret_status": raw.get("turrets", []),
        "current_enemy_comparison": raw.get("current_enemy_comparison", {}),
        "approach_directions": approaches,
    }


def _stockpile_rts_action_contract(raw: Any) -> dict[str, Any]:
    contract = raw if isinstance(raw, dict) else {}
    shape = contract.get("shape") if isinstance(contract.get("shape"), dict) else {}
    return {
        "endpoint": contract.get("endpoint"),
        "max_actions": contract.get("max_actions"),
        "coordinates": contract.get("coordinates"),
        "placement": contract.get("placement"),
        "shape": {
            "request_id": shape.get("request_id"),
            "expected_episode_id": shape.get("expected_episode_id"),
            "observed_tick": shape.get("observed_tick"),
            "actions": [
                {
                    "type": "place", "block": "ground-factory",
                    "placement_option_id": "production:0", "rotation": 0,
                },
                {"type": "remove", "x": 10, "y": 20},
                {
                    "type": "train_units", "facility_id": "factory:10:20",
                    "unit": "dagger", "count": 5,
                },
                {
                    "type": "upgrade_units", "facility_id": "reconstructor:14:20",
                    "from_unit": "dagger", "to_unit": "mace", "count": 5,
                },
                {
                    "type": "command_units", "mode": "attack_move", "unit": "dagger",
                    "squad_id": "alpha", "max_units": 10, "engagement_radius": 14,
                    "target_x": 30, "target_y": 40, "target_radius": 2,
                },
                {
                    "type": "resupply_turrets", "ammo": "copper", "max_items": 30,
                    "reserve_copper": 0, "below_fraction": 0.5,
                },
                {"type": "command_core_unit", "mode": "defend_core"},
            ],
        },
    }


def _offensive_production_state(
    state: dict[str, Any], block_definitions: list[dict[str, Any]], obtainable_resources: set[str],
) -> dict[str, Any]:
    """Expose unit-production mechanics and observed blockers without choosing a strategy."""
    rules = state.get("rules") if isinstance(state.get("rules"), dict) else {}
    if not rules.get("pvp"):
        return {}
    core = state.get("core") if isinstance(state.get("core"), dict) else {}
    core_items = core.get("items") if isinstance(core.get("items"), dict) else {}
    buildings = [building for building in state.get("buildings", []) if isinstance(building, dict)]
    producer_counts = state.get("production_summary", {}).get("item_producer_counts", {})
    if not isinstance(producer_counts, dict):
        producer_counts = {}
    ore_counts: dict[str, int] = {}
    for ore in state.get("nearby_ores", []):
        if isinstance(ore, dict) and ore.get("item"):
            item = str(ore["item"])
            ore_counts[item] = ore_counts.get(item, 0) + 1

    factories: list[dict[str, Any]] = []
    required_resources: set[str] = set()
    for definition in block_definitions:
        plans = definition.get("unit_plans")
        if not isinstance(plans, list) or not plans:
            continue
        existing = any(building.get("block") == definition.get("name") for building in buildings)
        if (
            not existing
            and _unreachable_cost_resources(definition.get("cost"), core_items, obtainable_resources)
        ):
            continue
        cost = definition.get("cost") if isinstance(definition.get("cost"), dict) else {}
        required_resources.update(str(item) for item in cost)
        construction_shortfall = {
            str(item): max(0, float(amount) - float(core_items.get(item, 0) or 0))
            for item, amount in cost.items() if isinstance(amount, (int, float))
        }
        construction_shortfall = {
            item: amount for item, amount in construction_shortfall.items() if amount > 0
        }
        plan_rows: list[dict[str, Any]] = []
        for plan in plans:
            if not isinstance(plan, dict):
                continue
            item_cost = plan.get("item_cost") if isinstance(plan.get("item_cost"), dict) else {}
            required_resources.update(str(item) for item in item_cost)
            plan_rows.append({
                "unit": plan.get("unit"), "item_cost_per_unit": item_cost,
                "time_seconds_per_unit": plan.get("time_seconds"),
            })
        instances = []
        for building in buildings:
            if building.get("block") != definition.get("name"):
                continue
            instance = {
                key: building.get(key) for key in (
                    "x", "y", "status", "stall_reason", "efficiency", "configured_unit",
                    "unit_build_progress", "items", "consumption_status", "power",
                ) if building.get(key) is not None
            }
            configured = building.get("configured_unit")
            configured_plan = next(
                (plan for plan in plan_rows if plan.get("unit") == configured), None
            )
            if configured_plan is not None:
                requirements = configured_plan.get("item_cost_per_unit", {})
                inventory = building.get("items") if isinstance(building.get("items"), dict) else {}
                instance["configured_plan_item_requirements"] = requirements
                instance["configured_plan_item_shortfall"] = {
                    str(item): max(0, float(amount) - float(inventory.get(item, 0) or 0))
                    for item, amount in requirements.items()
                    if isinstance(amount, (int, float))
                    and float(inventory.get(item, 0) or 0) < float(amount)
                }
            instances.append(instance)
        power_use = 0.0
        for requirement in definition.get("inputs", []):
            if (
                isinstance(requirement, dict) and not requirement.get("optional")
                and requirement.get("resource") == "power"
                and isinstance(requirement.get("usage_per_second"), (int, float))
            ):
                power_use += float(requirement["usage_per_second"])
        factories.append({
            "factory": definition.get("name"),
            "construction_cost_from_core": cost,
            "construction_shortfall_now": construction_shortfall,
            "affordable_now": bool(definition.get("affordable_now")),
            "required_power_per_second_when_producing": power_use,
            "plans": plan_rows,
            "existing_instances": instances,
        })

    resource_evidence = {
        resource: {
            "core_inventory": core_items.get(resource, 0),
            "nearby_ore_tiles_observed": ore_counts.get(resource, 0),
            "observed_producer_count": producer_counts.get(resource, 0),
        }
        for resource in sorted(required_resources)
    }
    return {
        "meaning": (
            "Authoritative unit-production facts, not a build order. Construction cost is paid once from the core; "
            "the configured plan's item cost and power are recurring factory requirements. A plan or attack intent "
            "does not create units unless an existing factory is configured, supplied, powered, and making progress."
        ),
        "factories": factories,
        "required_resource_evidence": resource_evidence,
    }


def _production_network_state(state: dict[str, Any]) -> dict[str, Any]:
    production = state.get("production_summary") if isinstance(state.get("production_summary"), dict) else {}
    nodes = [node for node in production.get("production_buildings", []) if isinstance(node, dict)]
    if not nodes:
        nodes = [
            building for building in state.get("buildings", [])
            if isinstance(building, dict) and (
                building.get("consumption_status") or building.get("configured_unit") is not None
                or any(token in str(building.get("block", "")) for token in (
                    "drill", "press", "smelter", "kiln", "generator", "factory",
                ))
            )
        ]
    logistics = state.get("logistics_analysis") if isinstance(state.get("logistics_analysis"), dict) else {}
    bottlenecks = {
        (entry.get("sink", {}).get("x"), entry.get("sink", {}).get("y")): entry
        for entry in logistics.get("selective_sink_bottlenecks", [])
        if isinstance(entry, dict) and isinstance(entry.get("sink"), dict)
    }
    source_counts: dict[str, dict[str, int]] = {}
    networks: list[dict[str, Any]] = []
    status_counts: dict[str, int] = {}
    for node in nodes:
        status = str(node.get("stall_reason") or node.get("status") or "unknown")
        status_counts[status] = status_counts.get(status, 0) + 1
        outputs = node.get("outputs") if isinstance(node.get("outputs"), dict) else {}
        is_raw_source = "drill" in str(node.get("block", ""))
        if is_raw_source:
            for resource in outputs or {str(node.get("produces", "unknown")): None}:
                bucket = source_counts.setdefault(str(resource), {"total": 0, "active": 0, "stalled": 0})
                bucket["total"] += 1
                bucket["active" if status == "none" or node.get("status") == "active" else "stalled"] += 1
            continue
        if len(networks) >= 40:
            continue
        key = (node.get("x"), node.get("y"))
        bottleneck = bottlenecks.get(key, {})
        unsatisfied_inputs = []
        for requirement in node.get("consumption_status", []):
            if not isinstance(requirement, dict) or requirement.get("satisfied") is not False:
                continue
            unsatisfied_inputs.append({
                key: requirement.get(key) for key in (
                    "resource", "item", "liquid", "amount_per_cycle", "accepted_items", "available",
                ) if requirement.get(key) is not None
            })
        power = node.get("power") if isinstance(node.get("power"), dict) else {}
        facts: list[dict[str, Any]] = []
        if status not in {"none", "active"}:
            facts.append({"kind": "building_stall", "value": status})
        for requirement in unsatisfied_inputs:
            facts.append({"kind": "unsatisfied_input", **requirement})
        incompatible = bottleneck.get("incompatible_items", [])
        if incompatible:
            facts.append({"kind": "incompatible_items_in_input_network", "items": incompatible})
        contamination_sources = bottleneck.get("contamination_sources", [])
        if contamination_sources:
            facts.append({
                "kind": "identified_contamination_sources",
                "sources": contamination_sources[:12],
            })
        saturated = bottleneck.get("saturated_segments", [])
        if saturated:
            facts.append({"kind": "saturated_input_segments", "count": len(saturated), "samples": saturated[:8]})
        if power and float(power.get("satisfaction", 1) or 0) <= 0.001:
            facts.append({"kind": "power_network_unsatisfied", "network_id": power.get("network_id")})
        networks.append({
            "id": f"{node.get('block')}@{node.get('x')},{node.get('y')}",
            "block": node.get("block"), "x": node.get("x"), "y": node.get("y"),
            "status": node.get("status"), "stall_reason": node.get("stall_reason"),
            "efficiency": node.get("efficiency"), "outputs": outputs,
            "craft_time_seconds": node.get("craft_time_seconds"),
            "nominal_item_input_rate_per_second": node.get("nominal_item_input_rate_per_second", {}),
            "observed_output_rate_per_second": node.get("observed_output_rate_per_second", {}),
            "configured_unit": node.get("configured_unit"),
            "unit_build_progress": node.get("unit_build_progress"),
            "stored_items": node.get("items", {}), "unsatisfied_inputs": unsatisfied_inputs,
            "power": power,
            "input_network": {
                "diagnosis": bottleneck.get("diagnosis"),
                "accepted_items": bottleneck.get("accepted_items", []),
                "observed_items": bottleneck.get("observed_items_in_input_network", []),
                "incompatible_items": incompatible,
                "direct_inputs": bottleneck.get("direct_inputs", []),
                "upstream_producers": bottleneck.get("upstream_producers", []),
                "contamination_sources": contamination_sources[:12],
                "resource_reachability": bottleneck.get("resource_reachability", []),
                "first_flow_break": bottleneck.get("first_flow_break"),
                "nearby_misdirected_segments": bottleneck.get("nearby_misdirected_segments", [])[:12],
                "input_segment_count": bottleneck.get("input_segment_count", 0),
                "transport_blocks": bottleneck.get("transport_blocks", {}),
                "minimum_nominal_transport_throughput_per_second": bottleneck.get(
                    "minimum_nominal_transport_throughput_per_second"
                ),
                "observed_upstream_output_rate_per_second": bottleneck.get(
                    "observed_upstream_output_rate_per_second", {}
                ),
                "sink_nominal_item_demand_per_second": bottleneck.get(
                    "sink_nominal_item_demand_per_second", {}
                ),
                "saturated_segments": saturated[:12],
            },
            "observed_failure_facts": facts,
        })
    networks.sort(key=lambda node: (node.get("stall_reason") in {None, "none"}, str(node.get("id"))))
    return {
        "meaning": "Sink-centered factual production summary. Exact belt geometry is available on demand through inspect_item_route.",
        "source_counts_by_resource": source_counts,
        "building_status_counts": status_counts,
        "networks": networks,
        "additional_production_buildings_omitted": production.get("production_buildings_omitted", 0),
    }


def _compact_defense_analysis(raw: Any) -> dict[str, Any]:
    if not isinstance(raw, dict):
        return {}
    result = {
        key: raw.get(key)
        for key in (
            "meaning", "totals", "turrets", "current_enemy_comparison",
        )
    }
    directions: list[dict[str, Any]] = []
    for direction in raw.get("approach_directions", []):
        if not isinstance(direction, dict):
            continue
        compact_direction = {
            key: direction.get(key)
            for key in (
                "direction_id", "spawn", "ground_route_reachable", "ground_route_length_tiles",
                "ground_entry_reference", "ground_route", "air_vector_from_core",
                "straight_line_distance_tiles", "wall_evidence", "engagement_interval_columns",
                "existing_turret_route_engagements", "placement_sample_contract",
            )
        }
        candidate_tables: dict[str, Any] = {}
        candidates = direction.get("valid_turret_placement_samples", {})
        if isinstance(candidates, dict):
            columns = ["x", "y", "ground_route_intervals", "air_route_distance_intervals"]
            for block, placements in candidates.items():
                rows: list[list[Any]] = []
                for placement in placements if isinstance(placements, list) else []:
                    if not isinstance(placement, dict):
                        continue
                    rows.append([
                        placement.get("x"), placement.get("y"),
                        placement.get("ground_route_intervals", []),
                        placement.get("air_route_distance_intervals", []),
                    ])
                candidate_tables[str(block)] = {"columns": columns, "rows": rows}
        compact_direction["valid_turret_placement_samples"] = candidate_tables
        directions.append(compact_direction)
    result["approach_directions"] = directions
    return result


def compact_state(state: dict[str, Any]) -> dict[str, Any]:
    """Keep only decision-relevant fields so cloud latency does not scale with map detail."""
    stockpile_rts = (
        isinstance(state.get("game_mode_variant"), dict)
        and state["game_mode_variant"].get("id") == "stockpile_rts_pvp"
    )
    compact = {
        key: state.get(key)
        for key in (
            "episode_id", "result", "self_team", "wave", "wave_time_remaining_seconds",
            "map", "rules", "game_mode_variant", "pvp_fairness", "core", "cores", "core_defender", "defense_supply", "wave_forecast", "threat_summary", "enemy_spawns",
            "rts_construction_queue", "rts_training_queues", "rts_upgrade_queues",
            "rts_battlefield", "rts_control_points", "rts_control_benefits", "rts_squads", "recent_unit_command_receipts",
            "recent_combat_losses",
            "power_networks", "power_node_topology", "action_contract", "infrastructure_backlog", "recent_defense_outcomes",
        )
    }
    if stockpile_rts:
        for key in (
            "power_networks", "power_node_topology", "defense_supply",
            "infrastructure_backlog", "recent_defense_outcomes",
        ):
            compact.pop(key, None)
        core = state.get("core") if isinstance(state.get("core"), dict) else {}
        compact["core"] = {
            key: core.get(key)
            for key in ("x", "y", "size", "health", "max_health", "items")
            if core.get(key) is not None
        }
        compact["cores"] = [
            {
                key: core_row.get(key)
                for key in ("x", "y", "size", "health", "max_health")
                if core_row.get(key) is not None
            }
            for core_row in state.get("cores", []) if isinstance(core_row, dict)
        ]
        fairness = state.get("pvp_fairness") if isinstance(state.get("pvp_fairness"), dict) else {}
        compact["pvp_fairness"] = {
            key: fairness.get(key)
            for key in (
                "applied", "method", "source_core", "target_core", "compact_visible_arena",
                "core_separation_tiles", "coordinate_transform", "terrain_mismatch_pairs_after_copy",
                "resource_mismatch_pairs_after_copy", "equal_core_stockpile",
            )
            if fairness.get(key) is not None
        }
        compact["action_contract"] = _stockpile_rts_action_contract(state.get("action_contract"))
        queue_summary: dict[str, Any] = {}
        for key, active_status in (
            ("rts_training_queues", "training"),
            ("rts_upgrade_queues", "upgrading"),
        ):
            queues = [queue for queue in state.get(key, []) if isinstance(queue, dict)]
            active = [queue for queue in queues if queue.get("status") == active_status]
            completed = [queue for queue in queues if queue.get("status") == "completed"]
            compact[key] = active + completed[-3:]
            queue_summary[key] = {
                "active_count": len(active),
                "completed_count": len(completed),
                "completed_units_total": sum(
                    int(queue.get("completed", 0) or 0) for queue in completed
                ),
                "recent_completed_rows_included": min(3, len(completed)),
            }
        compact["rts_queue_history_summary"] = queue_summary
        construction = [
            row for row in state.get("rts_construction_queue", []) if isinstance(row, dict)
        ]
        construction_active = [
            row for row in construction if row.get("status") not in {
                "completed", "cancelled_invalid_at_completion", "cancelled"
            }
        ]
        construction_finished = [row for row in construction if row not in construction_active]
        compact["rts_construction_queue"] = construction_active + construction_finished[-3:]
        compact["rts_queue_history_summary"]["rts_construction_queue"] = {
            "active_count": len(construction_active),
            "finished_count": len(construction_finished),
            "recent_finished_rows_included": min(3, len(construction_finished)),
        }
        compact["rts_squads"] = [
            squad for squad in state.get("rts_squads", []) if isinstance(squad, dict)
        ]
        defender = state.get("core_defender")
        if isinstance(defender, dict):
            compact["core_defender"] = {
                key: defender.get(key) for key in (
                    "enabled", "active", "id", "type", "x", "y", "health", "max_health",
                    "weapon_range_tiles", "task", "task_age_seconds", "task_progress",
                    "carried_item", "carried_amount",
                ) if defender.get(key) is not None
            }
    compact["pvp_objective"] = _pvp_objective_state(state)
    if not stockpile_rts:
        compact["production_networks"] = _production_network_state(state)
        raw_production = state.get("production_summary") if isinstance(state.get("production_summary"), dict) else {}
        compact["production_summary"] = {
            "item_producer_counts": raw_production.get("item_producer_counts", {}),
            "stalled_buildings": raw_production.get("stalled_buildings", []),
        }
        compact["logistics_analysis"] = _compact_logistics_analysis(state.get("logistics_analysis"))
        compact["defense_analysis"] = _compact_defense_analysis(state.get("defense_analysis"))

    damaged: list[dict[str, Any]] = []
    damaged_counts: dict[str, int] = {}
    for building in state.get("buildings", []):
        if not isinstance(building, dict):
            continue
        health, maximum = building.get("health"), building.get("max_health")
        if not isinstance(health, (int, float)) or not isinstance(maximum, (int, float)) or maximum <= 0:
            continue
        if health >= maximum - 1:
            continue
        block = str(building.get("block", "unknown"))
        damaged_counts[block] = damaged_counts.get(block, 0) + 1
        damaged.append({
            "block": block, "x": building.get("x"), "y": building.get("y"),
            "health": health, "max_health": maximum, "health_fraction": health / maximum,
        })
    damaged.sort(key=lambda building: building["health_fraction"])
    compact["maintenance_summary"] = {
        "damaged_count": len(damaged),
        "counts_by_block": damaged_counts,
        "most_damaged": damaged[:40],
        "additional_damaged_omitted": max(0, len(damaged) - 40),
    }

    raw_logistics = state.get("logistics_analysis", {})
    raw_topology = raw_logistics.get("transport_topology", []) if isinstance(raw_logistics, dict) else []
    transport_coordinates = {
        (segment.get("x"), segment.get("y"))
        for segment in raw_topology
        if isinstance(segment, dict)
    }
    decision_buildings = [
        building for building in state.get("buildings", [])
        if isinstance(building, dict)
        and (building.get("x"), building.get("y")) not in transport_coordinates
        and not str(building.get("block", "")).startswith("core-")
    ]
    if stockpile_rts:
        compact["buildings"] = [
            {
                "block": building.get("block"), "x": building.get("x"), "y": building.get("y"),
                "rotation": building.get("rotation"), "health": building.get("health"),
                "max_health": building.get("max_health"),
            }
            for building in decision_buildings[:140]
        ]
    else:
        compact["buildings"] = [_compact_building(building) for building in decision_buildings[:140]]
    compact["friendly_units"] = [
        {key: unit.get(key) for key in (
            "id", "type", "x", "y", "health", "max_health", "commandable", "unit_command", "command_target"
        )}
        for unit in state.get("friendly_units", [])[:20]
        if isinstance(unit, dict)
    ]
    compact["enemy_units"] = [
        {key: unit.get(key) for key in ("id", "type", "team", "x", "y", "health", "max_health")}
        for unit in state.get("enemy_units", [])[:40]
        if isinstance(unit, dict)
    ]
    compact["enemy_teams"] = []
    for enemy in state.get("enemy_teams", []):
        if not isinstance(enemy, dict):
            continue
        building_rows = []
        for building in enemy.get("buildings", [])[:180]:
            if not isinstance(building, dict):
                continue
            if stockpile_rts:
                building_rows.append([
                    building.get("block"), building.get("x"), building.get("y"),
                    building.get("health"), building.get("max_health"),
                ])
            else:
                building_rows.append([
                    building.get("block"), building.get("x"), building.get("y"),
                    building.get("rotation"), building.get("health"), building.get("max_health"),
                    building.get("items", {}), building.get("status"),
                ])
        enemy_cores = []
        for enemy_core in enemy.get("cores", []):
            if not isinstance(enemy_core, dict):
                continue
            enemy_cores.append({
                key: enemy_core.get(key)
                for key in ("x", "y", "size", "health", "max_health")
                if enemy_core.get(key) is not None
            } if stockpile_rts else enemy_core)
        enemy_row = {
            "team": enemy.get("team"),
            "core": enemy_cores[0] if enemy_cores else None,
            "cores": enemy_cores,
            "building_table": {
                "columns": (
                    ["block", "x", "y", "health", "max_health"] if stockpile_rts
                    else ["block", "x", "y", "rotation", "health", "max_health", "items", "status"]
                ),
                "rows": building_rows,
            },
        }
        if not stockpile_rts:
            enemy_row["units"] = [
                {key: unit.get(key) for key in ("type", "x", "y", "health", "max_health")}
                for unit in enemy.get("units", [])[:40] if isinstance(unit, dict)
            ]
        compact["enemy_teams"].append(enemy_row)

    grouped_ores: dict[str, list[dict[str, Any]]] = {}
    for ore in state.get("nearby_ores", []):
        if not isinstance(ore, dict):
            continue
        item = str(ore.get("item", "unknown"))
        bucket = grouped_ores.setdefault(item, [])
        if len(bucket) < 16:
            bucket.append({"x": ore.get("x"), "y": ore.get("y")})
    compact["nearby_ores_by_item"] = grouped_ores

    block_definitions = [
        block for block in state.get("available_blocks", []) if isinstance(block, dict)
    ]
    if stockpile_rts:
        block_definitions = [
            block for block in block_definitions
            if _is_stockpile_rts_production_block(block)
            or _is_stockpile_rts_defense_block(block)
        ]
    if stockpile_rts:
        production_block_names = {
            str(block.get("name")) for block in block_definitions
            if _is_stockpile_rts_production_block(block)
        }
        merged_production_options: dict[tuple[Any, Any], dict[str, Any]] = {}
        for block, samples in state.get("valid_placement_samples", {}).items():
            if block not in production_block_names or not isinstance(samples, list):
                continue
            for sample in samples:
                if not isinstance(sample, dict):
                    continue
                coordinate = (sample.get("x"), sample.get("y"))
                option = merged_production_options.setdefault(coordinate, {
                    key: sample.get(key) for key in (
                        "x", "y", "distance2_to_core", "distance_tiles", "distance_band",
                        "direction_sector", "relative_to_core",
                    )
                })
                option.setdefault("compatible_structures", []).append(block)
        placement_options = list(merged_production_options.values())
        episode_seed = str(state.get("episode_id", "stockpile-rts"))
        placement_options.sort(key=lambda option: hashlib.sha256(
            (
                f"{episode_seed}:{option.get('distance_band')}:"
                f"{option.get('direction_sector')}"
            ).encode("utf-8")
        ).digest())
        # Preserve spatial choice while bounding a static catalog that is resent every turn.
        placement_options = placement_options[:24]
        for index, option in enumerate(placement_options):
            option["placement_option_id"] = f"production:{index}"
        compact["rts_production_placement_options"] = {
            "meaning": (
                "Currently valid, spatially distributed factory or reconstructor anchors spanning distance bands "
                "and directions. Rows are options, not a ranking or build instruction; compatible_structures "
                "lists exact legality. Decode each row with columns."
            ),
            "distance_band_order": "0=near_core, increasing values are progressively farther from the core",
            "direction_sector_order": "0=east, then counter-clockwise in 45-degree steps",
            "ordering": "deterministically shuffled per episode; list order carries no preference",
            "columns": [
                "placement_option_id", "x", "y", "distance_tiles", "distance_band", "direction_sector",
                "compatible_structures",
            ],
            "rows": [
                [
                    option.get("placement_option_id"), option.get("x"), option.get("y"), option.get("distance_tiles"),
                    option.get("distance_band"), option.get("direction_sector"),
                    option.get("compatible_structures", []),
                ]
                for option in placement_options
            ],
        }
    else:
        compact["valid_placement_samples"] = {
            block: [
                {key: sample.get(key) for key in ("x", "y", "distance2_to_core", "produces", "ore_tiles")}
                for sample in samples[:4]
                if isinstance(sample, dict)
            ]
            for block, samples in state.get("valid_placement_samples", {}).items()
            if isinstance(samples, list)
        }
    renewable_resources = _currently_obtainable_resources(state, block_definitions)
    if stockpile_rts:
        compact["nearby_ores_by_item"] = {}
        compact["offensive_production"] = _stockpile_rts_production_state(state, block_definitions)
        compact["rts_defense"] = _stockpile_rts_defense_state(state, block_definitions)
    else:
        compact["currently_obtainable_resources"] = sorted(renewable_resources)
        compact["offensive_production"] = _offensive_production_state(
            state, block_definitions, renewable_resources
        )
    existing_blocks = {
        str(building.get("block"))
        for building in state.get("buildings", [])
        if isinstance(building, dict) and building.get("block")
    }
    core_items = state.get("core", {}).get("items", {})
    if not isinstance(core_items, dict):
        core_items = {}

    compact_blocks: list[dict[str, Any]] = []
    deferred_turrets: list[dict[str, Any]] = []
    capability_entries: list[dict[str, Any]] = []
    pvp_mode = bool(isinstance(state.get("rules"), dict) and state["rules"].get("pvp"))
    for block in block_definitions:
        is_turret = block.get("category") == "turret"
        blocked_by = _unreachable_cost_resources(block.get("cost"), core_items, renewable_resources)
        if is_turret and blocked_by:
            deferred_turrets.append({
                "name": block.get("name"),
                "cost": block.get("cost", {}),
                "blocked_by_resources": blocked_by,
            })
        if pvp_mode and blocked_by and block.get("name") not in existing_blocks:
            # Omit capabilities whose construction resources do not exist in this
            # match's reachable economy. This removes cross-planet catalog noise,
            # not a strategic option the agent could presently realize.
            continue
        include_detail = (
            bool(block.get("affordable_now"))
            or block.get("name") in existing_blocks
        )
        capability = _compact_capability(block)
        if include_detail:
            capability = {
                key: capability.get(key)
                for key in ("name", "category", "cost", "affordable_now")
            }
        capability_entries.append(capability)
        if include_detail:
            block_detail = _compact_block_definition(block)
            if stockpile_rts:
                block_detail.pop("unit_plans", None)
                block_detail.pop("unit_upgrades", None)
            if not (stockpile_rts and _is_stockpile_rts_defense_block(block)):
                compact_blocks.append(block_detail)

    if stockpile_rts:
        # RTS production/defense catalogs above already contain all legal choices.
        compact["available_blocks"] = []
        compact["capability_catalog"] = {
            "meaning": "See offensive_production and rts_defense; duplicate generic catalogs are omitted."
        }
    else:
        compact["available_blocks"] = compact_blocks
        compact["capability_catalog"] = _indexed_capability_catalog(capability_entries)
    if not stockpile_rts:
        compact["deferred_turrets"] = deferred_turrets
    core = state.get("core") if isinstance(state.get("core"), dict) else {}
    threat = state.get("threat_summary") if isinstance(state.get("threat_summary"), dict) else {}
    defense = state.get("defense_analysis") if isinstance(state.get("defense_analysis"), dict) else {}
    forecast = state.get("wave_forecast") if isinstance(state.get("wave_forecast"), dict) else {}
    defender = state.get("core_defender") if isinstance(state.get("core_defender"), dict) else {}
    compact["immediate_evidence_digest"] = {
        "meaning": "Small duplicate digest of time-sensitive raw evidence; it is not a threat rating or strategy rule.",
        "wave": state.get("wave"),
        "wave_time_remaining_seconds": state.get("wave_time_remaining_seconds"),
        "core_health": core.get("health"),
        "core_max_health": core.get("max_health"),
        "current_enemies": {
            key: threat.get(key) for key in (
                "enemy_units", "enemies_within_30_tiles", "enemies_within_20_tiles",
                "enemies_within_12_tiles", "nearest_enemy_distance_tiles",
            )
        },
        "scheduled_waves": [
            {key: wave.get(key) for key in (
                "wave", "is_current", "total_units", "ground_units", "air_units",
                "base_health_total", "shield_total",
            )}
            for wave in forecast.get("waves", [])[:3] if isinstance(wave, dict)
        ],
        "defense_totals": defense.get("totals", {}),
        "current_unit_task": defender.get("task"),
        "current_unit_task_effect": defender.get("current_task_effect", {}),
        "recent_defense_outcomes": state.get("recent_defense_outcomes", {}),
    }
    if stockpile_rts:
        compact["immediate_evidence_digest"].pop("scheduled_waves", None)
        compact["immediate_evidence_digest"].pop("recent_defense_outcomes", None)
    return compact


def _compact_block_definition(block: dict[str, Any]) -> dict[str, Any]:
    result = {
        key: block.get(key)
        for key in (
            "name", "category", "size", "cost", "build_time_seconds", "role", "affordable_now",
            "max_health", "defense_kind",
            "item_capacity", "liquid_capacity", "accepts_items", "outputs_items",
            "has_power", "consumes_power", "outputs_power", "inputs", "recipe", "drill",
            "power_output_per_second", "power_connection", "item_throughput_per_second", "fuel", "combat", "range_tiles", "compatible_ammo",
        )
        if block.get(key) is not None
    }
    plans = block.get("unit_plans")
    if isinstance(plans, list) and plans:
        result["unit_plans"] = [
            {
                key: plan.get(key)
                for key in ("unit", "item_cost", "time_seconds")
                if plan.get(key) is not None
            }
            for plan in plans if isinstance(plan, dict)
        ]
    upgrades = block.get("unit_upgrades")
    if isinstance(upgrades, list) and upgrades:
        result["unit_upgrades"] = [
            {
                key: upgrade.get(key)
                for key in ("from_unit", "to_unit", "item_cost", "time_seconds")
                if upgrade.get(key) is not None
            }
            for upgrade in upgrades if isinstance(upgrade, dict)
        ]
    return result


def _compact_capability(block: dict[str, Any]) -> dict[str, Any]:
    """Keep the complete option horizon as small causal edges; details stay in available_blocks."""
    result: dict[str, Any] = {
        "name": block.get("name"),
        "category": block.get("category"),
        "cost": block.get("cost", {}),
        "affordable_now": bool(block.get("affordable_now")),
    }
    recipe = block.get("recipe")
    if isinstance(recipe, dict):
        outputs = recipe.get("item_outputs_per_craft")
        liquids = recipe.get("liquid_outputs_per_second")
        if outputs:
            result["item_outputs"] = outputs
        if liquids:
            result["liquid_outputs_per_second"] = liquids
    item_inputs: dict[str, Any] = {}
    liquid_inputs: dict[str, Any] = {}
    accepted_item_fuels: list[str] = []
    accepted_liquids: list[str] = []
    power_use = 0.0
    requires_special_input = False
    for requirement in block.get("inputs", []) if isinstance(block.get("inputs"), list) else []:
        if not isinstance(requirement, dict) or requirement.get("optional"):
            continue
        resource = requirement.get("resource")
        amounts = requirement.get("amount_per_cycle")
        if resource == "items" and isinstance(amounts, dict):
            item_inputs.update(amounts)
        elif resource == "item_fuel":
            accepted_item_fuels.extend(str(item) for item in requirement.get("accepted_items", []) if item)
        elif resource in {"liquid", "liquids"} and requirement.get("liquid"):
            liquid_inputs[str(requirement["liquid"])] = requirement.get("amount_per_second")
        elif resource == "liquid_filter":
            accepted_liquids.extend(str(item) for item in requirement.get("accepted_liquids", []) if item)
        elif resource == "power" and isinstance(requirement.get("usage_per_second"), (int, float)):
            power_use += float(requirement["usage_per_second"])
        elif resource == "special":
            requires_special_input = True
    if item_inputs:
        result["item_inputs"] = item_inputs
    if liquid_inputs:
        result["liquid_inputs_per_second"] = liquid_inputs
    if accepted_item_fuels:
        result["accepted_item_fuels"] = sorted(set(accepted_item_fuels))
    if accepted_liquids:
        result["accepted_liquids"] = sorted(set(accepted_liquids))
    if power_use:
        result["power_use_per_second"] = power_use
    if requires_special_input:
        result["requires_configured_plan"] = True
    drill = block.get("drill")
    if isinstance(drill, dict):
        result["drill_tier"] = drill.get("tier")
        result["base_drill_time_seconds"] = drill.get("base_drill_time_seconds")
    if block.get("power_output_per_second") is not None:
        result["power_output_per_second"] = block["power_output_per_second"]
    if isinstance(block.get("power_connection"), dict):
        result["power_connection"] = block["power_connection"]
    combat = block.get("combat")
    if isinstance(combat, dict):
        ammo_dps: dict[str, Any] = {}
        ammo_splash: dict[str, Any] = {}
        for ammo in combat.get("ammunition", []):
            if not isinstance(ammo, dict) or not ammo.get("resource"):
                continue
            resource = str(ammo["resource"])
            ammo_dps[resource] = ammo.get("estimated_dps")
            if isinstance(ammo.get("splash_damage"), (int, float)) and ammo.get("splash_damage") != 0:
                ammo_splash[resource] = ammo.get("splash_damage")
        result["combat_summary"] = {
            "range_tiles": combat.get("range_tiles"),
            "targets_air": combat.get("targets_air"),
            "targets_ground": combat.get("targets_ground"),
            "ammo_dps": ammo_dps,
        }
        if ammo_splash:
            result["combat_summary"]["ammo_splash_damage"] = ammo_splash
    plans = block.get("unit_plans")
    if isinstance(plans, list) and plans:
        result["unit_plans"] = [
            {key: plan.get(key) for key in ("unit", "item_cost", "time_seconds") if plan.get(key) is not None}
            for plan in plans if isinstance(plan, dict)
        ]
    upgrades = block.get("unit_upgrades")
    if isinstance(upgrades, list) and upgrades:
        result["unit_upgrades"] = [
            {
                key: upgrade.get(key)
                for key in ("from_unit", "to_unit", "item_cost", "time_seconds")
                if upgrade.get(key) is not None
            }
            for upgrade in upgrades if isinstance(upgrade, dict)
        ]
    return result


def _indexed_capability_catalog(entries: list[dict[str, Any]]) -> dict[str, Any]:
    """Column-oriented catalog avoids repeating verbose JSON keys for every block."""
    detail_keys = {
        "io": "item_outputs", "lo": "liquid_outputs_per_second", "ii": "item_inputs",
        "li": "liquid_inputs_per_second", "fuels": "accepted_item_fuels",
        "liquids": "accepted_liquids", "p_use": "power_use_per_second",
        "plan": "requires_configured_plan", "tier": "drill_tier",
        "drill_s": "base_drill_time_seconds", "p_out": "power_output_per_second",
        "p_link": "power_connection", "combat": "combat_summary", "units": "unit_plans",
        "upgrades": "unit_upgrades",
    }
    rows: list[list[Any]] = []
    for entry in entries:
        details = {
            short: entry.get(field)
            for short, field in detail_keys.items()
            if entry.get(field) not in (None, {}, [], False)
        }
        rows.append([
            entry.get("name"), entry.get("category"), entry.get("cost", {}),
            bool(entry.get("affordable_now")), details,
        ])
    return {
        "meaning": "Complete current-game option horizon in compact rows. Per-block details appear in available_blocks only when affordable or already built.",
        "columns": ["name", "category", "cost", "affordable_now", "details"],
        "details_legend": detail_keys,
        "rows": rows,
    }


def _currently_obtainable_resources(
    state: dict[str, Any], block_definitions: list[dict[str, Any]],
) -> set[str]:
    """Resources observed directly or reachable through one currently feasible crafting step."""
    obtainable: set[str] = set()
    for ore in state.get("nearby_ores", []):
        if isinstance(ore, dict) and ore.get("item"):
            obtainable.add(str(ore["item"]))

    production = state.get("production_summary", {})
    producer_counts = production.get("item_producer_counts", {}) if isinstance(production, dict) else {}
    if isinstance(producer_counts, dict):
        obtainable.update(str(item) for item, count in producer_counts.items() if isinstance(count, (int, float)) and count > 0)

    definitions_by_name = {
        str(block.get("name")): block for block in block_definitions if block.get("name")
    }
    for building in state.get("buildings", []):
        if not isinstance(building, dict):
            continue
        if building.get("produces"):
            obtainable.add(str(building["produces"]))
        definition = definitions_by_name.get(str(building.get("block", "")), {})
        recipe = definition.get("recipe", {}) if isinstance(definition, dict) else {}
        outputs = recipe.get("item_outputs_per_craft", {}) if isinstance(recipe, dict) else {}
        if isinstance(outputs, dict):
            obtainable.update(str(item) for item in outputs)

    core_items = state.get("core", {}).get("items", {})
    if not isinstance(core_items, dict):
        core_items = {}
    source_resources = obtainable | {
        str(item) for item, amount in core_items.items()
        if isinstance(amount, (int, float)) and amount > 0
    }
    # One prospective crafting edge is "near term". Chaining every theoretical
    # recipe to closure makes distant late-game content look immediately relevant
    # and crowds out the live economy and combat state.
    for definition in block_definitions:
        recipe = definition.get("recipe", {})
        outputs = recipe.get("item_outputs_per_craft", {}) if isinstance(recipe, dict) else {}
        if not isinstance(outputs, dict) or not outputs:
            continue
        if _unreachable_cost_resources(definition.get("cost"), core_items, obtainable):
            continue
        if not _recipe_inputs_reachable(definition.get("inputs"), source_resources):
            continue
        obtainable.update(str(item) for item in outputs)
    return obtainable


def _recipe_inputs_reachable(inputs: Any, source_resources: set[str]) -> bool:
    if not isinstance(inputs, list):
        return True
    for requirement in inputs:
        if not isinstance(requirement, dict) or requirement.get("optional"):
            continue
        resource_type = requirement.get("resource")
        if resource_type == "items":
            amounts = requirement.get("amount_per_cycle", {})
            if isinstance(amounts, dict) and any(str(item) not in source_resources for item in amounts):
                return False
        elif resource_type == "item_fuel":
            accepted = requirement.get("accepted_items", [])
            if isinstance(accepted, list) and accepted and not any(str(item) in source_resources for item in accepted):
                return False
        elif resource_type in {"liquid", "liquids", "liquid_filter", "special"}:
            return False
    return True


def _unreachable_cost_resources(
    cost: Any, core_items: dict[str, Any], obtainable: set[str],
) -> list[str]:
    if not isinstance(cost, dict):
        return []
    blocked: list[str] = []
    for resource, required in cost.items():
        if not isinstance(required, (int, float)):
            continue
        available = core_items.get(resource, 0)
        available_amount = float(available) if isinstance(available, (int, float)) else 0.0
        if available_amount < float(required) and str(resource) not in obtainable:
            blocked.append(str(resource))
    return blocked


def _compact_building(building: dict[str, Any]) -> dict[str, Any]:
    keys = (
        "block", "x", "y", "rotation", "health", "max_health", "efficiency", "items", "item_capacity",
        "liquids", "liquid_capacity", "power", "produces", "ore_tiles", "last_drill_speed",
        "craft_progress", "warmup", "unit_plan_index", "unit_build_progress", "configured_unit",
        "ammo_fraction", "compatible_ammo", "output_to", "item_filter", "filter_behavior",
    )
    result = {key: building.get(key) for key in keys if building.get(key) is not None}
    health, maximum = building.get("health"), building.get("max_health")
    if isinstance(health, (int, float)) and isinstance(maximum, (int, float)) and health >= maximum - 1:
        result.pop("health", None)
        result.pop("max_health", None)
    if building.get("efficiency") == 1.0 and building.get("status") in {None, "active"}:
        result.pop("efficiency", None)
    consumption = building.get("consumption_status")
    if consumption:
        result["consumption_status"] = consumption
    diagnostic = (
        bool(consumption)
        or building.get("produces") is not None
        or building.get("craft_progress") is not None
        or building.get("ammo_fraction") is not None
        or building.get("power") is not None
        or building.get("item_filter") is not None
    )
    if diagnostic:
        result["status"] = building.get("status")
        result["stall_reason"] = building.get("stall_reason")
        if building.get("adjacent"):
            result["adjacent"] = building["adjacent"]
    return result
