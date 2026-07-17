"""N.E.K.O Minecraft 插件主模块 — 插件生命周期、消息分发、LLM 工具声明与 UI 入口"""

from plugin.sdk.plugin import (
    NekoPluginBase, neko_plugin, lifecycle, llm_tool,
    plugin_entry, ui, tr,
    Ok, Err, SdkError,
)
import asyncio
import sys
import uuid
from urllib.parse import urlsplit, urlunsplit

from .instructions import _TLM_AI_INSTRUCTIONS
from .bridge import WSBridge
from . import config as _config
from . import events as _events
from . import plan as _plan
from . import diagnostics as _diagnostics
from .awareness import AwarenessManager
from .maid_agent import MaidActionService
from .maid_agent.skill_feedback import SkillFeedbackHandler
from .maid_agent.skills import SkillRunner
from .maid_agent.skills.mine_ore import MineOreSkill
from .maid_activity import MaidActivityDirector
from . import tools as _tools
from .playmate import PlaymateContextManager, MinecraftPushRouter
from .playmate.debug_log import PlaymateDebugLogger

from .tool_defs import (
    MC_MAID_STATUS, MC_SWITCH_FOLLOW, MC_SWITCH_SIT,
    MC_SWITCH_TASK, MC_SWITCH_SCHEDULE, MC_EQUIP_ITEM,
    MC_SEND_CHAT, MC_GAME_CONTEXT, MC_MOVE_MAID_TO, MC_USE_SKILL, MC_EXECUTE_COMMAND,
    MC_SET_PLAN, MC_START_MAID_ACTION, MC_CANCEL_MAID_ACTION,
    MC_GET_MAID_ACTION_STATUS, MC_LIST_ACTIVE_MAID_ACTIONS,
    MC_START_SKILL, MC_CANCEL_SKILL, MC_GET_SKILL_STATUS, MC_LIST_SKILLS,
    MC_GET_MAID_ACTIVITY, MC_GET_MAID_CAPABILITIES,
    MC_SET_MAID_ACTIVITY, MC_STOP_MAID_ACTIVITY,
)

# respond 事件的 coalesce_key 映射：相同 key 的新推送覆盖旧的未消费推送
# 死亡/聊天/成就/维度切换不 coalesce（每条都是独立的重大事件）
_ALERT_EVENTS = {"maid_hurt", "player_hurt"}
_EVENT_COALESCE_KEYS = {
    "biome_change": "mc_event",
    "weather_change": "mc_event",
    "time_phase_change": "mc_event",
    "inventory_change": "mc_event",
    "item_fished": "mc_event",
}


def _event_coalesce_key(event_type):
    if event_type in _ALERT_EVENTS:
        return "mc_alert"
    return _EVENT_COALESCE_KEYS.get(event_type)


_UNDERGROUND_BIOME_STATES = {"mining", "underground_exploring"}
_UNDERGROUND_EVENT_KEYS = (
    "is_underground",
    "maid_is_underground",
    "player_is_underground",
    "underground",
)


def _coerce_optional_bool(value):
    if value is None:
        return None
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return bool(value)
    if isinstance(value, str):
        lowered = value.strip().lower()
        if lowered in ("true", "1", "yes", "y", "on"):
            return True
        if lowered in ("false", "0", "no", "n", "off"):
            return False
    return None


def _event_underground_hint(event_data):
    for key in _UNDERGROUND_EVENT_KEYS:
        if key in event_data:
            value = _coerce_optional_bool(event_data.get(key))
            if value is not None:
                return value
    return None


def _parse_step_index(value):
    value = str(value or "").strip()
    if not value:
        return None
    try:
        return [int(value)]
    except Exception:
        return None


def _ws_port_from_url(ws_url):
    try:
        port = urlsplit(str(ws_url or "")).port
    except Exception:
        return ""
    return str(port or "")


def _ws_url_with_port(ws_url, port):
    try:
        parsed = urlsplit(str(ws_url or ""))
    except Exception:
        parsed = None
    scheme = parsed.scheme if parsed and parsed.scheme in ("ws", "wss") else "ws"
    host = parsed.hostname if parsed and parsed.hostname else "127.0.0.1"
    if ":" in host and not host.startswith("["):
        host = f"[{host}]"
    path = parsed.path if parsed else ""
    query = parsed.query if parsed else ""
    fragment = parsed.fragment if parsed else ""
    return urlunsplit((scheme, f"{host}:{port}", path, query, fragment))


