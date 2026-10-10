import unittest

from mindustry_agent.intelligence import StrategicIntelligenceTracker


def rts_state(tick: int, enemy_x: float, *, enemy_building_x: int = 70) -> dict:
    unit = {
        "id": 91, "type": "dagger", "team": "crux", "x": enemy_x, "y": 10,
        "health": 100, "max_health": 100,
    }
    building = {
        "block": "ground-factory", "x": enemy_building_x, "y": 10,
        "health": 500, "max_health": 500,
    }
    core = {"block": "core-shard", "x": 90, "y": 10, "health": 6000, "max_health": 6000}
    return {
        "tick": tick,
        "rules": {"pvp": True},
        "game_mode_variant": {"id": "stockpile_rts_pvp"},
        "self_team": {"name": "sharded"},
        "core": {"x": 10, "y": 10, "health": 6000, "max_health": 6000},
        "cores": [{"x": 10, "y": 10}],
        "buildings": [],
        "friendly_units": [{"id": 1, "type": "flare", "x": 30, "y": 10}],
        "enemy_units": [unit],
        "enemy_teams": [{
            "team": {"name": "crux"}, "core": core, "cores": [core],
            "buildings": [building], "units": [unit],
        }],
        "threat_summary": {"enemy_units": 99},
        "defense_analysis": {"current_enemy_comparison": {"enemy_units": 99}},
        "recent_combat_losses": {
            "self": {"team": "sharded", "total_this_match": 2},
            "opponents": [],
        },
    }


class StrategicIntelligenceTrackerTest(unittest.TestCase):
    def test_hides_contacts_outside_vision_but_keeps_core_scoreboard(self) -> None:
        observed = StrategicIntelligenceTracker().observe(rts_state(60, 65))
        self.assertEqual(observed["enemy_units"], [])
        self.assertEqual(observed["enemy_teams"][0]["buildings"], [])
        self.assertEqual(observed["enemy_teams"][0]["cores"][0]["x"], 90)
        self.assertEqual(observed["threat_summary"]["enemy_units"], 0)
        self.assertEqual(
            observed["defense_analysis"]["current_enemy_comparison"]["visible_enemy_units"], 0
        )

    def test_visible_contact_becomes_stale_then_expires(self) -> None:
        tracker = StrategicIntelligenceTracker()
        visible = tracker.observe(rts_state(60, 45, enemy_building_x=48))
        self.assertEqual([unit["id"] for unit in visible["enemy_units"]], [91])
        self.assertEqual(len(visible["enemy_teams"][0]["buildings"]), 1)

        stale_state = rts_state(360, 65, enemy_building_x=70)
        stale_state["friendly_units"] = []
        stale = tracker.observe(stale_state)
        intel = stale["strategic_intelligence"]
        self.assertEqual(intel["last_known_enemy_units"][0]["id"], 91)
        self.assertEqual(intel["last_known_enemy_units"][0]["seconds_since_seen"], 5.0)
        self.assertEqual(intel["last_known_enemy_buildings"][0]["x"], 48)

        expired_state = rts_state(1200, 65, enemy_building_x=70)
        expired_state["friendly_units"] = []
        expired = tracker.observe(expired_state)
        self.assertEqual(expired["strategic_intelligence"]["last_known_enemy_units"], [])
        self.assertEqual(len(expired["strategic_intelligence"]["last_known_enemy_buildings"]), 1)

    def test_reobserved_empty_building_location_clears_stale_track(self) -> None:
        tracker = StrategicIntelligenceTracker()
        tracker.observe(rts_state(60, 45, enemy_building_x=48))
        state = rts_state(120, 65, enemy_building_x=70)
        state["friendly_units"] = [{"id": 1, "type": "flare", "x": 48, "y": 10}]
        observed = tracker.observe(state)
        stale_x = {
            row["x"] for row in observed["strategic_intelligence"]["last_known_enemy_buildings"]
        }
        self.assertNotIn(48, stale_x)

    def test_witnessed_enemy_loss_is_kept_without_global_enemy_totals(self) -> None:
        tracker = StrategicIntelligenceTracker()
        state = rts_state(60, 45)
        state["recent_combat_losses"]["opponents"] = [{
            "team": "crux", "total_this_match": 20,
            "recent_events": [{
                "seconds_ago": 1, "unit_id": 77, "unit": "dagger", "x": 45, "y": 10,
            }],
        }]
        losses = tracker.observe(state)["recent_combat_losses"]
        self.assertEqual(losses["observed_opponents"]["last_30_seconds"], 1)
        self.assertNotIn("total_this_match", losses["observed_opponents"])


if __name__ == "__main__":
    unittest.main()
