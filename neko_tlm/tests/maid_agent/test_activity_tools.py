import unittest

from _bootstrap import bootstrap_sdk

bootstrap_sdk()

from neko_minecraft import tools


class FakeDirector:
    def __init__(self):
        self.calls = []

    async def get_activity(self, **kwargs):
        self.calls.append(("get", kwargs))
        return {"success": True, "activity": {"type": "idle"}}

    async def get_capabilities(self, **kwargs):
        self.calls.append(("capabilities", kwargs))
        return {"success": True, "tlm_tasks": []}

    async def set_activity(self, target, **kwargs):
        self.calls.append(("set", target, kwargs))
        result = {"success": True, "target": dict(target), "status": "ACTIVE"}
        if target.get("type") == "tlm_task":
            result["final_activity"] = {
                "tlm_task": {
                    "id": "touhou_little_maid:attack",
                    "name": "Attack",
                    "suppressed": False,
                }
            }
        if target.get("type") == "skill":
            result["target_result"] = {
                "skill_id": target.get("skill_id"),
                "skill_name": target.get("skill"),
                "status": "RUNNING",
            }
        return result

    async def stop(self, **kwargs):
        self.calls.append(("stop", kwargs))
        return {"success": True, "status": "STOPPED"}

    async def execute_body_mutation(self, mutation, **kwargs):
        self.calls.append(("body", kwargs))
        return {
            "success": True,
            "operation": kwargs.get("operation"),
            "result": await mutation(),
        }


class FakePlugin:
    connected = True

    def __init__(self):
        self._maid_activity_director = FakeDirector()
        self._skill_runner = object()
        self._maid_status_cache = {}
        self.logger = type("Logger", (), {"info": lambda *args, **kwargs: None})()

    def _resolve_maid_id(self, maid_id=None):
        return maid_id or "maid-1"

    async def _send_request(self, request, timeout=5):
        if request.get("type") == "get_maid_status":
            return {
                "type": "maid_status",
                "data": {"maids": [{
                    "id": "maid-1",
                    "main_hand_item": "minecraft:torch",
                    "off_hand_item": "",
                }]},
            }
        command = request.get("data", {}).get("command", "")
        data = {"success": True}
        if command == "equip_item":
            data["equipped_item"] = "minecraft:torch"
        return {"type": "command_result", "data": data}


class ActivityToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_query_tools_delegate_to_director(self):
        plugin = FakePlugin()
        activity = await tools.do_get_maid_activity(plugin)
        capabilities = await tools.do_get_maid_capabilities(plugin)

        self.assertFalse(activity["is_error"])
        self.assertFalse(capabilities["is_error"])
        self.assertEqual("get", plugin._maid_activity_director.calls[0][0])
        self.assertEqual(
            "capabilities", plugin._maid_activity_director.calls[1][0]
        )

    async def test_set_builds_one_normalized_activity_target(self):
        plugin = FakePlugin()
        result = await tools.do_set_maid_activity(
            plugin,
            activity_type="skill",
            skill="mine_ore",
            args={"target_count": 8},
            switch_policy="reject_if_busy",
            request_id="request-1",
        )

        self.assertFalse(result["is_error"])
        _, target, kwargs = plugin._maid_activity_director.calls[0]
        self.assertEqual(
            {
                "type": "skill",
                "skill": "mine_ore",
                "args": {"target_count": 8},
            },
            target,
        )
        self.assertEqual("reject_if_busy", kwargs["switch_policy"])
        self.assertEqual("request-1", kwargs["request_id"])

    async def test_set_rejects_non_object_args_before_director(self):
        plugin = FakePlugin()
        result = await tools.do_set_maid_activity(
            plugin, activity_type="agent_action", kind="navigate", args=[]
        )

        self.assertTrue(result["is_error"])
        self.assertEqual("INVALID_ACTIVITY_ARGUMENTS", result["error"])
        self.assertEqual([], plugin._maid_activity_director.calls)

    async def test_stop_preserves_switch_to_idle_choice(self):
        plugin = FakePlugin()
        result = await tools.do_stop_maid_activity(
            plugin, switch_to_idle=False, request_id="stop-1"
        )

        self.assertFalse(result["is_error"])
        _, kwargs = plugin._maid_activity_director.calls[0]
        self.assertFalse(kwargs["switch_to_idle"])
        self.assertEqual("stop-1", kwargs["request_id"])

    async def test_legacy_switch_task_is_routed_through_safe_director(self):
        plugin = FakePlugin()

        result = await tools.do_switch_task(plugin, task="attack")

        self.assertFalse(result["is_error"])
        _, target, kwargs = plugin._maid_activity_director.calls[0]
        self.assertEqual(
            {"type": "tlm_task", "task": "attack"}, target
        )
        self.assertEqual("cancel_then_switch", kwargs["switch_policy"])
        self.assertTrue(result["output"]["verified"])

    async def test_legacy_start_skill_uses_same_director_lock(self):
        plugin = FakePlugin()

        result = await tools.do_start_skill(
            plugin,
            skill="mine_ore",
            args={"target_count": 8},
            skill_id="skill-1",
        )

        self.assertFalse(result["is_error"])
        _, target, kwargs = plugin._maid_activity_director.calls[0]
        self.assertEqual("skill", target["type"])
        self.assertEqual("skill-1", target["skill_id"])
        self.assertEqual("cancel_then_switch", kwargs["switch_policy"])

    async def test_legacy_body_tools_use_director_guard(self):
        plugin = FakePlugin()

        results = [
            await tools.do_switch_follow(plugin, action="stay"),
            await tools.do_switch_sit(plugin, action="sit"),
            await tools.do_switch_schedule(plugin, schedule="all"),
            await tools.do_equip_item(plugin, item="minecraft:torch"),
        ]

        self.assertTrue(all(not item["is_error"] for item in results))
        operations = [
            call[1]["operation"] for call in plugin._maid_activity_director.calls
            if call[0] == "body"
        ]
        self.assertEqual(
            ["switch_follow", "switch_sit", "switch_schedule", "equip_item"],
            operations,
        )


if __name__ == "__main__":
    unittest.main()
