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


if __name__ == "__main__":
    unittest.main()
