"""LLM 工具业务逻辑 — 女仆状态、行为、聊天与 Agent 动作工具。"""

import uuid

from plugin.sdk.plugin import Ok, Err

from . import task_resolver
from . import plan as _plan
from .maid_agent import ActionValidationError, MaidActionService


_ITEM_ALIASES = {
    "火把": "minecraft:torch",
    "普通火把": "minecraft:torch",
    "torch": "minecraft:torch",
    "灵魂火把": "minecraft:soul_torch",
    "soul_torch": "minecraft:soul_torch",
}


def _normalize_item_id(item):
    text = str(item or "").strip()
    if not text:
        return ""
    return _ITEM_ALIASES.get(text.lower(), _ITEM_ALIASES.get(text, text))


async def do_maid_status(plugin):
    if not plugin.connected:
        return {"output": {"error": "Not connected to Minecraft"}, "is_error": True, "error": "NOT_CONNECTED"}
    result = await plugin._send_request({"type": "get_maid_status"})
    if result.get("type") == "error":
        return {"output": result.get("data", {}), "is_error": True, "error": "REQUEST_FAILED"}
    maids = result.get("data", {}).get("maids", [])
    for maid in maids:
        plugin._maid_status_cache[maid.get("id", "")] = maid
    return maid_status_payload(plugin, maids)


def maid_status_payload(plugin, maids, *, compact=False):
    payload = {
        "maids": [_compact_maid_status(m) for m in maids] if compact else maids,
    }
    selected = _select_status_maid(plugin, maids)
    if not selected:
        return payload
    current_task = selected.get("task", "")
    current_task_name = _task_name_for_id(current_task, selected.get("available_tasks", []))
    payload["selected_maid"] = _compact_maid_status(selected)
    payload["current_mode"] = {
        "id": current_task,
        "name": current_task_name,
    }
    payload["current_mode_answer"] = f"当前真实模式是：{current_task_name or current_task or '未知'}"
    payload["available_modes"] = _normalize_available_tasks(selected.get("available_tasks", []))
    return payload


def _compact_maid_status(maid):
    available = maid.get("available_tasks", [])
    current_task = maid.get("task", "")
    return {
        "id": maid.get("id", ""),
        "name": maid.get("name", ""),
        "health": maid.get("health", 0),
        "max_health": maid.get("max_health", 0),
        "is_sitting": maid.get("is_sitting", False),
        "is_following": maid.get("is_following", False),
        "current_mode": {
            "id": current_task,
            "name": _task_name_for_id(current_task, available),
        },
        "main_hand_item": maid.get("main_hand_item", ""),
        "off_hand_item": maid.get("off_hand_item", ""),
    }


def _select_status_maid(plugin, maids):
    if not maids:
        return None
    assigned = getattr(plugin, "_assigned_maid_id", "")
    if assigned:
        for maid in maids:
            if maid.get("id") == assigned:
                return maid
    return maids[0]


async def do_switch_follow(plugin, *, action="follow"):
    plugin.logger.info(f"[Entry] switch_follow called with action='{action}'")
    if not plugin.connected:
        return Err("Not connected to Minecraft")
    maid_id = plugin._resolve_maid_id()
    if not maid_id:
        return Err("No maid assigned")
    follow = action != "stay"
    result = await plugin._send_request({
        "type": "command_maid",
        "data": {"maid_id": maid_id, "command": "switch_follow", "args": {"follow": follow}},
    })
    if result.get("type") == "error":
        return Err(str(result.get("data", {})))
    result_data = result.get("data", {})
    if result_data.get("success") is False:
        return Err(result_data.get("error", "Command failed"))
    state = result_data.get("state", "")
    extra = {}
    if follow and state == "already_following":
        maid = plugin._maid_status_cache.get(maid_id, {})
        if maid.get("is_sitting", False):
            sit_result = await plugin._send_request({
                "type": "command_maid",
                "data": {"maid_id": maid_id, "command": "switch_sit", "args": {"sit": False}},
            })
            if sit_result.get("type") != "error":
                sit_data = sit_result.get("data", {})
                if sit_data.get("success") is not False:
                    extra["stood_up"] = True
                    plugin.logger.info("[Entry] switch_follow: maid was sitting, auto stood up")
    return Ok({"success": True, "action": action, **extra})


