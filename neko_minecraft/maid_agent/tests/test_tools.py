import unittest

from _bootstrap import bootstrap_sdk

bootstrap_sdk()

from neko_minecraft import tools


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
        self.assertEqual(64, normalized["max_blocks"])
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
