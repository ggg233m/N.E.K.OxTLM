import unittest

from _bootstrap import bootstrap

bootstrap()

from neko_minecraft.maid_agent.skills.base import Blocked, Complete, SkillRun, StartAction
from neko_minecraft.maid_agent.skills.mine_ore import MineOreSkill


def run_for(args=None, *, execution_mode="legacy"):
    definition = MineOreSkill()
    raw = dict(args or {
        "selector": {"type": "tag", "id": "minecraft:diamond_ores"},
        "target_count": 5,
        "target_metric": "blocks_harvested",
    })
    if execution_mode:
        raw.setdefault("execution_mode", execution_mode)
    normalized = definition.normalize_args(raw)
    run = SkillRun("00000000-0000-0000-0000-000000000001", "maid", "mine_ore", normalized)
    definition.initialize(run)
    return definition, run


def terminal(kind, *, status="SUCCEEDED", result=None, end_reason="COMPLETED"):
    return {
        "action_id": "child", "maid_id": "maid", "generation": 1,
        "sequence": 2, "kind": kind, "status": status,
        "end_reason": end_reason, "result": dict(result or {}),
    }


class MineOreSkillTests(unittest.TestCase):
    def test_normalizes_new_runs_to_java_autonomy_and_legacy_is_explicit(self):
        definition, autonomous = run_for(execution_mode=None)
        self.assertEqual("autonomous", autonomous.args["execution_mode"])
        self.assertEqual("auto", autonomous.args["direction"])
        self.assertEqual("auto", autonomous.args["shape"])
        self.assertEqual("loaded_scan", autonomous.args["discovery_mode"])

        definition, run = run_for()
        self.assertEqual("fishbone", run.args["strategy"])
        self.assertEqual("legacy", run.args["execution_mode"])
        self.assertEqual("auto", run.args["direction"])
        self.assertEqual("auto", run.args["shape"])
        self.assertEqual("north", run.main_direction)
        aliased = definition.normalize_args({
            "selector": {"type": "block", "id": "mod:tin_ore"},
            "target_count": 2,
            "target_metric": "blocks_harvested",
            "execution_mode": "legacy", "strategy": "auto",
            "direction": "west", "shape": "level",
        })
        self.assertEqual("fishbone", aliased["strategy"])
        self.assertEqual("level", aliased["shape"])

    def test_autonomous_mode_is_one_java_owned_child_with_frozen_mapping(self):
        definition, run = run_for({
            "selector": {"type": "tag", "id": "minecraft:iron_ores"},
            "target_count": 12,
            "target_metric": "blocks_harvested",
            "direction": "east",
            "shape": "staircase_down",
            "segment_length": 6,
            "speed": 0.8,
            "discovery_mode": "exposed_only",
        }, execution_mode=None)
        directive = definition.next_directive(run, None)
        self.assertIsInstance(directive, StartAction)
        self.assertEqual("autonomous_mining", directive.kind)
        self.assertEqual({
            "selector": {"type": "tag", "id": "minecraft:iron_ores"},
            "target_count": 12,
            "direction": "east",
            "shape": "staircase_down",
            "segment_length": 6,
            "speed": 0.8,
            "discovery_mode": "exposed_only",
        }, directive.args)

    def test_autonomous_blocked_terminal_requests_restart_decision(self):
        definition, run = run_for(execution_mode=None)
        directive = definition.next_directive(run, terminal(
            "autonomous_mining", status="FAILED", end_reason="BLOCKED",
            result={
                "phase": "BLOCKED",
                "collected_count": 3,
                "target_count": 5,
                "blocked_reason": "lava_hazard",
                "decision_required": True,
                "segments_dug": 2,
                "cleared_blocks": 8,
            },
        ))
        self.assertIsInstance(directive, Blocked)
        self.assertEqual("LAVA_HAZARD", directive.reason)
        self.assertTrue(directive.result["decision_required"])
        decision = directive.result["decision"]
        self.assertEqual("restart_with_adjusted_parameters", decision["mode"])
        self.assertFalse(decision["in_place_resume_supported"])
        self.assertEqual(3, directive.result["blocks_harvested"])

    def test_old_checkpoint_without_execution_mode_stays_legacy(self):
        definition = MineOreSkill()
        old_args = {
            "selector": {"type": "tag", "id": "minecraft:coal_ores"},
            "target_count": 1,
            "target_metric": "blocks_harvested",
            "strategy": "fishbone",
            "direction": "north",
            "shape": "staircase_down",
        }
        run = SkillRun("old", "maid", "mine_ore", old_args)
        run.result = {
            "phase": "harvest",
            "harvest_purpose": "junction_scan",
            "after_harvest_phase": "choose_direction",
        }
        directive = definition.next_directive(run, None)
        self.assertEqual("harvest_blocks", directive.kind)

    def test_initial_scan_uses_complete_nearby_vein(self):
        definition, run = run_for()
        directive = definition.next_directive(run, None)
        self.assertIsInstance(directive, StartAction)
        self.assertEqual("harvest_blocks", directive.kind)
        self.assertTrue(directive.args["vein_mining"])
        self.assertEqual(64, directive.args["max_blocks"])
        self.assertEqual({"mode": "nearby"}, directive.args["mining_plan"])

    def test_empty_initial_scan_starts_downward_main_segment(self):
        definition, run = run_for()
        definition.next_directive(run, None)
        run.current_action_request = {"kind": "harvest_blocks"}
        directive = definition.next_directive(run, terminal(
            "harvest_blocks", status="FAILED", end_reason="TARGET_CHANGED",
            result={"message": "no_matching_block_found"},
        ))
        self.assertEqual("excavate_segment", directive.kind)
        self.assertEqual("north", directive.args["direction"])
        self.assertEqual("staircase_down", directive.args["shape"])
        self.assertEqual(8, directive.args["length"])

    def test_main_segment_creates_junction_then_left_and_right_level_branches(self):
        definition, run = run_for()
        run.result.update({
            "phase": "dig", "dig_role": "main", "dig_direction": "north",
            "segment_remaining": 8, "junction_established": False,
        })
        directive = definition.next_directive(run, terminal(
            "excavate_segment",
            result={"stop_reason": "completed", "segments_dug": 8,
                    "real_end": {"x": 0, "y": 56, "z": -8}},
        ))
        self.assertEqual("harvest_blocks", directive.kind)
        self.assertEqual(1, run.main_segment_index)

        run.current_action_request = {"kind": "harvest_blocks"}
        directive = definition.next_directive(run, terminal(
            "harvest_blocks", status="FAILED", end_reason="TARGET_CHANGED",
            result={"message": "no_matching_block_found"},
        ))
        self.assertEqual("west", directive.args["direction"])
        self.assertEqual("level", directive.args["shape"])

        run.current_action_request = {"kind": "excavate_segment"}
        directive = definition.next_directive(run, terminal(
            "excavate_segment",
            result={"stop_reason": "completed", "segments_dug": 8,
                    "real_end": {"x": -8, "y": 56, "z": -8}},
        ))
        self.assertEqual("navigate", directive.kind)
        self.assertEqual({"x": 0, "y": 56, "z": -8}, directive.args["target"])

        run.current_action_request = {"kind": "navigate"}
        directive = definition.next_directive(run, terminal("navigate", result={}))
        self.assertEqual("east", directive.args["direction"])
        self.assertEqual("level", directive.args["shape"])

    def test_ore_encounter_harvests_whole_vein_and_resumes_from_real_end(self):
        definition, run = run_for()
        run.result.update({
            "phase": "dig", "dig_role": "main", "dig_direction": "north",
            "segment_remaining": 8, "junction_established": False,
        })
        directive = definition.next_directive(run, terminal(
            "excavate_segment",
            result={"stop_reason": "ore_encountered", "segments_dug": 3,
                    "real_end": {"x": 2, "y": 60, "z": -3}},
        ))
        self.assertEqual("harvest_blocks", directive.kind)
        self.assertEqual(64, directive.args["max_blocks"])
        self.assertEqual({"x": 2, "y": 63, "z": 0}, run.origin_pos)

        run.current_action_request = {"kind": "harvest_blocks"}
        directive = definition.next_directive(run, terminal(
            "harvest_blocks", result={"harvested": 2, "cleared_blocks": [1] * 99},
        ))
        self.assertEqual(2, run.collected_count)
        self.assertEqual("navigate", directive.kind)
        self.assertEqual({"x": 2, "y": 60, "z": -3}, directive.args["target"])

        run.current_action_request = {"kind": "navigate"}
        directive = definition.next_directive(run, terminal("navigate"))
        self.assertEqual("excavate_segment", directive.kind)
        self.assertEqual(5, directive.args["length"])

    def test_target_is_minimum_and_whole_vein_may_overshoot(self):
        definition, run = run_for()
        directive = definition.next_directive(run, terminal(
            "harvest_blocks", result={"blocks_harvested": 7},
        ))
        self.assertIsInstance(directive, Complete)
        self.assertEqual(7, run.collected_count)
        self.assertEqual(2, directive.result["target_overshoot"])

    def test_all_directions_blocked_has_strict_structured_suggestions(self):
        definition, run = run_for()
        run.tried_directions_at_current = ["north", "west", "east", "south"]
        run.result.update({
            "phase": "choose_direction", "junction_established": True,
            "junction_pos": {"x": 1, "y": 30, "z": 2},
        })
        directive = definition.next_directive(run, None)
        self.assertIsInstance(directive, Blocked)
        self.assertEqual("ALL_DIRECTIONS_BLOCKED", directive.reason)
        allowed = {"kind", "target_y", "basis", "requires_confirmation"}
        for suggestion in directive.result["suggestions"]:
            self.assertLessEqual(set(suggestion), allowed)
        change_level = next(
            item for item in directive.result["suggestions"]
            if item["kind"] == "change_level"
        )
        self.assertNotIn("target_y", change_level)
        self.assertEqual("current_dimension_unknown", change_level["basis"])

    def test_rejects_unfrozen_metric_strategy_and_shape(self):
        definition = MineOreSkill()
        base = {
            "selector": {"type": "tag", "id": "minecraft:iron_ores"},
            "target_count": 1, "target_metric": "blocks_harvested",
        }
        for field, value in (
            ("target_metric", "items_in_inventory"),
            ("strategy", "random_walk"),
            ("shape", "vertical_shaft"),
        ):
            with self.subTest(field=field):
                with self.assertRaises(ValueError):
                    definition.normalize_args({**base, field: value})


if __name__ == "__main__":
    unittest.main()