async def do_switch_sit(plugin, *, action="sit"):
    plugin.logger.info(f"[Entry] switch_sit called with action='{action}'")
    if not plugin.connected:
        return Err("Not connected to Minecraft")
    maid_id = plugin._resolve_maid_id()
    if not maid_id:
        return Err("No maid assigned")
    sit = action == "sit"
    result = await plugin._send_request({
        "type": "command_maid",
        "data": {"maid_id": maid_id, "command": "switch_sit", "args": {"sit": sit}},
    })
    if result.get("type") == "error":
        return Err(str(result.get("data", {})))
    result_data = result.get("data", {})
    if result_data.get("success") is False:
        return Err(result_data.get("error", "Command failed"))
    return Ok({"success": True, "action": action})


async def do_switch_task(plugin, *, task=""):
    plugin.logger.info(f"[Entry] switch_task called with task='{task}'")
    if not plugin.connected:
        return Err("Not connected to Minecraft")
    maid_id = plugin._resolve_maid_id()
    if not maid_id:
        return Err("No maid assigned")
    if not task:
        return Err("请提供task参数")

    maid = plugin._maid_status_cache.get(maid_id, {})
    available = maid.get("available_tasks", [])
    plugin.logger.info(f"[switch_task] Cache hit={bool(maid)}, available_tasks count={len(available)}")

    if not available:
        try:
            status_result = await plugin._send_request({"type": "get_maid_status"}, timeout=5)
            if status_result.get("type") != "error":
                for m in status_result.get("data", {}).get("maids", []):
                    plugin._maid_status_cache[m.get("id", "")] = m
                maid = plugin._maid_status_cache.get(maid_id, {})
                available = maid.get("available_tasks", [])
                plugin.logger.info(f"[switch_task] get_maid_status: found {len(maid.get('available_tasks', []))} tasks for maid {maid_id}")
            else:
                plugin.logger.warning(f"[switch_task] get_maid_status failed: {status_result.get('data', {})}")
        except Exception as e:
            plugin.logger.warning(f"[Entry] switch_task: failed to fetch maid status: {e}")

    if not available:
        try:
            ctx_result = await plugin._send_request({
                "type": "get_game_context",
                "data": {"maid_id": maid_id, "category": "status"},
            }, timeout=5)
            if ctx_result.get("type") != "error":
                available = ctx_result.get("data", {}).get("available_tasks", [])
                plugin.logger.info(f"[switch_task] get_game_context: found {len(available)} tasks")
            else:
                plugin.logger.warning(f"[switch_task] get_game_context failed: {ctx_result.get('data', {})}")
        except Exception as e:
            plugin.logger.warning(f"[Entry] switch_task: failed to query game_context: {e}")

    resolved_task = task_resolver.resolve_task_name(task, available)
    plugin.logger.info(f"[Entry] switch_task: '{task}' resolved to '{resolved_task}' (available={len(available)} tasks)")

    if resolved_task is None:
        return _switch_task_recoverable_error(
            task,
            available,
            "无法匹配到任何工作模式",
        )

    result = await plugin._send_request({
        "type": "command_maid",
        "data": {"maid_id": maid_id, "command": "switch_task", "args": {"task": resolved_task}},
    })
    if result.get("type") == "error":
        plugin.logger.warning(f"[Entry] switch_task failed: {result.get('data', {})}")
        return _switch_task_recoverable_error(
            task,
            available,
            "Minecraft mod 返回错误",
            result.get("data", {}),
            resolved_task=resolved_task,
        )
    result_data = result.get("data", {})
    if result_data.get("success") is False:
        merged_available = result_data.get("available_tasks") or available
        return _switch_task_recoverable_error(
            task,
            merged_available,
            result_data.get("error", "Command failed"),
            result_data,
            resolved_task=resolved_task,
        )
    plugin.logger.info(f"[Entry] switch_task success: task='{task}' -> '{resolved_task}'")
    verification = await _verify_switched_task(plugin, maid_id, resolved_task)
    if not verification.get("verified"):
        return _switch_task_verification_error(task, resolved_task, verification, result_data)
    return Ok({
        "success": True,
        "requested_task": task,
        "matched_task_id": resolved_task,
        **verification,
    })


