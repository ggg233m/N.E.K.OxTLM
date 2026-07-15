import unittest

from _bootstrap import bootstrap

bootstrap()

from neko_minecraft.maid_agent.service import MaidActionService


class FakePlugin:
    def __init__(self, responses=None):
        self.responses = list(responses or [])
        self.requests = []
        self.pushes = []

    async def _send_request(self, request, timeout=30):
        self.requests.append((request, timeout))
        return self.responses.pop(0)

    async def _push_minecraft_context(self, text, **kwargs):
        self.pushes.append((text, kwargs))

    def _resolve_maid_id(self, maid_id=None):
        return maid_id or "m"


class Clock:
    def __init__(self):
        self.value = 0.0

    def __call__(self):
        return self.value


class MaidActionServiceTests(unittest.IsolatedAsyncioTestCase):
    async def test_progress_is_throttled_but_stage_change_is_immediate(self):
        plugin = FakePlugin()
        clock = Clock()
        service = MaidActionService(plugin, clock=clock)
        base = {
            "action_id": "a", "maid_id": "m", "generation": 1,
            "kind": "navigate", "status": "RUNNING",
        }
        await service.handle_message({
            "type": "maid_action_progress",
            "data": {**base, "sequence": 1, "stage": "MOVING", "progress": 0.1},
        })
        clock.value = 0.5
        await service.handle_message({
            "type": "maid_action_progress",
            "data": {**base, "sequence": 2, "stage": "MOVING", "progress": 0.2},
        })
        await service.handle_message({
            "type": "maid_action_progress",
            "data": {**base, "sequence": 3, "stage": "ARRIVING", "progress": 0.9},
        })
        self.assertEqual(2, len(plugin.pushes))
        self.assertTrue(all(push[1]["ai_behavior"] == "read" for push in plugin.pushes))
        self.assertTrue(all("%" not in push[0] for push in plugin.pushes))

    async def test_duplicate_response_is_not_returned_as_a_fresh_snapshot(self):
        service = MaidActionService(FakePlugin())
        service.tracker.apply({
            "action_id": "a", "maid_id": "m", "generation": 1,
            "sequence": 3, "status": "RUNNING",
        })
        observed = service.observe_response({
            "type": "maid_action_status",
            "data": {
                "action_id": "a", "maid_id": "m", "generation": 1,
                "sequence": 3, "status": "CANCEL_REQUESTED",
            },
        })
        self.assertEqual([], observed)
        self.assertEqual("RUNNING", service.tracker.get("a").status)

    async def test_stale_finished_event_is_ignored(self):
        plugin = FakePlugin()
        service = MaidActionService(plugin)
        service.tracker.apply({
            "action_id": "a", "maid_id": "m", "generation": 2,
            "sequence": 1, "status": "RUNNING",
        })
        handled = await service.handle_message({
            "type": "maid_action_finished",
            "data": {
                "action_id": "a", "maid_id": "m", "generation": 1,
                "sequence": 99, "status": "SUCCEEDED",
            },
        })
        self.assertTrue(handled)
        self.assertEqual([], plugin.pushes)

    async def test_finished_uses_respond_once(self):
        plugin = FakePlugin()
        service = MaidActionService(plugin)
        message = {
            "type": "maid_action_finished",
            "data": {
                "action_id": "a", "maid_id": "m", "generation": 1,
                "sequence": 4, "kind": "navigate", "status": "SUCCEEDED",
                "stage": "ARRIVED", "end_reason": "COMPLETED",
            },
        }
        await service.handle_message(message)
        await service.handle_message(message)
        self.assertEqual(1, len(plugin.pushes))
        self.assertEqual("respond", plugin.pushes[0][1]["ai_behavior"])

    async def test_unloaded_guessed_target_tells_model_to_retry_with_selector(self):
        plugin = FakePlugin()
        service = MaidActionService(plugin)
        await service.handle_message({
            "type": "maid_action_finished",
            "data": {
                "action_id": "a", "maid_id": "m", "generation": 1,
                "sequence": 4, "kind": "harvest_blocks", "status": "FAILED",
                "stage": "FAILED", "end_reason": "VALIDATION_FAILED",
                "result": {
                    "message": "target_chunk_not_loaded",
                    "retry_hint": "retry with a block/tag selector",
                },
            },
        })
        text, kwargs = plugin.pushes[0]
        self.assertEqual("respond", kwargs["ai_behavior"])
        self.assertIn("selector", text)
        self.assertIn("不要让玩家靠近", text)
        self.assertIn("不要强制加载区块", text)

    async def test_missing_ore_feedback_does_not_request_llm_retry(self):
        plugin = FakePlugin()
        service = MaidActionService(plugin)
        await service.handle_message({
            "type": "maid_action_finished",
            "data": {
                "action_id": "missing-ore", "maid_id": "maid", "generation": 1,
                "sequence": 4, "kind": "harvest_blocks", "status": "FAILED",
                "stage": "FAILED", "end_reason": "TARGET_CHANGED",
                "result": {
                    "message": "no_matching_block_found",
                    "selector": "tag:#minecraft:coal_ores",
                    "retry_hint": "Refresh the target or retry",
                },
            },
        })

        text = plugin.pushes[-1][0]
        self.assertIn("不要自动重复", text)
        self.assertIn("服务端", text)
        self.assertIn("minecraft:*_ores", text)
        self.assertNotIn("max_distance=8", text)
        self.assertNotIn("Refresh the target or retry", text)

    async def test_exhausted_server_prospect_does_not_invite_same_retry(self):
        plugin = FakePlugin()
        service = MaidActionService(plugin)
        await service.handle_message({
            "type": "maid_action_finished",
            "data": {
                "action_id": "exhausted", "maid_id": "maid", "generation": 1,
                "sequence": 9, "kind": "harvest_blocks", "status": "FAILED",
                "stage": "FAILED", "end_reason": "TARGET_CHANGED",
                "result": {
                    "message": "prospecting_budget_exhausted_without_match",
                    "retry_hint": "Refresh the target or retry",
                },
            },
        })

        text = plugin.pushes[-1][0]
        self.assertIn("有界探矿已经完成", text)
        self.assertIn("不要自动重复", text)
        self.assertNotIn("Refresh the target or retry", text)

    async def test_terrain_origin_drift_feedback_does_not_blame_selector(self):
        plugin = FakePlugin()
        service = MaidActionService(plugin)
        await service.handle_message({
            "type": "maid_action_finished",
            "data": {
                "action_id": "drift", "maid_id": "maid", "generation": 1,
                "sequence": 12, "kind": "harvest_blocks", "status": "FAILED",
                "stage": "FAILED", "end_reason": "STUCK",
                "result": {
                    "message": "terrain_origin_drift_replan_exhausted",
                    "retry_hint": "Refresh the target or retry with a broader selector",
                },
            },
        })

        text = plugin.pushes[-1][0]
        self.assertIn("路径执行位置偏移", text)
        self.assertIn("不要改变 selector", text)
        self.assertNotIn("broader selector", text)

    async def test_decision_required_uses_respond(self):
        plugin = FakePlugin()
        service = MaidActionService(plugin)
        await service.handle_message({
            "type": "maid_action_progress",
            "data": {
                "action_id": "a", "maid_id": "m", "generation": 1,
                "sequence": 4, "kind": "harvest_blocks", "status": "RUNNING",
                "stage": "WAITING_FOR_TOOL", "requires_decision": True,
            },
        })
        self.assertEqual("respond", plugin.pushes[0][1]["ai_behavior"])

    async def test_reconcile_adopts_server_action_and_marks_missing_local_lost(self):
        plugin = FakePlugin(responses=[
            {
                "type": "maid_action_list",
                "data": {"actions": [{
                    "action_id": "server", "maid_id": "m", "generation": 4,
                    "sequence": 2, "kind": "navigate", "status": "RUNNING",
                }]},
            },
            {"type": "error", "data": {"message": "not found"}},
        ])
        service = MaidActionService(plugin)
        service.tracker.apply({
            "action_id": "local", "maid_id": "m", "generation": 1,
            "sequence": 3, "kind": "harvest_blocks", "status": "RUNNING",
        })
        result = await service.reconcile()
        self.assertEqual(["server"], result["adopted"])
        self.assertEqual(["local"], result["lost"])
        self.assertEqual("FAILED", service.tracker.get("local").status)
        self.assertEqual("SERVER_STATE_LOST", service.tracker.get("local").end_reason)
        self.assertEqual("respond", plugin.pushes[0][1]["ai_behavior"])

    async def test_reconcile_keeps_local_action_on_transient_status_error(self):
        plugin = FakePlugin(responses=[
            {"type": "maid_action_list", "data": {"actions": []}},
            {"type": "error", "data": {"message": "Request timed out"}},
        ])
        service = MaidActionService(plugin)
        service.tracker.apply({
            "action_id": "local", "maid_id": "m", "generation": 1,
            "sequence": 3, "kind": "navigate", "status": "RUNNING",
        })
        result = await service.reconcile()
        self.assertEqual(["local"], result["unresolved"])
        self.assertEqual("RUNNING", service.tracker.get("local").status)
        self.assertEqual([], plugin.pushes)

    async def test_reconcile_marks_flat_not_found_status_as_lost(self):
        plugin = FakePlugin(responses=[
            {"type": "maid_action_list", "data": {"actions": []}},
            {
                "type": "maid_action_status",
                "data": {"found": False, "error_code": "ACTION_NOT_FOUND"},
            },
        ])
        service = MaidActionService(plugin)
        service.tracker.apply({
            "action_id": "local", "maid_id": "m", "generation": 1,
            "sequence": 3, "kind": "navigate", "status": "RUNNING",
        })
        result = await service.reconcile()
        self.assertEqual(["local"], result["lost"])
        self.assertEqual("SERVER_STATE_LOST", service.tracker.get("local").end_reason)


if __name__ == "__main__":
    unittest.main()
