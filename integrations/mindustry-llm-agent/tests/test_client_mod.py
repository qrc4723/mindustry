import json
import unittest
from pathlib import Path


class ClientModMetadataTest(unittest.TestCase):
    def test_spectator_mod_is_hidden_from_multiplayer_compatibility(self) -> None:
        root = Path(__file__).resolve().parents[1]
        metadata = json.loads((root / "client-plugin" / "mod.json").read_text(encoding="utf-8"))

        self.assertTrue(metadata["hidden"])


if __name__ == "__main__":
    unittest.main()
