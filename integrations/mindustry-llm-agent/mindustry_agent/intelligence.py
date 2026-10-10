from __future__ import annotations

from copy import deepcopy
import math
from typing import Any


UNIT_VISION_RADIUS_TILES = 20.0
CORE_VISION_RADIUS_TILES = 22.0
BUILDING_VISION_RADIUS_TILES = 10.0
STALE_UNIT_TRACK_SECONDS = 15.0


def _number(value: Any) -> float | None:
    return float(value) if isinstance(value, (int, float)) else None


def _position(value: Any) -> tuple[float, float] | None:
    if not isinstance(value, dict):
        return None
    x, y = _number(value.get("x")), _number(value.get("y"))
    return (x, y) if x is not None and y is not None else None


class StrategicIntelligenceTracker:
    """Create an episode-local fog-of-war observation for the strategic model.

    The game server remains authoritative and unfiltered for action validation. This
    tracker only limits what reaches the model, and its last-known contacts disappear
    with the runner process at the end of the episode.
    """

    def __init__(self) -> None:
        self._unit_tracks: dict[tuple[str, int], dict[str, Any]] = {}
        self._building_tracks: dict[tuple[str, str, int, int], dict[str, Any]] = {}
        self._observed_enemy_losses: dict[tuple[str, int], dict[str, Any]] = {}
        self._observed_enemy_combat: dict[tuple[str, int], dict[str, Any]] = {}

    @staticmethod
    def applies(state: dict[str, Any]) -> bool:
        variant = state.get("game_mode_variant")
        rules = state.get("rules")
        return (
            isinstance(variant, dict)
            and variant.get("id") == "stockpile_rts_pvp"
            and isinstance(rules, dict)
            and rules.get("pvp") is True
        )

    def observe(self, state: dict[str, Any]) -> dict[str, Any]:
        if not self.applies(state):
            return state

        observed = deepcopy(state)
        tick = float(state.get("tick", 0) or 0)
        self_team = state.get("self_team") if isinstance(state.get("self_team"), dict) else {}
        self_name = str(self_team.get("name", ""))
        sources = self._vision_sources(state)

        visible_units = [
            unit for unit in state.get("enemy_units", [])
            if isinstance(unit, dict) and self._is_visible(unit, sources)
        ]
        visible_unit_keys: set[tuple[str, int]] = set()
        for unit in visible_units:
            unit_id = unit.get("id")
            if not isinstance(unit_id, int):
                continue
            key = (str(unit.get("team", "enemy")), unit_id)
            visible_unit_keys.add(key)
            self._unit_tracks[key] = {"value": deepcopy(unit), "last_seen_tick": tick}

        visible_building_keys: set[tuple[str, str, int, int]] = set()
        enemy_rows: list[dict[str, Any]] = []
        for enemy in state.get("enemy_teams", []):
            if not isinstance(enemy, dict):
                continue
            team = enemy.get("team") if isinstance(enemy.get("team"), dict) else {}
            team_name = str(team.get("name", "enemy"))
            buildings = [
                building for building in enemy.get("buildings", [])
                if isinstance(building, dict) and self._is_visible(building, sources)
            ]
            for building in buildings:
                key = self._building_key(team_name, building)
                if key is None:
                    continue
                visible_building_keys.add(key)
                self._building_tracks[key] = {"value": deepcopy(building), "last_seen_tick": tick}
            team_visible_units = [
                unit for unit in enemy.get("units", [])
                if isinstance(unit, dict) and self._is_visible(unit, sources)
            ]
            filtered = deepcopy(enemy)
            filtered["buildings"] = buildings
            filtered["units"] = team_visible_units
            enemy_rows.append(filtered)

        for key, track in list(self._unit_tracks.items()):
            if key in visible_unit_keys:
                continue
            age = max(0.0, tick - float(track["last_seen_tick"])) / 60.0
            if age > STALE_UNIT_TRACK_SECONDS or self._is_visible(track["value"], sources):
                del self._unit_tracks[key]
        for key, track in list(self._building_tracks.items()):
            if key not in visible_building_keys and self._is_visible(track["value"], sources):
                del self._building_tracks[key]

        observed["enemy_units"] = visible_units
        observed["enemy_teams"] = enemy_rows
        observed["threat_summary"] = self._visible_threat_summary(observed, visible_units)
        defense = observed.get("defense_analysis")
        if isinstance(defense, dict):
            defense["current_enemy_comparison"] = self._visible_force_summary(visible_units)
        observed["recent_combat_losses"] = self._filter_losses(
            state.get("recent_combat_losses"), self_name, sources, tick
        )
        observed["recent_unit_combat"] = self._filter_combat(
            state.get("recent_unit_combat"), self_name, sources, tick
        )
        observed["strategic_intelligence"] = self._intelligence_state(
            state, sources, visible_units, tick
        )
        return observed

    def _vision_sources(self, state: dict[str, Any]) -> list[tuple[float, float, float]]:
        sources: list[tuple[float, float, float]] = []
        for core in state.get("cores", []):
            position = _position(core)
            if position is not None:
                sources.append((*position, CORE_VISION_RADIUS_TILES))
        if not sources:
            position = _position(state.get("core"))
            if position is not None:
                sources.append((*position, CORE_VISION_RADIUS_TILES))
        for unit in state.get("friendly_units", []):
            position = _position(unit)
            if position is not None:
                sources.append((*position, UNIT_VISION_RADIUS_TILES))
        for building in state.get("buildings", []):
            position = _position(building)
            if position is not None:
                sources.append((*position, BUILDING_VISION_RADIUS_TILES))
        return sources

    @staticmethod
    def _is_visible(value: dict[str, Any], sources: list[tuple[float, float, float]]) -> bool:
        position = _position(value)
        if position is None:
            return False
        x, y = position
        return any((x - sx) ** 2 + (y - sy) ** 2 <= radius ** 2 for sx, sy, radius in sources)

    @staticmethod
    def _building_key(team: str, building: dict[str, Any]) -> tuple[str, str, int, int] | None:
        x, y = building.get("x"), building.get("y")
        if not isinstance(x, (int, float)) or not isinstance(y, (int, float)):
            return None
        return team, str(building.get("block", "unknown")), int(x), int(y)

    @staticmethod
    def _visible_force_summary(units: list[dict[str, Any]]) -> dict[str, Any]:
        ground_health = sum(float(unit.get("health", 0) or 0) for unit in units)
        return {
            "meaning": "Only enemy combat units currently inside friendly vision.",
            "visible_enemy_units": len(units),
            "observed_ground_or_unknown_health": ground_health,
            "observed_air_health": 0.0,
        }

    @staticmethod
    def _visible_threat_summary(
        state: dict[str, Any], units: list[dict[str, Any]],
    ) -> dict[str, Any]:
        core = state.get("core") if isinstance(state.get("core"), dict) else {}
        core_position = _position(core)
        distances: list[float] = []
        if core_position is not None:
            cx, cy = core_position
            for unit in units:
                position = _position(unit)
                if position is not None:
                    distances.append(math.dist((cx, cy), position))
        maximum = float(core.get("max_health", 0) or 0)
        health = float(core.get("health", 0) or 0)
        return {
            "meaning": "Threat counts include currently visible enemies only.",
            "core_health_fraction": health / maximum if maximum > 0 else 0.0,
            "enemy_units": len(units),
            "enemies_within_30_tiles": sum(distance <= 30 for distance in distances),
            "enemies_within_20_tiles": sum(distance <= 20 for distance in distances),
            "enemies_within_12_tiles": sum(distance <= 12 for distance in distances),
            "nearest_enemy_distance_tiles": min(distances) if distances else None,
        }

    def _filter_losses(
        self, raw: Any, self_name: str, sources: list[tuple[float, float, float]], tick: float,
    ) -> dict[str, Any]:
        losses = raw if isinstance(raw, dict) else {}
        opponents = losses.get("opponents") if isinstance(losses.get("opponents"), list) else []
        for opponent in opponents:
            if not isinstance(opponent, dict):
                continue
            team = str(opponent.get("team", "enemy"))
            for event in opponent.get("recent_events", []):
                if not isinstance(event, dict) or not self._is_visible(event, sources):
                    continue
                unit_id = event.get("unit_id")
                if isinstance(unit_id, int):
                    tracked = deepcopy(event)
                    tracked["_loss_tick"] = tick - float(event.get("seconds_ago", 0) or 0) * 60.0
                    self._observed_enemy_losses[(team, unit_id)] = tracked

        recent = []
        for key, tracked in list(self._observed_enemy_losses.items()):
            age = max(0.0, tick - float(tracked.get("_loss_tick", tick))) / 60.0
            if age > 30.0:
                del self._observed_enemy_losses[key]
                continue
            event = {name: value for name, value in tracked.items() if name != "_loss_tick"}
            event["seconds_ago"] = round(age, 1)
            recent.append(event)
        by_type: dict[str, int] = {}
        for event in recent:
            unit = str(event.get("unit", "unknown"))
            by_type[unit] = by_type.get(unit, 0) + 1
        own = deepcopy(losses.get("self", {"team": self_name}))
        return {
            "meaning": "Own losses are authoritative; opponent losses include only witnessed events.",
            "self": own,
            "observed_opponents": {
                "last_30_seconds": len(recent),
                "last_30_seconds_by_type": by_type,
                "recent_events": recent[-24:],
            },
        }

    def _intelligence_state(
        self,
        state: dict[str, Any],
        sources: list[tuple[float, float, float]],
        visible_units: list[dict[str, Any]],
        tick: float,
    ) -> dict[str, Any]:
        stale_units = []
        for track in self._unit_tracks.values():
            if float(track["last_seen_tick"]) >= tick:
                continue
            value = deepcopy(track["value"])
            value["seconds_since_seen"] = round((tick - float(track["last_seen_tick"])) / 60.0, 1)
            stale_units.append(value)
        stale_buildings = []
        for track in self._building_tracks.values():
            if float(track["last_seen_tick"]) >= tick:
                continue
            value = deepcopy(track["value"])
            value["seconds_since_seen"] = round((tick - float(track["last_seen_tick"])) / 60.0, 1)
            stale_buildings.append(value)
        landmarks = []
        for enemy in state.get("enemy_teams", []):
            if not isinstance(enemy, dict):
                continue
            team = enemy.get("team") if isinstance(enemy.get("team"), dict) else {}
            for core in enemy.get("cores", []):
                position = _position(core)
                if position is not None:
                    landmarks.append({"team": team.get("name"), "x": position[0], "y": position[1]})
        return {
            "mode": "episode_local_fog_of_war",
            "meaning": (
                "Exact enemy units and non-core buildings are supplied only while inside friendly vision. "
                "Last-known contacts are stale observations, not current positions. Enemy core coordinates and "
                "health remain public victory-scoreboard information."
            ),
            "vision_rules": {
                "friendly_unit_radius_tiles": UNIT_VISION_RADIUS_TILES,
                "friendly_core_radius_tiles": CORE_VISION_RADIUS_TILES,
                "friendly_building_radius_tiles": BUILDING_VISION_RADIUS_TILES,
                "stale_unit_track_expiry_seconds": STALE_UNIT_TRACK_SECONDS,
            },
            "vision_source_count": len(sources),
            "currently_visible_enemy_units": len(visible_units),
            "last_known_enemy_units": stale_units[:40],
            "last_known_enemy_buildings": stale_buildings[:80],
            "public_enemy_core_landmarks": landmarks,
        }

    def _filter_combat(
        self, raw: Any, self_name: str, sources: list[tuple[float, float, float]], tick: float,
    ) -> dict[str, Any]:
        combat = raw if isinstance(raw, dict) else {}
        opponents = combat.get("opponents") if isinstance(combat.get("opponents"), list) else []
        for opponent in opponents:
            if not isinstance(opponent, dict):
                continue
            team = str(opponent.get("team", "enemy"))
            for event in opponent.get("recent_events", []):
                if not isinstance(event, dict) or not self._is_visible(event, sources):
                    continue
                event_id = event.get("event_id")
                if not isinstance(event_id, int):
                    continue
                tracked = deepcopy(event)
                tracked["_event_tick"] = tick - float(event.get("seconds_ago", 0) or 0) * 60.0
                self._observed_enemy_combat[(team, event_id)] = tracked

        recent: list[dict[str, Any]] = []
        for key, tracked in list(self._observed_enemy_combat.items()):
            age = max(0.0, tick - float(tracked.get("_event_tick", tick))) / 60.0
            if age > 30.0:
                del self._observed_enemy_combat[key]
                continue
            event = {name: value for name, value in tracked.items() if name != "_event_tick"}
            event["seconds_ago"] = round(age, 1)
            recent.append(event)
        return {
            "meaning": (
                "Own combat output is authoritative. Opponent output contains only witnessed bullet-hit events. "
                "raw_damage is before target armor and shields and is not a matchup recommendation."
            ),
            "self": deepcopy(combat.get("self", {"team": self_name})),
            "observed_opponents": {
                "last_10_seconds": self._combat_aggregate(
                    [event for event in recent if float(event.get("seconds_ago", 0) or 0) <= 10.0]
                ),
                "last_30_seconds": self._combat_aggregate(recent),
                "recent_events": recent[-120:],
            },
        }

    @staticmethod
    def _combat_aggregate(events: list[dict[str, Any]]) -> dict[str, Any]:
        by_type: dict[str, dict[str, Any]] = {}
        total = 0.0
        for event in events:
            damage = float(event.get("raw_damage", 0) or 0)
            total += damage
            unit = str(event.get("attacker_unit", "unknown"))
            row = by_type.setdefault(unit, {
                "unit": unit, "hits": 0, "raw_damage": 0.0,
                "unit_target_raw_damage": 0.0, "building_target_raw_damage": 0.0,
                "targets_by_type": {},
            })
            row["hits"] += 1
            row["raw_damage"] += damage
            damage_key = f"{event.get('target_kind', 'unit')}_target_raw_damage"
            if damage_key in row:
                row[damage_key] += damage
            target_type = str(event.get("target_type", "unknown"))
            row["targets_by_type"][target_type] = row["targets_by_type"].get(target_type, 0) + 1
        return {"hits": len(events), "raw_damage": total, "by_attacker_unit": list(by_type.values())}