def _switch_task_recoverable_error(task, available, message, raw_error=None, resolved_task=None):
    return {
        "output": {
            "success": False,
            "recoverable": True,
            "error": message,
            "requested_task": task,
            "matched_task_id": resolved_task,
            "available_tasks": _normalize_available_tasks(available),
            "retry_hint": (
                "请从 available_tasks 中选择最接近玩家意图的精确 id 或 name，"
                "然后再次调用 mc_switch_task。不要只口头说明失败。"
            ),
            "raw_error": raw_error or {},
        },
        "is_error": True,
        "error": "TASK_SWITCH_RECOVERABLE",
    }


def _switch_task_verification_error(task, resolved_task, verification, raw_result=None):
    return {
        "output": {
            "success": False,
            "recoverable": True,
            "error": "Minecraft reported task switch success, but status verification shows a different current task",
            "requested_task": task,
            "matched_task_id": resolved_task,
            "raw_result": raw_result or {},
            **verification,
            "retry_hint": (
                "不要告诉玩家已经切换成功。请说明 current_task/current_task_name 才是真实模式，"
                "并从 available_tasks 中选择攻击/战斗对应的精确 id/name 后再次调用 mc_switch_task。"
            ),
        },
        "is_error": True,
        "error": "TASK_SWITCH_VERIFY_FAILED",
    }


def _normalize_available_tasks(available):
    tasks = []
    for item in available or []:
        if isinstance(item, dict):
            task_id = str(item.get("id", "") or "")
            name = str(item.get("name", "") or "")
        else:
            task_id = str(item or "")
            name = ""
        if task_id or name:
            tasks.append({"id": task_id, "name": name})
    return tasks


async def _verify_switched_task(plugin, maid_id, expected_task):
    status_result = await plugin._send_request({"type": "get_maid_status"}, timeout=5)
    if status_result.get("type") == "error":
        return {
            "verified": False,
            "verification_error": status_result.get("data", {}),
            "expected_task": expected_task,
        }

    maids = status_result.get("data", {}).get("maids", [])
    for maid in maids:
        if maid.get("id") == maid_id:
            plugin._maid_status_cache[maid_id] = maid
            current_task = maid.get("task", "")
            current_task_name = _task_name_for_id(current_task, maid.get("available_tasks", []))
            verified = _task_matches(current_task, expected_task)
            return {
                "verified": verified,
                "current_task": current_task,
                "current_task_name": current_task_name,
                "expected_task": expected_task,
                "available_tasks": _normalize_available_tasks(maid.get("available_tasks", [])),
            }

    return {
        "verified": False,
        "verification_error": "Assigned maid was not present in status response",
        "expected_task": expected_task,
        "available_tasks": _normalize_available_tasks(
            maids[0].get("available_tasks", []) if maids else []
        ),
    }


def _task_matches(current_task, expected_task):
    current = str(current_task or "").strip().lower()
    expected = str(expected_task or "").strip().lower()
    if not current or not expected:
        return False
    return current == expected or current.split(":")[-1] == expected.split(":")[-1]


def _task_name_for_id(task_id, available):
    task_id = str(task_id or "")
    for item in available or []:
        if isinstance(item, dict) and item.get("id") == task_id:
            return item.get("name", "")
    return ""


async def _verify_equipped_item(plugin, maid_id, expected_item):
    expected_item = str(expected_item or "").strip()
    if not expected_item:
        return {
            "verified": False,
            "verification_error": "Command did not report which item should be in main hand",
        }

    status_result = await plugin._send_request({"type": "get_maid_status"}, timeout=5)
    if status_result.get("type") == "error":
        return {
            "verified": False,
            "verification_error": status_result.get("data", {}),
            "expected_item": expected_item,
        }

    maids = status_result.get("data", {}).get("maids", [])
    for maid in maids:
        if maid.get("id") == maid_id:
            plugin._maid_status_cache[maid_id] = maid
            current_main = maid.get("main_hand_item", "")
            current_off = maid.get("off_hand_item", "")
            return {
                "verified": _item_matches(current_main, expected_item),
                "expected_item": expected_item,
                "current_main_hand_item": current_main,
                "current_off_hand_item": current_off,
            }

    return {
        "verified": False,
        "verification_error": "Assigned maid was not present in status response",
        "expected_item": expected_item,
    }


def _item_matches(current_item, expected_item):
    current = str(current_item or "").strip().lower()
    expected = str(expected_item or "").strip().lower()
    if not current or not expected:
        return False
    return current == expected or current.split(":")[-1] == expected.split(":")[-1]


