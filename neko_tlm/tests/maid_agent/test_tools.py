import importlib
import unittest

from ._bootstrap import bootstrap_sdk

bootstrap_sdk()

tools = importlib.import_module("neko_tlm.tools")


class FakePlugin:
    connected = True

    def __init__(self, response):
        self.response = response
        self.requests = []
        self._maid_action_service = None

    def _resolve_maid_id(self, maid_id=None):
        return maid_id or "maid-1"

    async def _send_request(self, request, timeout=30):
        self.requests.append(request)
        return self.response

    async def _push_minecraft_context(self, *args, **kwargs):
        pass


class FakeDirector:
    def __init__(self, result):
        self.result = result
        self.calls = []

    async def set_activity(self, target, **kwargs):
        self.calls.append((target, kwargs))
        return self.result


class MaidActionToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_start_builds_normalized_protocol_request(self):
        plugin = FakePlugin({
            "type": "maid_action_start_result",
            "data": {
                "accepted": True, "action_id": "fixed", "maid_id": "maid-1",
                "generation": 1, "sequence": 0, "kind": "navigate",
                "status": "RUNNING", "stage": "PATHFINDING",
            },
        })
        result = await tools.do_start_maid_action(
            plugin,
            kind="navigate",
            action_id="fixed",
            args={"target": {"x": 4, "y": 65, "z": 9}},
        )
        self.assertFalse(result["is_error"])
        request = plugin.requests[0]
        self.assertEqual("start_maid_action", request["type"])
        self.assertEqual("maid-1", request["data"]["maid_id"])
        self.assertEqual(0.7, request["data"]["args"]["speed"])

    async def test_start_rejects_invalid_args_without_request(self):
        plugin = FakePlugin({})
        result = await tools.do_start_maid_action(
            plugin, kind="harvest_blocks", args={}
        )
        self.assertTrue(result["is_error"])
        self.assertEqual("INVALID_ACTION_ARGUMENTS", result["error"])
        self.assertEqual([], plugin.requests)

    async def test_start_passes_normalized_mining_plan_to_server(self):
        plugin = FakePlugin({
            "type": "maid_action_start_result",
            "data": {
                "accepted": True, "action_id": "mine", "maid_id": "maid-1",
                "generation": 1, "sequence": 1, "kind": "harvest_blocks",
                "status": "RUNNING", "stage": "SEARCHING",
            },
        })
        result = await tools.do_start_maid_action(
            plugin,
            kind="harvest_blocks",
            action_id="mine",
            args={
                "selector": {"type": "tag", "id": "minecraft:diamond_ores"},
                "max_blocks": 3,
                "mining_plan": {
                    "mode": "staircase_down",
                    "direction": "west",
                    "max_distance": 12,
                    "max_depth": 6,
                    "excavation_budget": 48,
                },
            },
        )
        self.assertFalse(result["is_error"])
        plan = plugin.requests[0]["data"]["args"]["mining_plan"]
        self.assertTrue(plugin.requests[0]["data"]["args"]["vein_mining"])
        self.assertEqual("staircase_down", plan["mode"])
        self.assertEqual("west", plan["direction"])
        self.assertEqual(12, plan["max_distance"])
        self.assertEqual(6, plan["max_depth"])
        self.assertEqual(1, plan["max_segments"])
        self.assertEqual(48, plan["excavation_budget"])

    async def test_default_ore_request_sends_whole_vein_contract(self):
        plugin = FakePlugin({
            "type": "maid_action_start_result",
            "data": {
                "accepted": True, "action_id": "vein", "maid_id": "maid-1",
                "generation": 1, "sequence": 1, "kind": "harvest_blocks",
                "status": "RUNNING", "stage": "SEARCHING",
            },
        })

        result = await tools.do_start_maid_action(
            plugin,
            kind="harvest_blocks",
            action_id="vein",
            args={"selector": {"type": "tag", "id": "minecraft:diamond_ores"}},
        )

        self.assertFalse(result["is_error"])
        normalized = plugin.requests[0]["data"]["args"]
        self.assertTrue(normalized["vein_mining"])
        self.assertEqual(1, normalized["max_blocks"])
        self.assertNotIn("mining_plan", normalized)
        self.assertEqual(0, plugin.requests[0]["data"]["timeout_ms"])

    async def test_multi_segment_plan_uses_safe_default_budget(self):
        plugin = FakePlugin({
            "type": "maid_action_start_result",
            "data": {
                "accepted": True, "action_id": "segments", "maid_id": "maid-1",
                "generation": 1, "sequence": 1, "kind": "harvest_blocks",
                "status": "RUNNING", "stage": "SEARCHING",
            },
        })
        result = await tools.do_start_maid_action(
            plugin,
            kind="harvest_blocks",
            action_id="segments",
            args={
                "selector": {"type": "tag", "id": "minecraft:diamond_ores"},
                "mining_plan": {"mode": "auto", "max_segments": 2},
            },
        )
        self.assertFalse(result["is_error"])
        plan = plugin.requests[0]["data"]["args"]["mining_plan"]
        self.assertEqual(2, plan["max_segments"])
        self.assertEqual(64, plan["excavation_budget"])

    async def test_start_generates_action_id(self):
        plugin = FakePlugin({
            "type": "maid_action_start_result",
            "data": {"accepted": True, "generation": 1, "status": "RUNNING"},
        })
        await tools.do_start_maid_action(
            plugin,
            kind="navigate",
            args={"target": {"x": 1, "y": 64, "z": 1}},
        )
        self.assertTrue(plugin.requests[0]["data"]["action_id"])
        self.assertEqual(60000, plugin.requests[0]["data"]["timeout_ms"])

    async def test_return_to_position_defaults_to_no_deadline(self):
        plugin = FakePlugin({
            "type": "maid_action_start_result",
            "data": {
                "accepted": True, "action_id": "return", "generation": 1,
                "status": "RUNNING", "kind": "return_to_position",
            },
        })
        result = await tools.do_start_maid_action(
            plugin,
            kind="return_to_position",
            action_id="return",
            args={"destination": "surface"},
        )
        self.assertFalse(result["is_error"])
        payload = plugin.requests[0]["data"]
        self.assertEqual(0, payload["timeout_ms"])
        self.assertEqual("surface", payload["args"]["destination"])
        self.assertEqual(
            "recorded_tunnels_first", payload["args"]["route_policy"]
        )
        self.assertEqual(
            "safe_support_and_water_seal",
            payload["args"]["placement_policy"],
        )
        self.assertEqual(0, payload["args"]["max_placements"])
        self.assertTrue(result["output"]["execution_pending"])
        self.assertFalse(result["output"]["completion_confirmed"])
        self.assertTrue(result["output"]["terminal_event_required"])

    async def test_simple_move_tool_starts_safe_return_to_player(self):
        plugin = FakePlugin({
            "type": "maid_action_start_result",
            "data": {
                "accepted": True, "action_id": "move", "generation": 1,
                "status": "RUNNING", "kind": "return_to_position",
            },
        })
        result = await tools.do_move_maid_to(plugin, destination="player")
        self.assertFalse(result["is_error"])
        payload = plugin.requests[0]["data"]
        self.assertEqual("return_to_position", payload["kind"])
        self.assertEqual({
            "destination": "player",
            "speed": 0.7,
            "stop_distance": 1.5,
            "route_policy": "recorded_tunnels_first",
            "placement_policy": "safe_support_and_water_seal",
            "max_placements": 0,
        }, payload["args"])
        self.assertEqual(0, payload["timeout_ms"])
        self.assertTrue(payload["replace_existing"])
        self.assertFalse(result["output"]["completion_confirmed"])

    async def test_simple_move_tool_rejects_unknown_destination_without_request(self):
        plugin = FakePlugin({})
        result = await tools.do_move_maid_to(plugin, destination="somewhere")
        self.assertTrue(result["is_error"])
        self.assertEqual("INVALID_ACTION_ARGUMENTS", result["error"])
        self.assertEqual([], plugin.requests)

    async def test_simple_move_tool_director_path_is_still_pending(self):
        plugin = FakePlugin({})
        plugin._maid_activity_director = FakeDirector({
            "success": True,
            "target_result": {
                "action_id": "director-move", "kind": "return_to_position",
                "status": "RUNNING",
            },
        })
        result = await tools.do_move_maid_to(plugin, destination="surface")
        self.assertFalse(result["is_error"])
        self.assertTrue(result["output"]["execution_pending"])
        self.assertFalse(result["output"]["completion_confirmed"])
        self.assertTrue(result["output"]["terminal_event_required"])
        self.assertEqual([], plugin.requests)
        self.assertEqual("return_to_position",
                         plugin._maid_activity_director.calls[0][0]["kind"])

    async def test_completion_confirmation_requires_completed_and_arrived(self):
        base = {
            "kind": "return_to_position", "status": "SUCCEEDED",
            "end_reason": "COMPLETED",
        }
        missing_arrival = tools._action_execution_confirmation({
            **base, "result": {"arrived": False},
        })
        self.assertFalse(missing_arrival["execution_pending"])
        self.assertFalse(missing_arrival["completion_confirmed"])
        confirmed = tools._action_execution_confirmation({
            **base, "result": {"arrived": True},
        })
        self.assertTrue(confirmed["completion_confirmed"])
        missing_reason = tools._action_execution_confirmation({
            "kind": "harvest_blocks", "status": "SUCCEEDED",
        })
        self.assertFalse(missing_reason["completion_confirmed"])
        missing_harvest_contract = tools._action_execution_confirmation({
            "kind": "harvest_blocks",
            "status": "SUCCEEDED",
            "end_reason": "COMPLETED",
            "result": {"harvested": 8, "requested": 8},
        })
        self.assertFalse(missing_harvest_contract["completion_confirmed"])

    async def test_partial_harvest_never_confirms_completion(self):
        partial = tools._action_execution_confirmation({
            "kind": "harvest_blocks",
            "status": "SUCCEEDED",
            "end_reason": "COMPLETED",
            "result": {
                "harvested": 5,
                "requested": 8,
                "partial": True,
                "request_satisfied": False,
            },
        })
        self.assertFalse(partial["completion_confirmed"])
        self.assertFalse(partial["conversation_goal_confirmed"])

        satisfied = tools._action_execution_confirmation({
            "kind": "harvest_blocks",
            "status": "SUCCEEDED",
            "end_reason": "COMPLETED",
            "result": {
                "harvested": 8,
                "requested": 8,
                "partial": False,
                "request_satisfied": True,
            },
        })
        self.assertTrue(satisfied["action_completion_confirmed"])
        self.assertFalse(satisfied["conversation_goal_confirmed"])

    async def test_timeout_zero_is_accepted_but_subsecond_positive_is_rejected(self):
        response = {
            "type": "maid_action_start_result",
            "data": {"accepted": True, "generation": 1, "status": "RUNNING"},
        }
        plugin = FakePlugin(response)
        result = await tools.do_start_maid_action(
            plugin,
            kind="navigate",
            timeout_ms=0,
            args={"target": {"x": 1, "y": 64, "z": 1}},
        )
        self.assertFalse(result["is_error"])
        self.assertEqual(0, plugin.requests[0]["data"]["timeout_ms"])

        rejected = await tools.do_start_maid_action(
            FakePlugin(response),
            kind="navigate",
            timeout_ms=999,
            args={"target": {"x": 1, "y": 64, "z": 1}},
        )
        self.assertTrue(rejected["is_error"])
        self.assertIn("0 (no deadline)", rejected["output"]["error"])

    async def test_ore_selector_forces_no_deadline_over_model_timeout(self):
        plugin = FakePlugin({
            "type": "maid_action_start_result",
            "data": {"accepted": True, "generation": 1, "status": "RUNNING"},
        })
        result = await tools.do_start_maid_action(
            plugin,
            kind="harvest_blocks",
            timeout_ms=120000,
            args={"selector": {"type": "tag", "id": "minecraft:diamond_ores"}},
        )
        self.assertFalse(result["is_error"])
        self.assertEqual(0, plugin.requests[0]["data"]["timeout_ms"])

    async def test_cancel_without_id_uses_latest_active(self):
        plugin = FakePlugin({
            "type": "maid_action_cancel_result",
            "data": {"accepted": True, "action_id": "running", "status": "CANCEL_REQUESTED"},
        })
        service = tools._maid_action_service(plugin)
        service.tracker.apply({
            "action_id": "running", "maid_id": "maid-1", "generation": 1,
            "sequence": 1, "status": "RUNNING",
        })
        result = await tools.do_cancel_maid_action(plugin)
        self.assertFalse(result["is_error"])
        self.assertEqual("running", plugin.requests[0]["data"]["action_id"])

    async def test_status_and_list_observe_server_snapshots(self):
        plugin = FakePlugin({
            "type": "maid_action_status",
            "data": {
                "action_id": "a", "maid_id": "maid-1", "generation": 2,
                "sequence": 5, "kind": "navigate", "status": "SUCCEEDED",
            },
        })
        await tools.do_get_maid_action_status(plugin, action_id="a")
        self.assertEqual("SUCCEEDED", plugin._maid_action_service.tracker.get("a").status)

        plugin.response = {
            "type": "maid_action_list",
            "data": {"actions": [{
                "action_id": "b", "maid_id": "maid-1", "generation": 1,
                "sequence": 1, "kind": "harvest_blocks", "status": "RUNNING",
            }]},
        }
        await tools.do_list_active_maid_actions(plugin)
        self.assertEqual("RUNNING", plugin._maid_action_service.tracker.get("b").status)

    async def test_status_not_found_is_a_tool_error(self):
        plugin = FakePlugin({
            "type": "maid_action_status",
            "data": {"found": False, "error_code": "ACTION_NOT_FOUND"},
        })
        result = await tools.do_get_maid_action_status(plugin, action_id="missing")
        self.assertTrue(result["is_error"])
        self.assertEqual("ACTION_NOT_FOUND", result["error"])

    async def test_list_embedded_error_is_not_hidden_as_empty(self):
        plugin = FakePlugin({
            "type": "maid_action_list",
            "data": {"error": "Server not ready", "error_code": "SERVER_NOT_READY"},
        })
        result = await tools.do_list_active_maid_actions(plugin)
        self.assertTrue(result["is_error"])
        self.assertEqual("SERVER_NOT_READY", result["error"])


if __name__ == "__main__":
    unittest.main()