@neko_plugin
class NekoMinecraftPlugin(NekoPluginBase):

    def __init__(self, ctx):
        super().__init__(ctx)
        self.logger = ctx.logger
        self._bridge = None
        self._poll_task = None
        self._instruction_task = None
        self._maid_reconcile_task = None
        self._maid_status_refresh_task = None
        self._request_futures = {}
        self._maid_status_cache = {}
        self._ws_url = "ws://127.0.0.1:48920"
        self._heartbeat_interval = 30
        self._reconnect_interval = 5
        self._max_reconnect_interval = 60
        self._assigned_maid_id = ""
        self._assigned_maid_name = ""
        self._maid_agent_enabled = True
        self._command_execution_enabled = False
        self._chat_bubble_enabled = True
        self._chat_box_enabled = True
        self._instructions_injected = False
        self._companion_mode = "standard"
        self._awareness_interval = 60
        self._playmate_memory_items = 24
        self._playmate_memory_summary_length = 120
        self._playmate_memory_inject_items = 8
        self._playmate_memory_inject_chars = 700
        self._playmate_activity_debounce_checks = 2
        self._playmate_activity_cooldown = 120
        self._playmate_quiet_stable_seconds = 90
        self._playmate_quiet_cooldown = 300
        self._playmate_aggregate_window = 8
        self._playmate_throttle_window = 30
        self._playmate_throttle_limit = 6
        self._playmate_minigame_feedback_cooldown = 90
        self._playmate_minigame_context_chars = 90
        self._playmate_suggestion_cooldown = 600
        self._playmate_debug_log_enabled = False
        self._playmate_debug_log_max_bytes = 262144
        self._current_plan_text = ""
        self._plan_state = _plan.empty_plan()
        self._last_diagnostic = None
        self._last_refresh_status = None
        self._playmate_debug = PlaymateDebugLogger(self)
        self._minecraft_push = MinecraftPushRouter(self)
        self._playmate = PlaymateContextManager(self)
        self._awareness = AwarenessManager(self)
        self._maid_action_service = MaidActionService(self)
        self._skill_runner = None
        self._maid_activity_director = MaidActivityDirector(self)

    async def _load_config(self):
        await _config.load_config(self)

    async def _save_config(self):
        return await _config.save_config(self)

    def _refresh_playmate_modules(self):
        self._minecraft_push = MinecraftPushRouter(
            self,
            aggregate_window=self._playmate_aggregate_window,
            throttle_window=self._playmate_throttle_window,
            throttle_limit=self._playmate_throttle_limit,
        )
        self._playmate = PlaymateContextManager(self)
        self._playmate._current_plan = self._current_plan_text

    async def _restart_bridge(self):
        for request_id, future in list(self._request_futures.items()):
            if not future.done():
                future.set_result({"type": "error", "data": {"message": "Bridge restarted, please retry"}})
        self._request_futures.clear()
        self._maid_status_cache = {}
        self._last_diagnostic = None
        self._instructions_injected = False
        if self._instruction_task and not self._instruction_task.done():
            self._instruction_task.cancel()
        self._instruction_task = None
        old_bridge = self._bridge
        self._bridge = None
        if old_bridge:
            await asyncio.to_thread(old_bridge.stop)
        self._bridge = WSBridge(
            ws_url=self._ws_url, logger=self.logger,
            heartbeat_interval=self._heartbeat_interval,
            reconnect_interval=self._reconnect_interval,
            max_reconnect_interval=self._max_reconnect_interval,
        )
        self._bridge.start()

    async def _push_minecraft_context(self, text, ai_behavior="read", priority=1, metadata=None, aggregate=None, coalesce_key=None):
        return await self._minecraft_push.push(
            text,
            ai_behavior=ai_behavior,
            priority=priority,
            metadata=metadata,
            aggregate=aggregate,
            coalesce_key=coalesce_key,
        )

    @lifecycle(id="startup")
    async def on_startup(self, **_):
        await self._load_config()
        self._refresh_playmate_modules()
        await self._initialize_skill_runner()
        self.logger.info(f"Python {sys.version}")
        self.logger.info(f"Event loop: {type(asyncio.get_event_loop())}")
        if self._assigned_maid_id:
            self.logger.info(f"[Config] Assigned maid: {self._assigned_maid_name} ({self._assigned_maid_id})")
        if self._poll_task and not self._poll_task.done():
            self._poll_task.cancel()
        if self._bridge:
            self._bridge.stop()
        self._bridge = WSBridge(
            ws_url=self._ws_url, logger=self.logger,
            heartbeat_interval=self._heartbeat_interval,
            reconnect_interval=self._reconnect_interval,
            max_reconnect_interval=self._max_reconnect_interval,
        )
        self._bridge.start()
        self._poll_task = asyncio.create_task(self._poll_messages())
        self._instructions_injected = False
        self._re_register_llm_tools()
        asyncio.create_task(self._delayed_re_register_llm_tools(5))
        self.logger.info(f"[Startup] Bridge started, maid_id={self._assigned_maid_id}")
        return Ok({"status": "ready"})

    def _re_register_llm_tools(self):
        """重新发送 LLM_TOOL_REGISTER IPC，补救 __init__ 阶段失败的注册。

        SDK 在 __init__ 中自动注册 @llm_tool 方法，但宿主向 main_server
        /api/tools/register 发 HTTP POST 时可能因 role session 尚未就绪而
        失败（返回 ok=False, "no role accepted the registration"）。
        SDK 没有重试机制，这里在 startup 生命周期中重发一次 IPC 通知，
        此时 main_server 的 role session 通常已就绪。
        """
        if not self._llm_tools:
            return
        for meta in self._llm_tools.values():
            self._notify_llm_tool_registered(meta)
        self.logger.info(f"[Startup] Re-emitted {len(self._llm_tools)} LLM tool registrations")

    async def _delayed_re_register_llm_tools(self, delay=5):
        """延迟后再次重发注册，兜底 startup 阶段 main_server 仍未就绪的边界情况。"""
        try:
            await asyncio.sleep(delay)
            self._re_register_llm_tools()
        except asyncio.CancelledError:
            pass

    async def _on_command_loop_start(self):
        self.logger.info("[CommandLoop] Starting message poll on command loop")
        if self._poll_task and not self._poll_task.done():
            self._poll_task.cancel()
        self._poll_task = asyncio.create_task(self._poll_messages())
        self._awareness.start()

    @lifecycle(id="shutdown")
    async def on_shutdown(self, **_):
        await self._maid_activity_director.close()
        if self._skill_runner is not None:
            await self._skill_runner.close()
        if self._poll_task:
            self._poll_task.cancel()
            try:
                await self._poll_task
            except asyncio.CancelledError:
                pass
        if self._maid_status_refresh_task:
            self._maid_status_refresh_task.cancel()
            try:
                await self._maid_status_refresh_task
            except asyncio.CancelledError:
                pass
        if self._maid_reconcile_task and not self._maid_reconcile_task.done():
            self._maid_reconcile_task.cancel()
            try:
                await self._maid_reconcile_task
            except asyncio.CancelledError:
                pass
        if self._instruction_task and not self._instruction_task.done():
            self._instruction_task.cancel()
            try:
                await self._instruction_task
            except asyncio.CancelledError:
                pass
        self._awareness.stop()
        if self._bridge:
            self._bridge.stop()
        await self._minecraft_push.flush()
        return Ok({"status": "stopped"})

    async def _initialize_skill_runner(self):
        if self._skill_runner is not None:
            await self._skill_runner.close()
        runner = SkillRunner(
            self,
            self._maid_action_service,
            self.data_path("skills"),
            feedback=SkillFeedbackHandler(self),
        )
        runner.register(MineOreSkill())
        await runner.load()
        self._skill_runner = runner

    async def _poll_messages(self):
        was_connected = False
        while True:
            try:
                if self._bridge:
                    is_connected = self._bridge.connected
                    if is_connected and not was_connected:
                        await self._on_bridge_reconnect()
                    was_connected = is_connected
                    for data in self._bridge.drain():
                        await self._handle_message(data)
                    if (is_connected and not self._instructions_injected and
                            (self._instruction_task is None or self._instruction_task.done())):
                        # 注入过程会发送请求；后台运行才能让本轮询循环继续分发响应。
                        self._instruction_task = asyncio.create_task(self._inject_instructions())
                await asyncio.sleep(0.1)
            except asyncio.CancelledError:
                break
            except Exception as e:
                self.logger.error(f"Poll error: {e}")
                await asyncio.sleep(1)

    async def _on_bridge_reconnect(self):
        """Bridge 重连后清理运行时状态，避免跨存档/跨会话串状态"""
        self.logger.info("[Bridge] Reconnected, resetting runtime state")
        # 取消所有待处理请求（MC 端已丢失这些请求的上下文，等满超时只会卡住 LLM）
        for request_id, future in list(self._request_futures.items()):
            if not future.done():
                future.set_result({"type": "error", "data": {"message": "Connection reset, please retry"}})
        self._request_futures.clear()
        # 清理女仆状态缓存
        self._maid_status_cache = {}
        # 清理感知状态
        if self._awareness:
            self._awareness._last_awareness_state = {}
            self._awareness._pending_revenge = None
            self._awareness._was_dead = False
        # 清理活动推断（重置为 unknown，让新存档重新推断）
        if self._playmate and hasattr(self._playmate, 'activity') and self._playmate.activity:
            self._playmate.activity._stable_state = "unknown"
            self._playmate.activity._candidate_state = "unknown"
            self._playmate.activity._candidate_count = 0
        # 重置指令注入标志，重连后重新注入
        self._instructions_injected = False
        if self._instruction_task and not self._instruction_task.done():
            self._instruction_task.cancel()
        self._instruction_task = None
        # 对账依赖轮询循环继续消费响应，不能在此同步等待。
        if self._maid_reconcile_task and not self._maid_reconcile_task.done():
            self._maid_reconcile_task.cancel()
        self._maid_reconcile_task = asyncio.create_task(self._reconcile_maid_actions())

    async def _reconcile_maid_actions(self):
        for attempt, delay in enumerate((0, 1, 2, 5), start=1):
            if delay:
                await asyncio.sleep(delay)
            try:
                expected_action_ids = []
                expected_maid_ids = []
                if self._skill_runner is not None:
                    for skill in self._skill_runner.list_skills(include_terminal=False):
                        action_id = str(skill.get("current_action_id") or "")
                        maid_id = str(skill.get("maid_id") or "")
                        if action_id:
                            expected_action_ids.append(action_id)
                        if maid_id:
                            expected_maid_ids.append(maid_id)
                result = await self._maid_action_service.reconcile(
                    expected_action_ids=expected_action_ids,
                    maid_ids=expected_maid_ids,
                )
                if result.get("success", False) and not result.get("unresolved"):
                    if self._skill_runner is None:
                        return
                    skill_result = await self._skill_runner.reconcile()
                    if skill_result.get("success", False):
                        return
                    result = {**result, "skill_reconcile": skill_result}
                self.logger.warning(
                    f"[MaidAgent] Reconcile attempt {attempt} deferred: {result}")
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                self.logger.warning(
                    f"[MaidAgent] Reconcile attempt {attempt} failed: {exc}")

    async def _inject_instructions(self):
        self._instructions_injected = True
        try:
            config_result = await self._send_request({"type": "get_config"}, timeout=5)
            if config_result.get("type") == "config":
                _config.sync_config(self, config_result.get("data", {}))
        except Exception:
            pass
        if self._assigned_maid_id:
            try:
                await self._send_request({
                    "type": "set_monitored_maid",
                    "data": {"maid_id": self._assigned_maid_id},
                }, timeout=5)
            except Exception:
                pass
        # 桥接连通时主动拉取一次女仆状态，避免首次连接/重连时女仆尚未加载导致面板为空
        try:
            await self._refresh_maid_status_cache()
        except Exception:
            pass
        # 目标板双向同步：先查询 MC 端，有则采用 MC 端的；无则推送插件侧的
        try:
            plan_result = await self._send_request({"type": "get_plan"}, timeout=5)
            mc_plan = ""
            if plan_result.get("type") == "plan_result":
                mc_plan = plan_result.get("data", {}).get("plan", "")
        except Exception:
            mc_plan = ""
        if mc_plan:
            await self._apply_plan_text(mc_plan, save=True)
        elif self._current_plan_text and self._bridge and self._bridge.connected:
            self._bridge.send({
                "type": "set_plan",
                "data": {"plan": self._current_plan_text},
            })
        instructions = _TLM_AI_INSTRUCTIONS
        if self._assigned_maid_id and self._assigned_maid_name:
            instructions += (
                f"\n\n## 当前配置\n你已被指定为女仆「{self._assigned_maid_name}」"
                f"（maid_id={self._assigned_maid_id}）。所有需要 maid_id 的操作会自动使用此 ID，你无需再调用 mc_maid_status 获取。\n"
            )
        self.push_message(
            source="minecraft", ai_behavior="read",
            parts=[{"type": "text", "text": instructions}], priority=0,
        )
        self.logger.info("[TLM] Injected AI calling instructions into LLM context")

    async def _handle_message(self, data):
        if await self._maid_action_service.handle_message(data):
            return
        msg_type = data.get("type", "")
        request_id = data.get("request_id")
        if msg_type == "pong":
            return
        if msg_type in ("maid_status", "game_context", "command_result",
                        "chat_result", "skill_result", "command_execution_result",
                        "attack_target_result", "maid_action_start_result",
                        "maid_action_cancel_result", "maid_action_status",
                        "maid_action_list", "error"):
            if request_id and request_id in self._request_futures:
                self._request_futures[request_id].set_result(data)
                del self._request_futures[request_id]
            return
        if msg_type == "config":
            _config.sync_config(self, data.get("data", {}))
            if request_id and request_id in self._request_futures:
                self._request_futures[request_id].set_result(data)
                del self._request_futures[request_id]
            return
        if msg_type == "config_update":
            _config.sync_config(self, data.get("data", {}))
            return
        if msg_type == "plan_update":
            plan_text = data.get("data", {}).get("plan", "")
            await self._apply_plan_text(plan_text, save=True)
            return
        if msg_type == "event":
            event_data = data.get("data", {})
            if event_data.get("event_type") == "player_login":
                if not _events.event_matches_assigned_maid(
                    event_data, self._assigned_maid_id
                ):
                    return
                await self._handle_player_login_event(event_data)
                return
            await self._handle_event(data)
            return
        if msg_type == "chat_message":
            chat_data = data.get("data", {})
            sender = chat_data.get("sender", "unknown")
            message = chat_data.get("message", "")
            text = f"{sender}说了: {message}"
            self._playmate_debug.record("chat_message", sender=sender, message=message, route="respond", priority=7)
            self._playmate.remember_event("chat", text, priority=7)
            await self._push_minecraft_context(
                text,
                ai_behavior="respond",
                metadata={"description": f"Minecraft聊天消息 - {sender}: {message}"},
                priority=7,
            )
            return
        if request_id and request_id in self._request_futures:
            self._request_futures[request_id].set_result(data)
            del self._request_futures[request_id]

    async def _handle_player_login_event(self, event_data):
        """玩家登录事件：刷新女仆状态缓存，让刚进世界的用户能被正确检测到。"""
        player_name = event_data.get("player_name", "unknown")
        self.logger.info(f"[Event] Player logged in: {player_name}")
        found = await self._refresh_maid_status_cache()
        if not found:
            # 女仆实体可能还在加载，延迟 2 秒再扫一次
            asyncio.create_task(self._delayed_refresh_maid_status(2))

    async def _handle_event(self, data):
        event_data = data.get("data", {})
        parts_text, priority, side_effects = _events.format_event(
            event_data, self._assigned_maid_id
        )
        if parts_text is None:
            return
        event_type = event_data.get("event_type", "event")
        if event_type == "biome_change" and self._should_suppress_biome_change_event(event_data):
            biome = str(event_data.get("biome", ""))
            text = f"Suppressed underground biome_change: {biome}"
            self._playmate_debug.record(
                "event",
                event_type=event_type,
                priority=0,
                text=text,
                route="suppressed_underground_biome",
            )
            self.logger.info(f"[Event] {text}")
            return
        debug_base = {"event_type": event_type, "priority": priority, "text": parts_text[:160]}
        if side_effects:
            if "pending_revenge" in side_effects:
                self._awareness._pending_revenge = side_effects["pending_revenge"]
            if "was_dead" in side_effects:
                self._awareness._was_dead = side_effects["was_dead"]
            if side_effects.get("evidence_only"):
                self._playmate.remember_event(event_type, parts_text, priority=priority)
                self._playmate_debug.record("event", **debug_base, route="evidence_only")
                self.logger.info(f"[Playmate] Evidence event stored: {event_type}, text={parts_text[:80]}")
                return

        # 棋局事件特殊处理
        if side_effects.get("chess_event"):
            chess_event_type = side_effects.get("chess_event_type", "")
            context_text = self._playmate.remember_minigame_event(
                event_data,
                parts_text,
                priority,
                side_effects,
            )

            self.logger.info(
                f"[Chess] Event: {chess_event_type}, "
                f"game={event_data.get('game_type', '?')}, "
                f"priority={priority}, feedback={bool(context_text)}, "
                f"text={parts_text[:80]}"
            )

            if not context_text:
                self._playmate_debug.record("event", **debug_base, route="minigame_suppressed")
                return

            ai_behavior = side_effects.get("ai_behavior", "read")
            self._playmate_debug.record("event", **debug_base, route="minigame", ai_behavior=ai_behavior, context_text=context_text[:160])
            await self._push_minecraft_context(
                context_text,
                ai_behavior=ai_behavior,
                priority=priority if ai_behavior == "respond" else 2,
                aggregate=ai_behavior != "respond",
                coalesce_key="mc_chess",
            )
            return

        self._playmate.remember_event(event_type, parts_text, priority=priority)
        self._playmate_debug.record("event", **debug_base, route="respond", ai_behavior="respond")

        await self._push_minecraft_context(
            parts_text,
            ai_behavior="respond",
            priority=priority,
            coalesce_key=_event_coalesce_key(event_type),
        )

    async def _send(self, data):
        if self._bridge and self._bridge.connected:
            self._bridge.send(data)

    async def _send_request(self, data, timeout=30):
        if not self._bridge or not self._bridge.connected:
            return {"type": "error", "data": {"message": "Not connected to Minecraft"}}
        request_id = str(uuid.uuid4())
        data["request_id"] = request_id
        future = asyncio.get_event_loop().create_future()
        self._request_futures[request_id] = future
        self._bridge.send(data)
        try:
            return await asyncio.wait_for(future, timeout=timeout)
        except asyncio.TimeoutError:
            self._request_futures.pop(request_id, None)
            return {"type": "error", "data": {"message": "Request timed out"}}
        except asyncio.CancelledError:
            self._request_futures.pop(request_id, None)
            if not future.done():
                future.cancel()
            raise

    @property
    def connected(self):
        return bool(self._bridge and self._bridge.connected)

    def _resolve_maid_id(self, maid_id=None):
        if maid_id:
            return maid_id
        if self._assigned_maid_id:
            return self._assigned_maid_id
        return self._get_cached_maid_id()

    async def _apply_plan_state(self, state, save=False):
        self._plan_state = _plan.normalize_plan_state(state)
        self._current_plan_text = _plan.plan_to_text(self._plan_state)
        if self._playmate:
            self._playmate._current_plan = self._current_plan_text
        if save:
            await self._save_config()
        return self._current_plan_text

    async def _apply_plan_text(self, plan_text, save=False):
        self._current_plan_text = str(plan_text or "")
        self._plan_state = _plan.plan_from_text(self._current_plan_text)
        if self._playmate:
            self._playmate._current_plan = self._current_plan_text
        if save:
            await self._save_config()
        return self._current_plan_text

    def _get_cached_maid_id(self):
        if self._assigned_maid_id:
            return self._assigned_maid_id
        if self._maid_status_cache:
            first_id = next(iter(self._maid_status_cache.values()), None)
            if first_id:
                return first_id.get("id", "")
        return ""

    async def _refresh_maid_status_cache(self):
        """向 mod 查询女仆状态并更新本地缓存，供面板和工具使用。"""
        if not self.connected:
            return False
        try:
            result = await self._send_request({"type": "get_maid_status"}, timeout=5)
            if result.get("type") == "error":
                return False
            maids = result.get("data", {}).get("maids", [])
            for maid in maids:
                self._maid_status_cache[maid.get("id", "")] = maid
            if maids:
                self.logger.info(f"[MaidStatus] Refreshed cache, {len(maids)} maid(s) found")
            return bool(maids)
        except Exception as e:
            self.logger.warning(f"[MaidStatus] Failed to refresh cache: {e}")
            return False

    def _schedule_maid_status_refresh(self):
        if not self.connected:
            return
        if self._maid_status_refresh_task and not self._maid_status_refresh_task.done():
            return
        self._maid_status_refresh_task = asyncio.create_task(self._refresh_maid_status_cache())

    async def _delayed_refresh_maid_status(self, delay):
        """延迟刷新女仆状态，用于玩家登录后女仆实体尚未完全加载的场景。"""
        try:
            await asyncio.sleep(delay)
            await self._refresh_maid_status_cache()
        except asyncio.CancelledError:
            pass
        except Exception as e:
            self.logger.warning(f"[MaidStatus] Delayed refresh failed: {e}")

    def _should_suppress_biome_change_event(self, event_data):
        explicit = _event_underground_hint(event_data or {})
        if explicit is not None:
            return explicit
        awareness_state = getattr(self._awareness, "_last_awareness_state", {}) or {}
        if awareness_state.get("is_underground"):
            return True
        activity = getattr(getattr(self._playmate, "activity", None), "stable_state", "unknown")
        return activity in _UNDERGROUND_BIOME_STATES

    # ── UI 上下文与 actions ──

    @ui.context(id="dashboard")
    async def dashboard_context(self, **_):
        if self.connected and not self._maid_status_cache:
            self._schedule_maid_status_refresh()
        maids = []
        for maid in self._maid_status_cache.values():
            maids.append({
                "id": maid.get("id", ""), "name": maid.get("name", ""),
                "health": maid.get("health", 0), "max_health": maid.get("max_health", 0),
                "is_sitting": maid.get("is_sitting", False),
                "is_following": maid.get("is_following", False),
                "owner": maid.get("owner", ""),
            })
        return {
            "connected": self.connected,
            "ws_url": self._ws_url,
            "ws_port": _ws_port_from_url(self._ws_url),
            "maids": maids,
            "assigned_maid_id": self._assigned_maid_id,
            "assigned_maid_name": self._assigned_maid_name,
            "maid_agent_enabled": self._maid_agent_enabled,
            "command_execution_enabled": self._command_execution_enabled,
            "companion_mode": self._companion_mode,
            "companion_settings": _config.companion_settings(self),
            "plan_state": self._plan_state,
            "plan_summary": _plan.plan_summary(self._plan_state),
            "last_diagnostic": self._last_diagnostic,
            "last_refresh_status": self._last_refresh_status,
        }

    @ui.action(id="refresh_maid_status", label=tr("actions.refresh", default="Refresh Status"), tone="primary", refresh_context=True)
    @plugin_entry(id="refresh_maid_status", name=tr("entries.refresh.name", default="Refresh Maid Status"), description="Fetch current maid status and true current work mode from Minecraft. When the user asks what mode the maid is in, answer from current_mode/current_mode_answer, not from previous intent.", input_schema={"type": "object", "properties": {}}, llm_result_fields=["current_mode", "current_mode_answer", "selected_maid", "available_modes", "maids"])
    async def refresh_maid_status(self, **_):
        if not self.connected:
            self._last_refresh_status = {
                "status": "warning",
                "message": "尚未连接到 Minecraft。请确认游戏已进入存档且 N.E.K.O 桥接已启用，然后重试刷新。",
            }
            return Ok({"success": False, **self._last_refresh_status})
        result = await self._send_request({"type": "get_maid_status"}, timeout=4)
        if result.get("type") == "error":
            raw_message = str(result.get("data", {}).get("message", ""))
            if raw_message == "Request timed out":
                message = "刷新状态超时：Minecraft 端暂时没有响应。请确认游戏没有卡顿、已进入存档，稍后点击“刷新状态”重试。"
            else:
                message = raw_message or "刷新状态失败，请稍后重试。"
            self._last_refresh_status = {"status": "warning", "message": message}
            return Ok({"success": False, **self._last_refresh_status})
        maids = result.get("data", {}).get("maids", [])
        for maid in maids:
            self._maid_status_cache[maid.get("id", "")] = maid
        self._last_refresh_status = {
            "status": "success",
            "message": f"刷新完成，找到 {len(maids)} 个女仆。" if maids else "刷新完成，但当前世界未检测到女仆。",
        }
        return Ok({**_tools.maid_status_payload(self, maids, compact=True), **self._last_refresh_status})

    @ui.action(id="assign_maid", label=tr("actions.assignMaid", default="Assign Maid"), tone="primary", refresh_context=True)
    @plugin_entry(id="assign_maid", name=tr("entries.assign.name", default="Assign Maid"), description="Assign a specific maid by ID for the AI to control. ONLY use this tool when you need to CHANGE the current maid or no maid is assigned. If a maid is already assigned in the config, you do NOT need to call this tool; just proceed with the task directly.", input_schema={"type": "object", "properties": {"maid_id": {"type": "string", "description": "The maid entity ID (UUID) to assign"}, "maid_name": {"type": "string", "description": "The maid name for display"}}, "required": []}, llm_result_fields=["assigned_maid_id", "assigned_maid_name"])
    async def assign_maid(self, *, maid_id="", maid_name="", **_):
        if not maid_id:
            if self._assigned_maid_id:
                return Ok({"assigned_maid_id": self._assigned_maid_id, "assigned_maid_name": self._assigned_maid_name, "message": "Already assigned. No change."})
            return Err("maid_id is required")
        self._assigned_maid_id = maid_id
        self._assigned_maid_name = maid_name
        await self._save_config()
        self._instructions_injected = False
        self.logger.info(f"[Config] Assigned maid: {maid_name} ({maid_id})")
        if self._bridge and self._bridge.connected and maid_id:
            self._bridge.send({"type": "set_monitored_maid", "data": {"maid_id": maid_id}})
        return Ok({"assigned_maid_id": maid_id, "assigned_maid_name": maid_name})

    @ui.action(id="set_connection_port", label=tr("actions.setConnectionPort", default="Save Port"), tone="primary", refresh_context=True)
    @plugin_entry(
        id="set_connection_port",
        name=tr("entries.setConnectionPort.name", default="Save Bridge Port"),
        description="Save the plugin UI connection port for the Minecraft WebSocket bridge. Only use when the user explicitly asks to change the bridge port; this is not a gameplay or maid-control action.",
        input_schema={
            "type": "object",
            "properties": {
                "port": {
                    "type": "string",
                    "description": "WebSocket port number, 1-65535.",
                },
            },
            "required": ["port"],
        },
        llm_result_fields=["ws_url", "ws_port", "restarted", "saved"],
    )
    async def set_connection_port(self, *, port="", **_):
        raw_port = str(port or "").strip()
        try:
            port_number = int(raw_port)
        except Exception:
            return Err("Port must be a number")
        if port_number < 1 or port_number > 65535:
            return Err("Port must be between 1 and 65535")

        old_url = self._ws_url
        new_url = _ws_url_with_port(old_url, port_number)
        self._ws_url = new_url
        saved = await self._save_config()
        if not saved:
            self._ws_url = old_url
            return Err("Failed to save connection port config")
        restarted = new_url != old_url
        if restarted:
            await self._restart_bridge()
            self.logger.info(f"[Config] WebSocket port set to {port_number}, bridge restarted: {new_url}")
        else:
            self.logger.info(f"[Config] WebSocket port unchanged: {new_url}")
        return Ok({
            "ws_url": self._ws_url,
            "ws_port": str(port_number),
            "restarted": restarted,
            "saved": saved,
        })

    @ui.action(id="diagnose_bridge", label=tr("actions.diagnose", default="Diagnose Bridge"), tone="primary", refresh_context=True)
    @plugin_entry(id="diagnose_bridge", name=tr("entries.diagnose.name", default="Diagnose Minecraft Bridge"), description="Diagnose the Minecraft bridge connection, mod config, assigned maid, and plugin-side companion mode. This does not diagnose N.E.K.O host model/TTS behavior.", input_schema={"type": "object", "properties": {}}, llm_result_fields=["status", "summary", "checks"])
    async def diagnose_bridge(self, **_):
        result = await _diagnostics.diagnose_bridge(self)
        self._last_diagnostic = result
        return Ok(result)

    @ui.action(id="apply_speech_frequency_preset", label=tr("actions.applySpeechPreset", default="Apply Preset"), tone="primary", refresh_context=True)
    @plugin_entry(
        id="apply_speech_frequency_preset",
        name=tr("entries.applySpeechPreset.name", default="Apply Speech Frequency Preset"),
        description="Apply a companion mode preset (quiet/standard/active) or custom companion speech frequency settings from the UI panel.",
        input_schema={
            "type": "object",
            "properties": {
                "mode": {"type": "string", "description": "Companion mode: quiet, standard, active, or custom"},
                "playmate_quiet_stable_seconds": {"type": "integer", "description": "Custom: seconds of quiet before companion speech can trigger"},
                "playmate_quiet_cooldown": {"type": "integer", "description": "Custom: minimum interval between idle-state proactive messages"},
                "playmate_suggestion_cooldown": {"type": "integer", "description": "Custom: minimum interval between proactive suggestion messages"},
            },
            "required": ["mode"],
        },
    )
    async def apply_speech_frequency_preset(self, *, mode="", **kwargs):
        mode = _config._normalize_companion_mode(mode, self)
        self._companion_mode = mode
        _config._apply_companion_mode(self)
        if mode == "custom":
            _config.apply_custom_companion_settings(self, kwargs)
        was_awareness_running = bool(self._awareness and self._awareness._task and not self._awareness._task.done())
        if was_awareness_running:
            self._awareness.stop()
        await self._minecraft_push.flush()
        self._refresh_playmate_modules()
        if was_awareness_running:
            self._awareness.start()
        await self._save_config()
        self.logger.info(f"[Config] Companion mode set to {mode}")
        return Ok({
            "companion_mode": mode,
            "companion_settings": _config.companion_settings(self),
        })

    @ui.action(id="set_plan_board", label=tr("actions.setPlanBoard", default="Update Goal Board"), tone="primary", refresh_context=True)
    @plugin_entry(
        id="set_plan_board",
        name=tr("entries.setPlanBoard.name", default="Update Goal Board"),
        description="Update the Minecraft goal board from the UI panel: set title, append a step, mark steps complete/incomplete, or clear.",
        input_schema={
            "type": "object",
            "properties": {
                "title": {"type": "string", "description": "New title for the goal board"},
                "plan": {"type": "string", "description": "Full plan text. Empty string clears the goal board"},
                "append_step": {"type": "string", "description": "Step text to append"},
                "completed_step": {"type": "string", "description": "1-based step number to mark as completed"},
                "uncompleted_step": {"type": "string", "description": "1-based step number to mark as incomplete"},
                "clear": {"type": "boolean", "description": "Clear the entire goal board"},
            },
            "required": [],
        },
    )
    async def set_plan_board(self, *, plan=None, title=None, append_step="", completed_step="", uncompleted_step="", clear=False, **_):
        completed_steps = _parse_step_index(completed_step)
        uncompleted_steps = _parse_step_index(uncompleted_step)
        append_steps = [append_step] if str(append_step or "").strip() else None
        clear_flag = _coerce_optional_bool(clear)
        return await _tools.do_set_plan(
            self,
            plan=plan,
            title=title,
            append_steps=append_steps,
            completed_steps=completed_steps,
            uncompleted_steps=uncompleted_steps,
            clear=bool(clear_flag),
        )

    # ── LLM 工具 ──

    @llm_tool(**MC_MAID_STATUS)
    async def mc_maid_status(self, **_):
        return await _tools.do_maid_status(self)

    @llm_tool(**MC_SWITCH_FOLLOW)
    async def switch_follow(self, *, action="follow", **_):
        return await _tools.do_switch_follow(self, action=action)

    @llm_tool(**MC_SWITCH_SIT)
    async def switch_sit(self, *, action="sit", **_):
        return await _tools.do_switch_sit(self, action=action)

    @llm_tool(**MC_SWITCH_TASK)
    async def switch_task(self, *, task="", **_):
        return await _tools.do_switch_task(self, task=task)

    @llm_tool(**MC_SWITCH_SCHEDULE)
    async def switch_schedule(self, *, schedule="all", **_):
        return await _tools.do_switch_schedule(self, schedule=schedule)

    @llm_tool(**MC_EQUIP_ITEM)
    async def equip_item(self, *, item="", slot=None, **_):
        return await _tools.do_equip_item(self, item=item, slot=slot)

    @llm_tool(**MC_SEND_CHAT)
    async def mc_send_chat(self, *, message, maid_id=None, **_):
        return await _tools.do_send_chat(self, message=message, maid_id=maid_id)

    @llm_tool(**MC_GAME_CONTEXT)
    async def mc_game_context(self, category=None, **_):
        return await _tools.do_game_context(self, category=category)

    @llm_tool(**MC_MOVE_MAID_TO)
    async def mc_move_maid_to(self, *, destination="", **_):
        return await _tools.do_move_maid_to(self, destination=destination)

    @llm_tool(**MC_START_MAID_ACTION)
    async def mc_start_maid_action(self, *, kind="", args=None, action_id="",
                                   timeout_ms=None, replace_existing=True, **_):
        return await _tools.do_start_maid_action(
            self,
            kind=kind,
            args=args,
            action_id=action_id,
            timeout_ms=timeout_ms,
            replace_existing=replace_existing,
        )

    @llm_tool(**MC_CANCEL_MAID_ACTION)
    async def mc_cancel_maid_action(self, *, action_id="", **_):
        return await _tools.do_cancel_maid_action(self, action_id=action_id)

    @llm_tool(**MC_GET_MAID_ACTION_STATUS)
    async def mc_get_maid_action_status(self, *, action_id="", **_):
        return await _tools.do_get_maid_action_status(self, action_id=action_id)

    @llm_tool(**MC_LIST_ACTIVE_MAID_ACTIONS)
    async def mc_list_active_maid_actions(self, **_):
        return await _tools.do_list_active_maid_actions(self)

    @llm_tool(**MC_START_SKILL)
    async def mc_start_skill(self, *, skill="", args=None, skill_id="",
                             replace_existing=True, **_):
        return await _tools.do_start_skill(
            self,
            skill=skill,
            args=args,
            skill_id=skill_id,
            replace_existing=replace_existing,
        )

    @llm_tool(**MC_CANCEL_SKILL)
    async def mc_cancel_skill(self, *, skill_id="", **_):
        return await _tools.do_cancel_skill(self, skill_id=skill_id)

    @llm_tool(**MC_GET_SKILL_STATUS)
    async def mc_get_skill_status(self, *, skill_id="", **_):
        return await _tools.do_get_skill_status(self, skill_id=skill_id)

    @llm_tool(**MC_LIST_SKILLS)
    async def mc_list_skills(self, *, include_terminal=True, **_):
        return await _tools.do_list_skills(
            self, include_terminal=include_terminal
        )

    @llm_tool(**MC_GET_MAID_ACTIVITY)
    async def mc_get_maid_activity(self, **_):
        return await _tools.do_get_maid_activity(self)

    @llm_tool(**MC_GET_MAID_CAPABILITIES)
    async def mc_get_maid_capabilities(self, **_):
        return await _tools.do_get_maid_capabilities(self)

    @llm_tool(**MC_SET_MAID_ACTIVITY)
    async def mc_set_maid_activity(
        self, *, activity_type="", task="", kind="", skill="", args=None,
        switch_policy="cancel_then_switch", request_id="", **_
    ):
        return await _tools.do_set_maid_activity(
            self,
            activity_type=activity_type,
            task=task,
            kind=kind,
            skill=skill,
            args=args,
            switch_policy=switch_policy,
            request_id=request_id,
        )

    @llm_tool(**MC_STOP_MAID_ACTIVITY)
    async def mc_stop_maid_activity(
        self, *, switch_to_idle=True, request_id="", **_
    ):
        return await _tools.do_stop_maid_activity(
            self,
            switch_to_idle=switch_to_idle,
            request_id=request_id,
        )

    @llm_tool(**MC_USE_SKILL)
    async def use_skill(self, *, skill_name="", **_):
        return await _tools.do_use_skill(self, skill_name=skill_name)

    @llm_tool(**MC_EXECUTE_COMMAND)
    async def execute_command(self, *, command="", **_):
        return await _tools.do_execute_command(self, command=command)

    @llm_tool(**MC_SET_PLAN)
    async def set_plan(self, *, plan=None, title=None, steps=None, completed_steps=None,
                       uncompleted_steps=None, append_steps=None, clear=False, **_):
        return await _tools.do_set_plan(
            self,
            plan=plan,
            title=title,
            steps=steps,
            completed_steps=completed_steps,
            uncompleted_steps=uncompleted_steps,
            append_steps=append_steps,
            clear=clear,
        )