async def do_switch_schedule(plugin, *, schedule="all"):
    plugin.logger.info(f"[Entry] switch_schedule called with schedule='{schedule}'")
    if not plugin.connected:
        return Err("Not connected to Minecraft")
    maid_id = plugin._resolve_maid_id()
    if not maid_id:
        return Err("No maid assigned")
    result = await plugin._send_request({
        "type": "command_maid",
        "data": {"maid_id": maid_id, "command": "switch_schedule", "args": {"schedule": schedule}},
    })
    if result.get("type") == "error":
        return Err(str(result.get("data", {})))
    result_data = result.get("data", {})
    if result_data.get("success") is False:
        return Err(result_data.get("error", "Command failed"))
    return Ok({"success": True, "current_schedule": schedule})


async def do_equip_item(plugin, *, item="", slot=None):
    plugin.logger.info(f"[Entry] equip_item called with item='{item}', slot={slot}")
    if not plugin.connected:
        return Err("Not connected to Minecraft")
    maid_id = plugin._resolve_maid_id()
    if not maid_id:
        return Err("No maid assigned")
    args = {}
    requested_item = _normalize_item_id(item)
    if item:
        args["item"] = requested_item
    elif slot is not None:
        args["slot"] = slot
    else:
        return Err("请提供item或slot参数")
    result = await plugin._send_request({
        "type": "command_maid",
        "data": {"maid_id": maid_id, "command": "equip_item", "args": args},
    })
    result_data = result.get("data", {})
    if result.get("type") == "error":
        return Err(str(result.get("data", {})))
    if result_data.get("success") is False:
        if requested_item:
            verification = await _verify_equipped_item(plugin, maid_id, requested_item)
            if verification.get("verified"):
                return Ok({
                    "success": True,
                    "already_equipped": True,
                    "requested_item": requested_item,
                    **verification,
                })
        return Err(result_data.get("error", "Command failed"))
    equipped_item = str(result_data.get("equipped_item") or requested_item or "").strip()
    verification = await _verify_equipped_item(plugin, maid_id, equipped_item)
    if not verification.get("verified"):
        return {
            "output": {
                "success": False,
                "recoverable": True,
                "error": "Equip command returned success, but main-hand verification failed",
                "requested_item": requested_item or f"slot:{slot}",
                "raw_result": result_data,
                **verification,
                "retry_hint": (
                    "Do not tell the player the item is equipped. "
                    "If the player asked to hold a torch, explain the actual main-hand item and retry with item='minecraft:torch' or a precise inventory slot."
                ),
            },
            "is_error": True,
            "error": "EQUIP_VERIFY_FAILED",
        }
    return Ok({
        "success": True,
        "requested_item": requested_item or f"slot:{slot}",
        "equipped_item": equipped_item,
        **verification,
    })


async def do_send_chat(plugin, *, message, maid_id=None):
    if not plugin._chat_bubble_enabled and not plugin._chat_box_enabled:
        return Err("聊天功能已被管理员关闭（气泡和聊天框均未启用）")
    if not plugin.connected:
        return Err("Not connected to Minecraft")
    resolved_id = plugin._resolve_maid_id(maid_id)
    if not resolved_id:
        return Err("No maid_id available. Call mc_maid_status first or assign a maid in config.")
    result = await plugin._send_request({
        "type": "send_chat",
        "data": {"maid_id": resolved_id, "message": message},
    })
    if result.get("type") == "error":
        return Err(str(result.get("data", {})))
    result_data = result.get("data", {})
    if not result_data.get("success", False):
        return Err("Chat send failed")
    return Ok({"success": True})


async def do_game_context(plugin, category=None):
    if not plugin.connected:
        return {"output": {"error": "Not connected to Minecraft"}, "is_error": True, "error": "NOT_CONNECTED"}
    request_data = {"type": "get_game_context", "data": {}}
    if category:
        request_data["data"]["category"] = category
    maid_id = plugin._resolve_maid_id()
    if maid_id:
        request_data["data"]["maid_id"] = maid_id
    result = await plugin._send_request(request_data)
    if result.get("type") == "error":
        return {"output": result.get("data", {}), "is_error": True, "error": "REQUEST_FAILED"}
    return result.get("data", {})


