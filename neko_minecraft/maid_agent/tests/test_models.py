import unittest

from _bootstrap import bootstrap

bootstrap()

from neko_minecraft.maid_agent.models import ActionTracker
from neko_minecraft.maid_agent.registry import ActionRegistry, ActionValidationError


class ActionTrackerTests(unittest.TestCase):
    def test_rejects_old_generation_and_duplicate_sequence(self):
        tracker = ActionTracker()
        first, accepted = tracker.apply({
            "action_id": "action",
            "generation": 2,
            "sequence": 4,
            "status": "RUNNING",
            "stage": "MOVING",
        })
        self.assertTrue(accepted)

        duplicate, accepted = tracker.apply({
            "action_id": "action", "generation": 2, "sequence": 4,
            "status": "SUCCEEDED",
        })
        self.assertFalse(accepted)
        self.assertIs(duplicate, first)
        self.assertEqual("RUNNING", first.status)

        _, accepted = tracker.apply({
            "action_id": "action", "generation": 1, "sequence": 99,
            "status": "SUCCEEDED",
        })
        self.assertFalse(accepted)

        replacement, accepted = tracker.apply({
            "action_id": "action", "generation": 3, "sequence": 0,
            "status": "RUNNING", "stage": "PATHFINDING",
        })
        self.assertTrue(accepted)
        self.assertEqual(3, replacement.generation)
        self.assertEqual("PATHFINDING", replacement.stage)

    def test_terminal_cannot_regress_to_running(self):
        tracker = ActionTracker()
        tracker.apply({
            "action_id": "done", "generation": 1, "sequence": 2,
            "status": "SUCCEEDED",
        })
        record, accepted = tracker.apply({
            "action_id": "done", "generation": 1, "sequence": 3,
            "status": "RUNNING",
        })
        self.assertFalse(accepted)
        self.assertEqual("SUCCEEDED", record.status)


class ActionRegistryTests(unittest.TestCase):
    def setUp(self):
        self.registry = ActionRegistry()

    def test_normalizes_navigate_defaults(self):
        args = self.registry.normalize("navigate", {"target": {"x": 1, "y": 64, "z": -2}})
        self.assertEqual({"x": 1, "y": 64, "z": -2}, args["target"])
        self.assertEqual(0.7, args["speed"])
        self.assertEqual(1.5, args["stop_distance"])

    def test_harvest_requires_exactly_one_targeting_mode(self):
        with self.assertRaises(ActionValidationError):
            self.registry.normalize("harvest_blocks", {})
        with self.assertRaises(ActionValidationError):
            self.registry.normalize("harvest_blocks", {
                "target_pos": {"x": 0, "y": 64, "z": 0},
                "selector": {"type": "block", "id": "minecraft:stone"},
            })

    def test_normalizes_harvest_selector(self):
        args = self.registry.normalize("harvest_blocks", {
            "selector": {"type": "tag", "id": "minecraft:base_stone_overworld"},
            "search_radius": 8,
            "max_blocks": 2,
        })
        self.assertEqual("tag", args["selector"]["type"])
        self.assertEqual(8, args["search_radius"])
        self.assertEqual("require_correct", args["tool_policy"])


if __name__ == "__main__":
    unittest.main()
