import unittest

from _bootstrap import bootstrap

bootstrap()

from neko_minecraft.instructions import _TLM_AI_INSTRUCTIONS
from neko_minecraft.tool_defs import (
    MC_CANCEL_SKILL,
    MC_GET_SKILL_STATUS,
    MC_LIST_SKILLS,
    MC_START_MAID_ACTION,
    MC_START_SKILL,
)


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

    def test_guidance_routes_high_level_mining_and_emergency_stop_to_skill(self):
        tool_text = MC_START_MAID_ACTION["description"] + str(
            MC_START_MAID_ACTION["parameters"]
        )
        for value in (
            "mining_plan", "forward_tunnel", "staircase_down", "auto",
            "max_distance", "max_depth", "max_segments", "excavation_budget",
        ):
            self.assertIn(value, tool_text)
            self.assertIn(value, _TLM_AI_INSTRUCTIONS)
        self.assertIn("mc_start_skill", _TLM_AI_INSTRUCTIONS)
        self.assertIn("autonomous_mining", _TLM_AI_INSTRUCTIONS)
        self.assertIn("Java 自主完成", _TLM_AI_INSTRUCTIONS)
        self.assertIn("staircase_down", _TLM_AI_INSTRUCTIONS)
        self.assertIn("F8", _TLM_AI_INSTRUCTIONS)
        self.assertIn("mc_cancel_skill", _TLM_AI_INSTRUCTIONS)
        self.assertIn("mc_cancel_maid_action", _TLM_AI_INSTRUCTIONS)
        self.assertIn("底层兼容能力", _TLM_AI_INSTRUCTIONS)
        self.assertIn("协议兼容能力", tool_text)

    def test_guidance_keeps_low_level_mining_plan_as_compatibility_only(self):
        tool_text = MC_START_MAID_ACTION["description"] + str(
            MC_START_MAID_ACTION["parameters"]
        )
        for text in (tool_text, _TLM_AI_INSTRUCTIONS):
            self.assertIn("max_segments", text)
            self.assertIn("兼容", text)
            self.assertIn("256", text)
        self.assertIn("不是默认高层方案", tool_text)
        self.assertIn("不要用它代替普通高级找矿 Skill", _TLM_AI_INSTRUCTIONS)

    def test_guidance_exposes_frozen_skill_contract(self):
        self.assertEqual("mc_start_skill", MC_START_SKILL["name"])
        self.assertEqual("mc_cancel_skill", MC_CANCEL_SKILL["name"])
        self.assertEqual("mc_get_skill_status", MC_GET_SKILL_STATUS["name"])
        self.assertEqual("mc_list_skills", MC_LIST_SKILLS["name"])
        parameters = MC_START_SKILL["parameters"]
        self.assertEqual(["skill", "args"], parameters["required"])
        self.assertNotIn("skill_name", parameters["properties"])
        skill_text = MC_START_SKILL["description"] + str(parameters)
        for value in (
            "mine_ore", "target_count", "blocks_harvested",
            "autonomous_mining", "execution_mode", "segment_length",
            "loaded_scan", "decision_required",
            "placement_policy", "safe_support_and_water_seal",
            "max_placements",
        ):
            self.assertIn(value, skill_text)
            self.assertIn(value, _TLM_AI_INSTRUCTIONS)
        self.assertIn("Java 全程自主", skill_text)
        self.assertIn("LLM 不得逐段遥控", _TLM_AI_INSTRUCTIONS)
        self.assertIn("当前没有暂停", _TLM_AI_INSTRUCTIONS)
        self.assertIn("fishbone", skill_text)
        self.assertIn("legacy", skill_text)
        self.assertIn("旧检查点", _TLM_AI_INSTRUCTIONS)

    def test_autonomous_miner_documents_route_ore_and_safe_construction(self):
        skill_text = MC_START_SKILL["description"] + str(
            MC_START_SKILL["parameters"]
        )
        for value in (
            "其他矿石", "搭桥", "封水", "普通实心方块",
            "placement_policy", "max_placements",
        ):
            self.assertIn(value, skill_text)
            self.assertIn(value, _TLM_AI_INSTRUCTIONS)
        self.assertIn("不绕过领地保护", skill_text)
        self.assertIn("placement_protected", _TLM_AI_INSTRUCTIONS)
        self.assertIn("MiningPlanner", skill_text)
        self.assertIn("综合预计成本", _TLM_AI_INSTRUCTIONS)

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
            self.assertIn("26", text)
            self.assertIn("最低目标", text)
            self.assertIn("必须", text)
        self.assertIn("只挖一块", _TLM_AI_INSTRUCTIONS)
        self.assertIn("vein_mining=false", _TLM_AI_INSTRUCTIONS)
        self.assertIn("timeout_ms=0", _TLM_AI_INSTRUCTIONS)
        self.assertIn("120000", tool_text)
        self.assertIn("no_matching_block_found", _TLM_AI_INSTRUCTIONS)
        self.assertIn("不要自动重复", _TLM_AI_INSTRUCTIONS)


if __name__ == "__main__":
    unittest.main()