def _maid_action_service(plugin):
    service = getattr(plugin, "_maid_action_service", None)
    if service is None:
        service = MaidActionService(plugin)
        plugin._maid_action_service = service
    return service


def _action_error(code, message, **details):
    return {
        "output": {"success": False, "error": message, **details},
        "is_error": True,
        "error": code,
    }


async def do_start_maid_action(
    plugin,
    *,
    kind="",
    args=None,
    action_id="",
    timeout_ms=60000,
    replace_existing=True,
    maid_id=None,
):
    """Validate and start a server-authoritative maid action."""
    if not plugin.connected:
        return _action_error("NOT_CONNECTED", "Not connected to Minecraft")
    resolved_id = plugin._resolve_maid_id(maid_id)
    if not resolved_id:
        return _action_error("NO_MAID_ASSIGNED", "No maid assigned")
    service = _maid_action_service(plugin)
    try:
        normalized_args = service.registry.normalize(kind, args or {})
        timeout_ms = int(timeout_ms)
    except (ActionValidationError, TypeError, ValueError) as exc:
        return _action_error("INVALID_ACTION_ARGUMENTS", str(exc))
    if timeout_ms < 1000 or timeout_ms > 120000:
        return _action_error(
            "INVALID_ACTION_ARGUMENTS", "timeout_ms must be between 1000 and 120000"
        )
    action_id = str(action_id or uuid.uuid4())
    request = {
        "type": "start_maid_action",
        "data": {
            "action_id": action_id,
            "maid_id": resolved_id,
            "kind": str(kind).strip().lower(),
            "timeout_ms": timeout_ms,
            "replace_existing": bool(replace_existing),
            "args": normalized_args,
        },
    }
    result = await plugin._send_request(request)
    if result.get("type") == "error":
        return _action_error("REQUEST_FAILED", str(result.get("data", {})), action_id=action_id)
    records = service.observe_response(result)
    result_data = dict(result.get("data", {}) or {})
    accepted = result_data.get("accepted", result_data.get("success", True))
    if not accepted:
        return _action_error(
            str(result_data.get("error_code") or "ACTION_REJECTED"),
            str(result_data.get("error") or result_data.get("message") or "Action rejected"),
            action_id=action_id,
            response=result_data,
        )
    record = records[0].as_dict() if records else {}
    return Ok({"accepted": True, "action_id": action_id, **result_data, **record})


async def do_cancel_maid_action(plugin, *, action_id="", maid_id=None):
    if not plugin.connected:
        return _action_error("NOT_CONNECTED", "Not connected to Minecraft")
    service = _maid_action_service(plugin)
    resolved_id = plugin._resolve_maid_id(maid_id)
    if not action_id:
        active = service.tracker.latest_active(resolved_id)
        if active is None:
            return _action_error("NO_ACTIVE_ACTION", "No active maid action to cancel")
        action_id = active.action_id
    data = {"action_id": str(action_id)}
    if resolved_id:
        data["maid_id"] = resolved_id
    result = await plugin._send_request({"type": "cancel_maid_action", "data": data})
    if result.get("type") == "error":
        return _action_error("REQUEST_FAILED", str(result.get("data", {})), action_id=action_id)
    service.observe_response(result)
    result_data = dict(result.get("data", {}) or {})
    accepted = result_data.get("accepted", result_data.get("success", True))
    if not accepted:
        return _action_error(
            str(result_data.get("error_code") or "CANCEL_REJECTED"),
            str(result_data.get("error") or result_data.get("message") or "Cancel rejected"),
            action_id=action_id,
            response=result_data,
        )
    return Ok({"accepted": True, "action_id": str(action_id), **result_data})


async def do_get_maid_action_status(plugin, *, action_id=""):
    if not plugin.connected:
        return _action_error("NOT_CONNECTED", "Not connected to Minecraft")
    if not str(action_id or "").strip():
        return _action_error("INVALID_ACTION_ARGUMENTS", "action_id is required")
    service = _maid_action_service(plugin)
    result = await plugin._send_request({
        "type": "get_maid_action_status",
        "data": {"action_id": str(action_id)},
    })
    if result.get("type") == "error":
        return _action_error("REQUEST_FAILED", str(result.get("data", {})), action_id=action_id)
    records = service.observe_response(result)
    result_data = dict(result.get("data", {}) or {})
    return Ok(records[0].as_dict() if records else result_data)


