import unittest

from _bootstrap import bootstrap

bootstrap()

from neko_minecraft.instructions import _TLM_AI_INSTRUCTIONS
from neko_minecraft.tool_defs import MC_START_MAID_ACTION


class MaidActionGuidanceTests(unittest.TestCase):
    def test_stone_example_uses_overworld_base_stone_tag(self):
        expected = "{type:'tag', id:'minecraft:base_stone_overworld'}"
        tool_text = MC_START_MAID_ACTION["description"] + str(
            MC_START_MAID_ACTION["parameters"]
        )
        self.assertIn(expected, _TLM_AI_INSTRUCTIONS)
        self.assertIn(expected, tool_text)
        self.assertNotIn("{type:'block', id:'minecraft:stone'}", tool_text)

    def test_guidance_declares_terrain_aware_harvest_boundary(self):
        self.assertIn("规划清理安全", _TLM_AI_INSTRUCTIONS)
        self.assertIn("短距离下挖或开通道", _TLM_AI_INSTRUCTIONS)
        self.assertIn("普通 navigate 始终是非破坏性寻路", _TLM_AI_INSTRUCTIONS)
        self.assertIn("不会强制加载未加载区块", _TLM_AI_INSTRUCTIONS)
        tool_text = MC_START_MAID_ACTION["description"] + str(
            MC_START_MAID_ACTION["parameters"]
        )
        self.assertIn("清理安全", tool_text)
        self.assertIn("短距离下挖", tool_text)
        self.assertIn("navigate 始终非破坏性", tool_text)
        self.assertIn("不会搭桥", tool_text)
        self.assertIn("不会强制加载", tool_text)

    def test_capability_upgrade_does_not_change_tool_protocol(self):
        parameters = MC_START_MAID_ACTION["parameters"]
        self.assertEqual("object", parameters["type"])
        self.assertEqual(["kind", "args"], parameters["required"])
        self.assertEqual(
            {"kind", "args", "action_id", "timeout_ms", "replace_existing"},
            set(parameters["properties"]),
        )
        self.assertEqual(
            ["navigate", "harvest_blocks"],
            parameters["properties"]["kind"]["enum"],
        )


if __name__ == "__main__":
    unittest.main()
