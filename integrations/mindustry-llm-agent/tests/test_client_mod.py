import json
import unittest
from pathlib import Path


class ClientModMetadataTest(unittest.TestCase):
    def test_spectator_mod_is_hidden_from_multiplayer_compatibility(self) -> None:
        root = Path(__file__).resolve().parents[1]
        metadata = json.loads((root / "client-plugin" / "mod.json").read_text(encoding="utf-8"))

        self.assertTrue(metadata["hidden"])

    def test_spectator_cleanup_handles_dead_units_and_drawn_decals(self) -> None:
        root = Path(__file__).resolve().parents[1]
        source = (root / "client-plugin" / "src" / "main" / "java" / "dev" / "capstone" /
                  "mindustry" / "spectator" / "RtsSpectatorMod.java").read_text(encoding="utf-8")

        self.assertIn("Groups.unit.each", source)
        self.assertIn("unit.dead || unit.health <= 0f", source)
        self.assertIn("Groups.draw.each", source)


if __name__ == "__main__":
    unittest.main()
