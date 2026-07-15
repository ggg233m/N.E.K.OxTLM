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

    def test_guidance_exposes_continuous_mining_plans_and_emergency_stop(self):
        tool_text = MC_START_MAID_ACTION["description"] + str(
            MC_START_MAID_ACTION["parameters"]
        )
        for value in (
            "mining_plan", "forward_tunnel", "staircase_down", "auto",
            "max_distance", "max_depth", "max_segments", "excavation_budget",
        ):
            self.assertIn(value, tool_text)
            self.assertIn(value, _TLM_AI_INSTRUCTIONS)
        self.assertIn("附近没有目标", _TLM_AI_INSTRUCTIONS)
        self.assertIn("F8", _TLM_AI_INSTRUCTIONS)
        self.assertIn("mc_cancel_maid_action", _TLM_AI_INSTRUCTIONS)
        self.assertIn("不再因总步数", _TLM_AI_INSTRUCTIONS)
        self.assertIn("不再用它们终止动作", tool_text)

    def test_guidance_marks_old_mining_limits_as_compatibility_only(self):
        tool_text = MC_START_MAID_ACTION["description"] + str(
            MC_START_MAID_ACTION["parameters"]
        )
        for text in (tool_text, _TLM_AI_INSTRUCTIONS):
            self.assertIn("省略 mining_plan", text)
            self.assertIn("max_segments", text)
            self.assertIn("兼容", text)
            self.assertIn("256", text)

    def test_guidance_requires_a_concrete_llm_recovery_plan(self):
        self.assertIn("具体解决方案", _TLM_AI_INSTRUCTIONS)
        self.assertIn("禁止只道歉", _TLM_AI_INSTRUCTIONS)
        self.assertIn("立即调用", _TLM_AI_INSTRUCTIONS)
        self.assertIn("禁止相同参数无限重试", _TLM_AI_INSTRUCTIONS)

    def test_guidance_prefers_ore_tags_and_whole_veins(self):
        tool_text = MC_START_MAID_ACTION["description"] + str(
            MC_START_MAID_ACTION["parameters"]
        )
        for text in (tool_text, _TLM_AI_INSTRUCTIONS):
            self.assertIn("minecraft:diamond_ores", text)
            self.assertIn("vein_mining", text)
            self.assertIn("max_blocks", text)
            self.assertIn("64", text)
        self.assertIn("只挖一块", _TLM_AI_INSTRUCTIONS)
        self.assertIn("vein_mining=false", _TLM_AI_INSTRUCTIONS)
        self.assertIn("timeout_ms=0", _TLM_AI_INSTRUCTIONS)
        self.assertIn("120000", tool_text)
        self.assertIn("no_matching_block_found", _TLM_AI_INSTRUCTIONS)
        self.assertIn("不要自动重复", _TLM_AI_INSTRUCTIONS)


if __name__ == "__main__":
    unittest.main()
