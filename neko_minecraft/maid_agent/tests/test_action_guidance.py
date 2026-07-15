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

    def test_guidance_declares_exposed_reachable_resource_boundary(self):
        self.assertIn("安全可达站立面", _TLM_AI_INSTRUCTIONS)
        self.assertIn("不会自动挖开覆盖层", _TLM_AI_INSTRUCTIONS)
        tool_text = MC_START_MAID_ACTION["description"] + str(
            MC_START_MAID_ACTION["parameters"]
        )
        self.assertIn("安全可达", tool_text)
        self.assertIn("不会自动挖", tool_text)


if __name__ == "__main__":
    unittest.main()