async def do_list_active_maid_actions(plugin, *, maid_id=None):
    if not plugin.connected:
        return _action_error("NOT_CONNECTED", "Not connected to Minecraft")
    service = _maid_action_service(plugin)
    resolved_id = plugin._resolve_maid_id(maid_id)
    data = {"maid_id": resolved_id} if resolved_id else {}
    result = await plugin._send_request({"type": "list_active_maid_actions", "data": data})
    if result.get("type") == "error":
        return _action_error("REQUEST_FAILED", str(result.get("data", {})))
    service.observe_response(result)
    result_data = dict(result.get("data", {}) or {})
    actions = result_data.get("actions", result_data.get("active_actions", []))
    return Ok({"actions": actions if isinstance(actions, list) else []})


async def do_use_skill(plugin, *, skill_name=""):
    plugin.logger.info(f"[Entry] use_skill called with skill_name='{skill_name}'")
    if not plugin.connected:
        return Err("Not connected to Minecraft")
    if not skill_name:
        return Err("请提供skill_name参数")
    maid_id = plugin._resolve_maid_id()
    request_data = {
        "type": "use_skill",
        "data": {"skill_name": skill_name},
    }
    if maid_id:
        request_data["data"]["maid_id"] = maid_id
    result = await plugin._send_request(request_data)
    if result.get("type") == "error":
        return Err(str(result.get("data", {})))
    result_data = result.get("data", {})
    if not result_data.get("success", False):
        return Err(result_data.get("error", "Skill not found"))
    return Ok({
        "skill_name": result_data.get("skill_name", skill_name),
        "description": result_data.get("description", ""),
        "body": result_data.get("body", ""),
        "references": result_data.get("references", {}),
    })


async def do_execute_command(plugin, *, command=""):
    plugin.logger.info(f"[Entry] execute_command called with command='{command}'")
    if not plugin.connected:
        return Err("Not connected to Minecraft")
    if not command:
        return Err("请提供command参数")
    result = await plugin._send_request(
        {"type": "execute_command", "data": {"command": command}},
        timeout=120,
    )
    if result.get("type") == "error":
        error_msg = result.get("data", {}).get("message", "Unknown error")
        if "disabled" in error_msg.lower():
            return Err("Command execution is disabled in Minecraft mod config")
        return Err(str(result.get("data", {})))
    result_data = result.get("data", {})
    if result_data.get("approved") is False:
        if result_data.get("expired"):
            return Err("Command request expired (no player confirmation within 120s)")
        rejected_by = result_data.get("rejected_by", "unknown")
        return Err(f"Command rejected by player {rejected_by}")
    return Ok({
        "approved": True,
        "success": result_data.get("success", True),
        "command": result_data.get("command", command),
        "result": result_data.get("result"),
        "approved_by": result_data.get("approved_by", ""),
    })


async def do_set_plan(plugin, *, plan=None, title=None, steps=None, completed_steps=None,
                      uncompleted_steps=None, append_steps=None, clear=False):
    preview = plan if plan is not None else title if title is not None else ""
    plugin.logger.info(f"[Entry] set_plan called with preview='{str(preview)[:80]}'")
    has_update = (
        clear or plan is not None or title is not None or steps is not None
        or completed_steps is not None or uncompleted_steps is not None
        or bool(append_steps)
    )
    if not has_update:
        return Ok({
            "success": False,
            "noop": True,
            "message": "No goal board update was requested. Ignore this result and do not mention it to the player.",
            **_plan.plan_summary(plugin._plan_state),
        })
    if not plugin.connected:
        return Err("Not connected to Minecraft")
    plan_state = _plan.update_plan_state(
        plugin._plan_state,
        plan=plan,
        title=title,
        steps=steps,
        completed_steps=completed_steps,
        uncompleted_steps=uncompleted_steps,
        append_steps=append_steps,
        clear=clear,
    )
    plan_text = _plan.plan_to_text(plan_state)
    result = await plugin._send_request({
        "type": "set_plan",
        "data": {"plan": plan_text},
    })
    if result.get("type") == "error":
        return Err(str(result.get("data", {})))
    result_data = result.get("data", {})
    if result_data.get("success") is False:
        return Err(result_data.get("error", "Set plan failed"))
    await plugin._apply_plan_state(plan_state, save=True)
    return Ok({"success": True, **_plan.plan_summary(plan_state)})
