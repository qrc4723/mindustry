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

    def test_control_points_provide_benefits_but_not_victory(self) -> None:
        self.assertIn("RTS_CONTROL_PRODUCTION_SPEED_BONUS_PER_POINT = 0.10d", self.source)
        self.assertIn("RTS_CONTROL_REPAIR_MAX_HEALTH_PER_SECOND = 0.01d", self.source)
        self.assertIn("rtsProductionSpeedMultiplier", self.source)
        self.assertIn("unit.heal", self.source)
        self.assertIn("activeRtsControlPointCount", self.source)
        self.assertIn("Math.min(1.5d", self.source)
        self.assertNotIn('gameEndReason = "all_three_control_points_', self.source)
        self.assertNotIn("income_items", self.source)

    def test_training_can_stage_units_in_persistent_squads(self) -> None:
        self.assertIn('"training_squad_requires_rally"', self.source)
        self.assertIn("order.squadId", self.source)
        self.assertIn("squad.unitIds.add(unit.id)", self.source)
        self.assertIn("RTS_EMPTY_SQUAD_RETENTION_TICKS", self.source)
        self.assertIn('row.put("operation_phase", operationPhase)', self.source)
        self.assertIn('row.put("ready_for_new_order", readyForNewOrder)', self.source)

    def test_game_thread_state_requests_allow_serial_snapshot_work(self) -> None:
        self.assertIn("GAME_THREAD_REQUEST_TIMEOUT_SECONDS = 20", self.source)
        self.assertIn("future.get(GAME_THREAD_REQUEST_TIMEOUT_SECONDS", self.source)

    def test_showcase_uses_tactical_terrain_v2(self) -> None:
        self.assertIn("symmetric_three_lane_tactical_showcase_v2", self.source)
        self.assertIn("placeSymmetricTerrainRect", self.source)
        self.assertIn("objective-side cover with open capture centers", self.source)


if __name__ == "__main__":
    unittest.main()
