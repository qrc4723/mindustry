import unittest
from pathlib import Path


class RtsPluginContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        root = Path(__file__).resolve().parents[1]
        cls.source = (root / "plugin" / "src" / "main" / "java" / "dev" / "capstone" /
                      "mindustry" / "MindustryAgentPlugin.java").read_text(encoding="utf-8")

    def test_dead_units_are_destroyed_over_the_network(self) -> None:
        self.assertIn("deadUnits.each(unit -> Call.unitDestroy(unit.id))", self.source)
        self.assertNotIn("deadUnits.each(Unit::remove)", self.source)

    def test_three_objective_hold_is_an_authoritative_timed_win_condition(self) -> None:
        self.assertIn("RTS_TOTAL_CONTROL_HOLD_SECONDS = 60d", self.source)
        self.assertIn('gameEndReason = "all_three_control_points_held_60_seconds"', self.source)
        self.assertIn("rtsControlPoints.stream().allMatch", self.source)
        self.assertIn("point.contested", self.source)
        self.assertIn("Events.fire(new GameOverEvent(secureController))", self.source)

    def test_showcase_uses_tactical_terrain_v2(self) -> None:
        self.assertIn("symmetric_three_lane_tactical_showcase_v2", self.source)
        self.assertIn("placeSymmetricTerrainRect", self.source)
        self.assertIn("objective-side cover with open capture centers", self.source)


if __name__ == "__main__":
    unittest.main()
