import importlib
import unittest

from ._bootstrap import bootstrap_sdk

bootstrap_sdk()

tools = importlib.import_module("neko_tlm.tools")
_tool_defs = importlib.import_module("neko_tlm.tool_defs")
MC_CANCEL_SKILL = _tool_defs.MC_CANCEL_SKILL
MC_GET_SKILL_STATUS = _tool_defs.MC_GET_SKILL_STATUS
MC_LIST_SKILLS = _tool_defs.MC_LIST_SKILLS
MC_START_SKILL = _tool_defs.MC_START_SKILL


class FakeRunner:
    def __init__(self):
        self.calls = []
        self.snapshots = {
            "skill-1": {
                "skill_id": "skill-1", "maid_id": "maid-1",
                "skill_name": "mine_ore", "status": "RUNNING",
            },
        }

    async def start(self, **kwargs):
        self.calls.append(("start", kwargs))
        return dict(self.snapshots["skill-1"])

    async def cancel(self, **kwargs):
        self.calls.append(("cancel", kwargs))
        return {**self.snapshots["skill-1"], "status": "CANCELLED"}

    def get_status(self, skill_id):
        self.calls.append(("status", skill_id))
        return self.snapshots.get(skill_id)

    def list_skills(self, **kwargs):
        self.calls.append(("list", kwargs))
        return list(self.snapshots.values())


class FakePlugin:
    connected = True
    _maid_agent_enabled = True

    def __init__(self):
        self._skill_runner = FakeRunner()

    def _resolve_maid_id(self, maid_id=None):
        return maid_id or "maid-1"


class SkillToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_start_calls_runner_directly_with_frozen_skill_wire_key(self):
        plugin = FakePlugin()
        args = {
            "selector": {"type": "tag", "id": "minecraft:diamond_ores"},
            "target_count": 8,
            "target_metric": "blocks_harvested",
        }
        result = await tools.do_start_skill(plugin, skill="mine_ore", args=args)
        self.assertFalse(result["is_error"])
        self.assertTrue(result["output"]["execution_pending"])
        self.assertFalse(result["output"]["completion_confirmed"])
        self.assertTrue(result["output"]["terminal_event_required"])
        name, call = plugin._skill_runner.calls[0]
        self.assertEqual("start", name)
        self.assertEqual("mine_ore", call["skill_name"])
        self.assertEqual("maid-1", call["maid_id"])
        self.assertEqual(args, call["args"])

    async def test_cancel_status_and_list_use_only_skill_runner(self):
        plugin = FakePlugin()
        cancelled = await tools.do_cancel_skill(plugin, skill_id="skill-1")
        status = await tools.do_get_skill_status(plugin, skill_id="skill-1")
        listed = await tools.do_list_skills(plugin, include_terminal=False)
        self.assertFalse(cancelled["is_error"])
        self.assertFalse(status["is_error"])
        self.assertFalse(listed["is_error"])
        self.assertEqual(False, plugin._skill_runner.calls[-1][1]["include_terminal"])

    def test_public_tool_names_and_mine_ore_schema_are_frozen(self):
        self.assertEqual("mc_start_skill", MC_START_SKILL["name"])
        self.assertEqual("mc_cancel_skill", MC_CANCEL_SKILL["name"])
        self.assertEqual("mc_get_skill_status", MC_GET_SKILL_STATUS["name"])
        self.assertEqual("mc_list_skills", MC_LIST_SKILLS["name"])
        params = MC_START_SKILL["parameters"]
        self.assertEqual(["skill", "args"], params["required"])
        self.assertNotIn("skill_name", params["properties"])
        mine_args = params["properties"]["args"]
        self.assertEqual(["selector", "target_count"], mine_args["required"])
        self.assertEqual(
            ["mine_ore", "gather_blocks"],
            params["properties"]["skill"]["enum"],
        )
        self.assertIn("vein_mining", mine_args["properties"])
        self.assertIn("search_radius", mine_args["properties"])
        self.assertEqual(
            ["auto", "level", "staircase_down"],
            mine_args["properties"]["shape"]["enum"],
        )
        self.assertEqual(
            ["autonomous", "legacy"],
            mine_args["properties"]["execution_mode"]["enum"],
        )
        self.assertEqual(
            ["loaded_scan", "exposed_only"],
            mine_args["properties"]["discovery_mode"]["enum"],
        )
        self.assertEqual(
            ["disabled", "safe_support_and_water_seal"],
            mine_args["properties"]["placement_policy"]["enum"],
        )
        self.assertEqual(0, mine_args["properties"]["max_placements"]["minimum"])
        self.assertEqual(4096, mine_args["properties"]["max_placements"]["maximum"])


if __name__ == "__main__":
    unittest.main()
