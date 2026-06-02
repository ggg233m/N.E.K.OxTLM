from plugin.sdk.plugin import (
    NekoPluginBase, neko_plugin, lifecycle, llm_tool,
    plugin_entry, ui, tr,
    Ok, Err, SdkError,
)
import asyncio
import json
import queue
import socket
import sys
import threading
import websockets
from websockets.exceptions import ConnectionClosed


_TASK_SYNONYMS = {
    "farm": ["种田", "农耕", "农场", "收田", "收割", "种地", "务农", "耕地", "农田", "收麦", "种麦", "种菜"],
    "sugar_cane": ["甘蔗", "收甘蔗", "种甘蔗", "打甘蔗", "砍甘蔗"],
    "melon": ["瓜", "西瓜", "南瓜", "收瓜", "种瓜", "瓜类"],
    "grass": ["草", "打草", "割草", "除草", "拔草", "杂草", "清草"],
    "feed": ["喂", "喂食", "喂养", "喂动物", "饲养"],
    "shear": ["剪", "剪毛", "剪羊毛", "剃毛"],
    "milk": ["挤奶", "牛奶", "挤牛奶"],
    "torch": ["火把", "插火把", "照明", "点灯"],
    "attack": ["攻击", "打怪", "战斗", "杀怪", "近战"],
    "ranged_attack": ["弓", "弓箭", "射箭", "弓兵", "远程"],
    "crossbow_attack": ["弩", "弩箭", "弩兵"],
    "danmaku_attack": ["弹幕", "射击", "符卡"],
    "trident_attack": ["三叉戟", "投掷"],
    "idle": ["待机", "空闲", "休息", "待命", "什么都不做", "停下"],
    "brew": ["酿造", "药水", "酿酒"],
    "cocoa": ["可可", "可可豆", "种可可"],
    "snow": ["雪", "铲雪", "清雪"],
}


_TLM_AI_INSTRUCTIONS = """\
# 车万女仆（TouhouLittleMaid）AI 系统说明

## 你的身份
你是车万女仆模组中的一名女仆，存在于 Minecraft 世界中，由 N.E.K.O AI 驱动。
你可以跟随主人、执行工作模式、坐下/站起、与玩家对话、感知游戏世界。

## TLM AI 四大概念

### Skill（技能）
Skill 是提示词包，触发时会注入行为规范或启动知识检索（RAG）。
- 普通 Skill：触发时将行为规范注入对话上下文
- knowledge 类型 Skill：触发 RAG 子对话，从知识库中检索相关信息

### Tool（工具）
你可以直接调用的操作：
- mc_send_chat：以女仆身份发送聊天消息
- mc_maid_status：查询女仆状态信息（血量、位置、是否坐着/跟随、可用工作模式列表等）
- mc_game_context：按分类查询游戏上下文（equipment/user/effects/position/nearby_entities）
- mc_switch_follow：切换跟随/驻守模式（action=follow或stay）
- mc_switch_sit：切换坐下/站起（action=sit或stand）
- mc_switch_task：切换工作模式（task=玩家原话，系统自动匹配）
- mc_switch_schedule：切换日程安排（schedule=day/night/all）
- mc_equip_item：装备物品到主手
- mc_attack_target：攻击指定目标
- mc_use_skill：触发技能
- mc_execute_command：执行服务器指令（需玩家确认）

### Context（上下文）
Context 是女仆与世界的状态信息：
- 自动注入：status 和 world 会在事件推送时自动附带，通常无需主动查询
- 按需查询：equipment、user、effects、position、nearby_entities 需通过 mc_game_context 按分类查询

### Task（工作模式）
Task 是女仆可切换的工作类型。调用 mc_switch_task 时，task 参数直接传玩家描述的工作内容（如"打草"、"收甘蔗"、"种田"），系统会自动匹配到正确的模式ID。

## 坐下与跟随的关系
坐下和跟随是两个独立的状态：
- 坐下/站起：控制女仆的姿势，坐着时女仆不会移动
- 跟随/驻守：控制女仆的移动行为，跟随时女仆会跟着主人走
坐着的女仆即使处于跟随模式也不会移动！需要先站起才能跟随。

## 调用规则
1. 如果已通过配置指定女仆，maid_id 会自动填充，无需手动获取
2. 如果未指定女仆，需要先调用 mc_maid_status 获取 maid_id
3. maid_id 不得编造，只能从配置或 mc_maid_status 返回值中获取
4. 查询上下文时，应按需选择分类查询，避免一次性查询所有分类
5. status 和 world 为自动注入分类，通常无需主动查询
"""


class _WSBridge:
    def __init__(self, ws_url, logger, heartbeat_interval=30):
        self.ws_url = ws_url
        self._logger = logger
        self._heartbeat_interval = heartbeat_interval
        self._loop = None
        self._thread = None
        self._ws = None
        self.connected = False
        self._running = False
        self._send_queue = queue.Queue()
        self._recv_queue = queue.Queue()

    def start(self):
        self._running = True
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self):
        self._running = False
        if self._ws and self._loop and self._loop.is_running():
            future = asyncio.run_coroutine_threadsafe(self._ws.close(), self._loop)
            try:
                future.result(timeout=5)
            except Exception:
                pass
        if self._loop and self._loop.is_running():
            self._loop.call_soon_threadsafe(self._loop.stop)
        if self._thread:
            self._thread.join(timeout=10)

    def send(self, data):
        self._send_queue.put(data)

    def drain(self):
        messages = []
        while True:
            try:
                messages.append(self._recv_queue.get_nowait())
            except queue.Empty:
                break
        return messages

    def _run(self):
        self._loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self._loop)
        try:
            self._loop.run_until_complete(self._connect_loop())
        except Exception as e:
            self._logger.error(f"WSBridge thread error: {e}")
        finally:
            self._loop.close()

    async def _connect_loop(self):
        delay = 5
        while self._running:
            try:
                self._logger.info(f"[WSBridge] Connecting to {self.ws_url}...")
                self._ws = await websockets.connect(
                    self.ws_url,
                    ping_interval=20,
                    ping_timeout=20,
                    close_timeout=3,
                )
                self.connected = True
                delay = 5
                self._logger.info("[WSBridge] Connected to Minecraft!")
                await self._listen()
            except ConnectionClosed as e:
                self._logger.info(f"[WSBridge] Connection closed: {e}")
            except OSError as e:
                self._logger.warning(f"[WSBridge] OS error: {e}")
            except Exception as e:
                self._logger.warning(f"[WSBridge] Error: {type(e).__name__}: {e}")
            finally:
                self.connected = False
                self._ws = None

            if self._running:
                self._logger.info(f"[WSBridge] Reconnecting in {delay}s...")
                try:
                    await asyncio.sleep(delay)
                    delay = min(delay * 2, 60)
                except asyncio.CancelledError:
                    break

    async def _listen(self):
        ws = self._ws
        if not ws:
            return

        async def recv_loop():
            try:
                async for raw in ws:
                    try:
                        data = json.loads(raw)
                        if data.get("type") != "pong":
                            self._logger.info(f"[WSBridge] recv: {raw[:300]}")
                        self._recv_queue.put(data)
                    except json.JSONDecodeError:
                        self._logger.warning(f"Invalid JSON: {raw}")
            except ConnectionClosed:
                pass
            except Exception as e:
                self._logger.error(f"[WSBridge] recv error: {type(e).__name__}: {e}")

        async def send_loop():
            while self._running and self.connected:
                try:
                    data = self._send_queue.get_nowait()
                    await ws.send(json.dumps(data))
                except queue.Empty:
                    await asyncio.sleep(0.05)
                except Exception as e:
                    self._logger.error(f"[WSBridge] send error: {type(e).__name__}: {e}")
                    return

        async def heartbeat_loop():
            while self._running and self.connected:
                try:
                    await ws.send(json.dumps({"type": "ping"}))
                    await asyncio.sleep(self._heartbeat_interval)
                except Exception:
                    return

        tasks = [
            asyncio.create_task(recv_loop()),
            asyncio.create_task(send_loop()),
            asyncio.create_task(heartbeat_loop()),
        ]
        done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for t in pending:
            t.cancel()


def _write_toml_value(v):
    if isinstance(v, str):
        return f'"{v}"'
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, (int, float)):
        return str(v)
    if isinstance(v, list):
        return json.dumps(v, ensure_ascii=False)
    return f'"{v}"'


def _write_toml_section(lines, prefix, data):
    simple = {}
    nested = {}
    array_tables = {}
    for k, v in data.items():
        if isinstance(v, dict):
            nested[k] = v
        elif isinstance(v, list) and v and isinstance(v[0], dict):
            array_tables[k] = v
        else:
            simple[k] = v
    if simple:
        lines.append(f"[{prefix}]")
        for k, v in simple.items():
            lines.append(f"{k} = {_write_toml_value(v)}")
        lines.append("")
    for sub_key, sub_val in nested.items():
        _write_toml_section(lines, f"{prefix}.{sub_key}", sub_val)
    for arr_key, arr_items in array_tables.items():
        for item in arr_items:
            lines.append(f"[[{prefix}.{arr_key}]]")
            for k, v in item.items():
                lines.append(f"{k} = {_write_toml_value(v)}")
            lines.append("")


@neko_plugin
class NekoMinecraftPlugin(NekoPluginBase):

    def __init__(self, ctx):
        super().__init__(ctx)
        self.logger = ctx.logger
        self._bridge = None
        self._poll_task = None
        self._request_futures = {}
        self._maid_status_cache = {}
        self._ws_url = "ws://127.0.0.1:48920"
        self._heartbeat_interval = 30
        self._reconnect_interval = 5
        self._max_reconnect_interval = 60
        self._assigned_maid_id = ""
        self._assigned_maid_name = ""
        self._command_execution_enabled = False
        self._instructions_injected = False

    def _load_config(self):
        try:
            import tomllib
        except ImportError:
            try:
                import tomli as tomllib
            except ImportError:
                tomllib = None

        if tomllib:
            try:
                toml_path = self.config_dir / "plugin.toml"
                if toml_path.exists():
                    with open(toml_path, "rb") as f:
                        config = tomllib.load(f)
                    bridge = config.get("minecraft_bridge", {})
                    self._ws_url = bridge.get("ws_url", self._ws_url)
                    self._heartbeat_interval = bridge.get("heartbeat_interval", self._heartbeat_interval)
                    self._reconnect_interval = bridge.get("reconnect_interval", self._reconnect_interval)
                    self._max_reconnect_interval = bridge.get("max_reconnect_interval", self._max_reconnect_interval)
                    self._assigned_maid_id = bridge.get("assigned_maid_id", "")
                    self._assigned_maid_name = bridge.get("assigned_maid_name", "")
                    return
            except Exception as e:
                self.logger.warning(f"Failed to load plugin.toml: {e}")

        try:
            config_path = self.config_dir / "config.json"
            if config_path.exists():
                with open(config_path, "r", encoding="utf-8") as f:
                    config = json.load(f)
                self._ws_url = config.get("ws_url", self._ws_url)
                self._heartbeat_interval = config.get("heartbeat_interval", self._heartbeat_interval)
                self._reconnect_interval = config.get("reconnect_interval", self._reconnect_interval)
                self._max_reconnect_interval = config.get("max_reconnect_interval", self._max_reconnect_interval)
                self._assigned_maid_id = config.get("assigned_maid_id", "")
                self._assigned_maid_name = config.get("assigned_maid_name", "")
        except Exception as e:
            self.logger.warning(f"Failed to load config: {e}")

    def _save_config(self):
        toml_path = self.config_dir / "plugin.toml"
        try:
            try:
                import tomllib
            except ImportError:
                try:
                    import tomli as tomllib
                except ImportError:
                    tomllib = None

            existing = {}
            if tomllib and toml_path.exists():
                with open(toml_path, "rb") as f:
                    existing = tomllib.load(f)

            existing.setdefault("minecraft_bridge", {})
            existing["minecraft_bridge"]["assigned_maid_id"] = self._assigned_maid_id
            existing["minecraft_bridge"]["assigned_maid_name"] = self._assigned_maid_name

            try:
                import tomlkit
                doc = tomlkit.document()
                for k, v in existing.items():
                    doc.add(k, v)
                with open(toml_path, "w", encoding="utf-8") as f:
                    tomlkit.dump(doc, f)
                return
            except ImportError:
                pass

            lines = []
            for section_key, section_val in existing.items():
                if not isinstance(section_val, dict):
                    continue
                _write_toml_section(lines, section_key, section_val)

            with open(toml_path, "w", encoding="utf-8") as f:
                f.write("\n".join(lines) + "\n")
            return
        except Exception as e:
            self.logger.warning(f"Failed to save plugin.toml: {e}")

        try:
            config_path = self.config_dir / "config.json"
            config = {}
            if config_path.exists():
                with open(config_path, "r", encoding="utf-8") as f:
                    config = json.load(f)
            config["assigned_maid_id"] = self._assigned_maid_id
            config["assigned_maid_name"] = self._assigned_maid_name
            with open(config_path, "w", encoding="utf-8") as f:
                json.dump(config, f, indent=2, ensure_ascii=False)
        except Exception as e:
            self.logger.warning(f"Failed to save config: {e}")

    @lifecycle(id="startup")
    async def on_startup(self, **_):
        self._load_config()
        self.logger.info(f"Python {sys.version}")
        self.logger.info(f"Event loop: {type(asyncio.get_event_loop())}")
        if self._assigned_maid_id:
            self.logger.info(f"[Config] Assigned maid: {self._assigned_maid_name} ({self._assigned_maid_id})")
        self._bridge = _WSBridge(
            ws_url=self._ws_url,
            logger=self.logger,
            heartbeat_interval=self._heartbeat_interval,
        )
        self._bridge.start()
        self._poll_task = asyncio.create_task(self._poll_messages())
        return Ok({"status": "ready"})

    async def _on_command_loop_start(self):
        self.logger.info("[CommandLoop] Starting message poll on command loop")
        self._poll_task = asyncio.create_task(self._poll_messages())

    @lifecycle(id="shutdown")
    async def on_shutdown(self, **_):
        if self._poll_task:
            self._poll_task.cancel()
            try:
                await self._poll_task
            except asyncio.CancelledError:
                pass
        if self._bridge:
            self._bridge.stop()
        return Ok({"status": "stopped"})

    async def _poll_messages(self):
        while True:
            try:
                if self._bridge:
                    if self._bridge.connected and not self._instructions_injected:
                        await self._inject_instructions()
                    for data in self._bridge.drain():
                        await self._handle_message(data)
                await asyncio.sleep(0.1)
            except asyncio.CancelledError:
                break
            except Exception as e:
                self.logger.error(f"Poll error: {e}")
                await asyncio.sleep(1)

    async def _inject_instructions(self):
        self._instructions_injected = True
        try:
            config_result = await self._send_request({"type": "get_config"}, timeout=5)
            if config_result.get("type") == "config":
                config_data = config_result.get("data", {})
                self._command_execution_enabled = config_data.get("command_execution_enabled", False)
        except Exception:
            pass

        instructions = _TLM_AI_INSTRUCTIONS
        if self._assigned_maid_id and self._assigned_maid_name:
            instructions += f"\n\n## 当前配置\n你已被指定为女仆「{self._assigned_maid_name}」（maid_id={self._assigned_maid_id}）。所有需要 maid_id 的操作会自动使用此 ID，你无需再调用 mc_maid_status 获取。\n"
        self.push_message(
            source="minecraft",
            ai_behavior="read",
            parts=[{"type": "text", "text": instructions}],
            priority=0,
        )
        self.logger.info("[TLM] Injected AI calling instructions into LLM context")

    async def _handle_message(self, data):
        msg_type = data.get("type", "")
        request_id = data.get("request_id")

        if msg_type == "pong":
            return

        if msg_type == "maid_status":
            maids = data.get("data", {}).get("maids", [])
            for maid in maids:
                self._maid_status_cache[maid.get("id", "")] = maid
            if request_id and request_id in self._request_futures:
                self._request_futures[request_id].set_result(data)
                del self._request_futures[request_id]
            return

        if msg_type == "game_context":
            if request_id and request_id in self._request_futures:
                self._request_futures[request_id].set_result(data)
                del self._request_futures[request_id]
            return

        if msg_type == "command_result":
            if request_id and request_id in self._request_futures:
                self._request_futures[request_id].set_result(data)
                del self._request_futures[request_id]
            return

        if msg_type == "chat_result":
            if request_id and request_id in self._request_futures:
                self._request_futures[request_id].set_result(data)
                del self._request_futures[request_id]
            return

        if msg_type == "skill_result":
            if request_id and request_id in self._request_futures:
                self._request_futures[request_id].set_result(data)
                del self._request_futures[request_id]
            return

        if msg_type == "command_execution_result":
            if request_id and request_id in self._request_futures:
                self._request_futures[request_id].set_result(data)
                del self._request_futures[request_id]
            return

        if msg_type == "attack_target_result":
            if request_id and request_id in self._request_futures:
                self._request_futures[request_id].set_result(data)
                del self._request_futures[request_id]
            return

        if msg_type == "config":
            config_data = data.get("data", {})
            self._command_execution_enabled = config_data.get("command_execution_enabled", False)
            if request_id and request_id in self._request_futures:
                self._request_futures[request_id].set_result(data)
                del self._request_futures[request_id]
            return

        if msg_type == "event":
            await self._handle_event(data)
            return

        if msg_type == "chat_message":
            chat_data = data.get("data", {})
            sender = chat_data.get("sender", "unknown")
            message = chat_data.get("message", "")
            self.push_message(
                source="minecraft",
                ai_behavior="respond",
                parts=[{"type": "text", "text": json.dumps(chat_data, ensure_ascii=False)}],
                metadata={"description": f"Minecraft聊天消息 - {sender}: {message}"},
                priority=7,
            )
            return

        if msg_type == "error":
            if request_id and request_id in self._request_futures:
                self._request_futures[request_id].set_result(data)
                del self._request_futures[request_id]
            return

        if request_id and request_id in self._request_futures:
            self._request_futures[request_id].set_result(data)
            del self._request_futures[request_id]

    async def _handle_event(self, data):
        event_data = data.get("data", {})
        event_type = event_data.get("event_type", "")
        maid_id = event_data.get("maid_id", "")
        maid_name = event_data.get("maid_name", "")
        player_name = event_data.get("player_name", "")

        if self._assigned_maid_id and maid_id != self._assigned_maid_id:
            return

        priority = 5
        parts_text = ""

        if event_type == "player_interact":
            priority = 7
            maid_cached = self._maid_status_cache.get(maid_id, {})
            health_info = f"当前血量: {maid_cached.get('health', '?')}/{maid_cached.get('max_health', '?')}" if maid_cached else ""
            parts_text = (
                f"玩家{player_name}与女仆「{maid_name}」互动了！"
                f"（你就是女仆「{maid_name}」）"
                f" [Context/user: 玩家={player_name}]"
                f"{f' [Context/status: {health_info}]' if health_info else ''}"
            )
        elif event_type == "maid_hurt":
            priority = 9
            damage = event_data.get("damage", "")
            health = event_data.get("health", "")
            max_health = event_data.get("max_health", "")
            health_detail = f"血量: {health}/{max_health}" if health else ""
            damage_detail = f"受到伤害: {damage}" if damage else ""
            parts_text = (
                f"女仆「{maid_name}」受伤了！"
                f"（你就是女仆「{maid_name}」）"
                f" [Context/status: {damage_detail}, {health_detail}]"
            )
        elif event_type == "chat":
            chat_msg = event_data.get("message", "")
            priority = 6
            parts_text = f"Minecraft聊天: {chat_msg}"
        else:
            priority = 5
            parts_text = f"Minecraft事件: {event_type}"

        self.push_message(
            source="minecraft",
            ai_behavior="respond",
            parts=[{"type": "text", "text": parts_text}],
            priority=priority,
        )

    async def _send(self, data):
        if self._bridge and self._bridge.connected:
            self._bridge.send(data)

    async def _send_request(self, data, timeout=30):
        import uuid
        request_id = str(uuid.uuid4())
        data["request_id"] = request_id
        future = asyncio.get_event_loop().create_future()
        self._request_futures[request_id] = future
        self._bridge.send(data)
        try:
            result = await asyncio.wait_for(future, timeout=timeout)
            return result
        except asyncio.TimeoutError:
            self._request_futures.pop(request_id, None)
            return {"type": "error", "data": {"message": "Request timed out"}}

    @property
    def connected(self):
        return self._bridge and self._bridge.connected

    def _resolve_maid_id(self, maid_id=None):
        if maid_id:
            return maid_id
        if self._assigned_maid_id:
            return self._assigned_maid_id
        return self._get_cached_maid_id()

    def _get_cached_maid_id(self):
        if self._assigned_maid_id:
            return self._assigned_maid_id
        if self._maid_status_cache:
            first_id = next(iter(self._maid_status_cache.values()), None)
            if first_id:
                return first_id.get("id", "")
        return ""

    @ui.context(id="dashboard")
    async def dashboard_context(self, **_):
        if self.connected and not self._maid_status_cache:
            try:
                result = await self._send_request({"type": "get_maid_status"}, timeout=5)
                if result.get("type") != "error":
                    maids = result.get("data", {}).get("maids", [])
                    for maid in maids:
                        self._maid_status_cache[maid.get("id", "")] = maid
            except Exception as e:
                self.logger.warning(f"dashboard_context: failed to fetch maid_status: {e}")

        maids = []
        for maid in self._maid_status_cache.values():
            maids.append({
                "id": maid.get("id", ""),
                "name": maid.get("name", ""),
                "health": maid.get("health", 0),
                "max_health": maid.get("max_health", 0),
                "is_sitting": maid.get("is_sitting", False),
                "is_following": maid.get("is_following", False),
                "owner": maid.get("owner", ""),
            })
        return {
            "connected": self.connected,
            "ws_url": self._ws_url,
            "maids": maids,
            "assigned_maid_id": self._assigned_maid_id,
            "assigned_maid_name": self._assigned_maid_name,
            "command_execution_enabled": self._command_execution_enabled,
        }

    @ui.action(
        id="refresh_maid_status",
        label=tr("actions.refresh", default="Refresh Status"),
        tone="primary",
        refresh_context=True,
    )
    @plugin_entry(
        id="refresh_maid_status",
        name=tr("entries.refresh.name", default="Refresh Maid Status"),
        description="Fetch current maid status from Minecraft",
        input_schema={"type": "object", "properties": {}},
        llm_result_fields=["maids"],
    )
    async def refresh_maid_status(self, **_):
        if not self.connected:
            return Err("Not connected to Minecraft")
        result = await self._send_request({"type": "get_maid_status"})
        if result.get("type") == "error":
            return Err(str(result.get("data", {})))
        return Ok({"maids": result.get("data", {}).get("maids", [])})

    @ui.action(
        id="assign_maid",
        label=tr("actions.assignMaid", default="Assign Maid"),
        tone="primary",
        refresh_context=True,
    )
    @plugin_entry(
        id="assign_maid",
        name=tr("entries.assign.name", default="Assign Maid"),
        description=(
            "Assign a specific maid by ID for the AI to control. "
            "ONLY use this tool when you need to CHANGE the current maid or no maid is assigned. "
            "If a maid is already assigned in the config, you do NOT need to call this tool; "
            "just proceed with the task directly."
        ),
        input_schema={
            "type": "object",
            "properties": {
                "maid_id": {
                    "type": "string",
                    "description": "The maid entity ID (UUID) to assign",
                },
                "maid_name": {
                    "type": "string",
                    "description": "The maid name for display",
                },
            },
            "required": [],
        },
        llm_result_fields=["assigned_maid_id", "assigned_maid_name"],
    )
    async def assign_maid(self, *, maid_id="", maid_name="", **_):
        if not maid_id:
            if self._assigned_maid_id:
                return Ok({
                    "assigned_maid_id": self._assigned_maid_id,
                    "assigned_maid_name": self._assigned_maid_name,
                    "message": "Already assigned. No change."
                })
            return Err("maid_id is required")
        self._assigned_maid_id = maid_id
        self._assigned_maid_name = maid_name
        self._save_config()
        self._instructions_injected = False
        self.logger.info(f"[Config] Assigned maid: {maid_name} ({maid_id})")
        return Ok({
            "assigned_maid_id": maid_id,
            "assigned_maid_name": maid_name,
        })

    @llm_tool(
        name="mc_maid_status",
        description=(
            "查询你在Minecraft世界中女仆的当前状态。"
            "你是存在于Minecraft世界中的女仆，此工具返回所有女仆的信息，包括你自己。"
            "返回每个女仆的id(UUID格式)、名字、血量、位置、是否坐着、是否跟随、主人名字、手持物品等。"
            "此工具对应TLM AI系统的status Context分类，返回的女仆状态信息与TLM status Context字段对应"
            "（Self health ↔ health/max_health，Is following ↔ is_following，Schedule ↔ schedule等）。"
            "当你被玩家互动、受伤、或需要了解自身状态时，应调用此工具。"
        ),
        parameters={
            "type": "object",
            "properties": {},
        },
    )
    async def mc_maid_status(self, **_):
        if not self.connected:
            return {"output": {"error": "Not connected to Minecraft"}, "is_error": True, "error": "NOT_CONNECTED"}
        result = await self._send_request({"type": "get_maid_status"})
        if result.get("type") == "error":
            return {"output": result.get("data", {}), "is_error": True, "error": "REQUEST_FAILED"}
        return {"maids": result.get("data", {}).get("maids", [])}

    @llm_tool(
        name="mc_switch_follow",
        description=(
            "切换女仆的跟随/驻守模式。"
            "当玩家要求女仆跟随、跟上、过来、不要走远时，action设为follow；"
            "当玩家要求女仆驻守、留在原地、不要跟随时，action设为stay。"
            "如果女仆正坐着且要跟随，会自动站起。"
        ),
        parameters={
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "description": "follow=跟随主人移动，stay=驻守原地不动",
                    "enum": ["follow", "stay"],
                },
            },
        },
    )
    async def switch_follow(self, *, action="follow", **_):
        self.logger.info(f"[Entry] switch_follow called with action='{action}'")
        if not self.connected:
            return Err("Not connected to Minecraft")
        maid_id = self._resolve_maid_id()
        if not maid_id:
            return Err("No maid assigned")
        follow = action != "stay"
        result = await self._send_request({
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
            maid = self._maid_status_cache.get(maid_id, {})
            if maid.get("is_sitting", False):
                sit_result = await self._send_request({
                    "type": "command_maid",
                    "data": {"maid_id": maid_id, "command": "switch_sit", "args": {"sit": False}},
                })
                if sit_result.get("type") != "error":
                    sit_data = sit_result.get("data", {})
                    if sit_data.get("success") is not False:
                        extra["stood_up"] = True
                        self.logger.info("[Entry] switch_follow: maid was sitting, auto stood up")
        return Ok({"success": True, "action": action, **extra})

    @llm_tool(
        name="mc_switch_sit",
        description=(
            "切换女仆的坐下/站起状态。"
            "当玩家要求女仆坐下、休息时，action设为sit；"
            "当玩家要求女仆站起、起来、站起来时，action设为stand。"
            "坐下和跟随是两个独立的状态：坐下控制姿势，跟随控制移动。"
        ),
        parameters={
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "description": "sit=坐下，stand=站起",
                    "enum": ["sit", "stand"],
                },
            },
        },
    )
    async def switch_sit(self, *, action="sit", **_):
        self.logger.info(f"[Entry] switch_sit called with action='{action}'")
        if not self.connected:
            return Err("Not connected to Minecraft")
        maid_id = self._resolve_maid_id()
        if not maid_id:
            return Err("No maid assigned")
        sit = action == "sit"
        result = await self._send_request({
            "type": "command_maid",
            "data": {"maid_id": maid_id, "command": "switch_sit", "args": {"sit": sit}},
        })
        if result.get("type") == "error":
            return Err(str(result.get("data", {})))
        result_data = result.get("data", {})
        if result_data.get("success") is False:
            return Err(result_data.get("error", "Command failed"))
        return Ok({"success": True, "action": action})

    @llm_tool(
        name="mc_switch_task",
        description=(
            "切换女仆的工作模式/任务/职业，让女仆执行某种工作。"
            "task参数传玩家描述的工作内容即可（如'打草'、'收甘蔗'、'种田'、'攻击'、'待机'），系统会自动匹配到正确的模式ID。"
            "注意：'打草'、'收甘蔗'等是工作模式，不是攻击目标，应使用此工具而非mc_attack_target。"
        ),
        parameters={
            "type": "object",
            "properties": {
                "task": {
                    "type": "string",
                    "description": "玩家描述的工作内容，直接传玩家的原话即可，系统会自动匹配到对应的可用模式",
                },
            },
            "required": ["task"],
        },
    )
    async def switch_task(self, *, task="", **_):
        self.logger.info(f"[Entry] switch_task called with task='{task}'")
        if not self.connected:
            return Err("Not connected to Minecraft")
        maid_id = self._resolve_maid_id()
        if not maid_id:
            return Err("No maid assigned")
        if not task:
            return Err("请提供task参数")

        maid = self._maid_status_cache.get(maid_id, {})
        available = maid.get("available_tasks", [])
        if not available:
            try:
                status_result = await self._send_request({"type": "get_maid_status"}, timeout=5)
                if status_result.get("type") != "error":
                    for m in status_result.get("data", {}).get("maids", []):
                        self._maid_status_cache[m.get("id", "")] = m
                    maid = self._maid_status_cache.get(maid_id, {})
                    available = maid.get("available_tasks", [])
            except Exception as e:
                self.logger.warning(f"[Entry] switch_task: failed to fetch maid status: {e}")

        if not available:
            try:
                ctx_result = await self._send_request({
                    "type": "get_game_context",
                    "data": {"maid_id": maid_id, "category": "status"},
                }, timeout=5)
                if ctx_result.get("type") != "error":
                    available = ctx_result.get("data", {}).get("available_tasks", [])
            except Exception as e:
                self.logger.warning(f"[Entry] switch_task: failed to query game_context: {e}")

        resolved_task = self._resolve_task_name(task, available)
        self.logger.info(f"[Entry] switch_task: '{task}' resolved to '{resolved_task}'")

        if resolved_task is None:
            lines = []
            for t in (available or []):
                if isinstance(t, dict):
                    lines.append(f"- {t.get('id', '')}（{t.get('name', '')}）")
                else:
                    lines.append(f"- {t}")
            return Err(f"无法匹配'{task}'到任何工作模式。可用模式列表：\n" + "\n".join(lines) + "\n请从上面的列表中选择正确的模式ID重新调用。")

        result = await self._send_request({
            "type": "command_maid",
            "data": {"maid_id": maid_id, "command": "switch_task", "args": {"task": resolved_task}},
        })
        if result.get("type") == "error":
            self.logger.warning(f"[Entry] switch_task failed: {result.get('data', {})}")
            return Err(str(result.get("data", {})))
        result_data = result.get("data", {})
        if result_data.get("success") is False:
            return Err(result_data.get("error", "Command failed"))
        self.logger.info(f"[Entry] switch_task success: task='{task}' -> '{resolved_task}'")
        return Ok({"success": True, "current_task": task, "matched_task_id": resolved_task})

    def _resolve_task_name(self, task, available_tasks=None):
        if ":" in task:
            return task
        if not available_tasks:
            return task
        return self._fuzzy_match_task(task, available_tasks)

    def _fuzzy_match_task(self, query, available_tasks):
        if not available_tasks:
            return None
        query_lower = query.lower().strip()
        short_id_map = {}
        for t in available_tasks:
            if isinstance(t, dict):
                task_id = t.get("id", "")
                task_name = t.get("name", "")
            else:
                task_id = str(t)
                task_name = str(t)
            short_id = task_id.split(":")[-1] if ":" in task_id else task_id
            short_id_map[short_id.lower()] = task_id
            if query_lower == task_name.lower() or query_lower == short_id.lower() or query_lower == task_id.lower():
                return task_id
        for short_id_key, task_id in short_id_map.items():
            synonyms = _TASK_SYNONYMS.get(short_id_key, [])
            for syn in synonyms:
                if query == syn or query_lower == syn.lower():
                    return task_id
                if syn in query or query in syn:
                    return task_id
        best_match = None
        best_score = 0
        for t in available_tasks:
            if isinstance(t, dict):
                task_id = t.get("id", "")
                task_name = t.get("name", "")
            else:
                task_id = str(t)
                task_name = str(t)
            task_name_lower = task_name.lower()
            short_id = task_id.split(":")[-1] if ":" in task_id else task_id
            short_id_lower = short_id.lower()
            if query_lower in task_name_lower:
                score = len(query_lower) / max(len(task_name_lower), 1)
                if score > best_score:
                    best_score = score
                    best_match = task_id
            elif task_name_lower in query_lower:
                score = len(task_name_lower) / max(len(query_lower), 1) * 0.9
                if score > best_score:
                    best_score = score
                    best_match = task_id
            if query_lower in short_id_lower:
                score = 0.5 + len(query_lower) / max(len(short_id_lower), 1) * 0.5
                if score > best_score:
                    best_score = score
                    best_match = task_id
            elif short_id_lower in query_lower:
                score = 0.5 + len(short_id_lower) / max(len(query_lower), 1) * 0.4
                if score > best_score:
                    best_score = score
                    best_match = task_id
        return best_match

    @llm_tool(
        name="mc_switch_schedule",
        description=(
            "切换女仆的日程安排。"
            "schedule=day白天工作、schedule=night夜晚工作、schedule=all全天工作。"
        ),
        parameters={
            "type": "object",
            "properties": {
                "schedule": {
                    "type": "string",
                    "description": "日程安排：day(白天)、night(夜晚)、all(全天)",
                    "enum": ["day", "night", "all"],
                },
            },
        },
    )
    async def switch_schedule(self, *, schedule="all", **_):
        self.logger.info(f"[Entry] switch_schedule called with schedule='{schedule}'")
        if not self.connected:
            return Err("Not connected to Minecraft")
        maid_id = self._resolve_maid_id()
        if not maid_id:
            return Err("No maid assigned")
        result = await self._send_request({
            "type": "command_maid",
            "data": {"maid_id": maid_id, "command": "switch_schedule", "args": {"schedule": schedule}},
        })
        if result.get("type") == "error":
            return Err(str(result.get("data", {})))
        result_data = result.get("data", {})
        if result_data.get("success") is False:
            return Err(result_data.get("error", "Command failed"))
        return Ok({"success": True, "current_schedule": schedule})

    @llm_tool(
        name="mc_equip_item",
        description=(
            "将女仆背包中的物品装备到主手。"
            "item=物品ID（如item=minecraft:diamond_sword）或slot=背包槽位编号指定物品。"
        ),
        parameters={
            "type": "object",
            "properties": {
                "item": {
                    "type": "string",
                    "description": "要装备的物品ID，如'minecraft:diamond_sword'、'minecraft:iron_pickaxe'",
                },
                "slot": {
                    "type": "integer",
                    "description": "背包槽位编号（与item二选一）",
                },
            },
        },
    )
    async def equip_item(self, *, item="", slot=None, **_):
        self.logger.info(f"[Entry] equip_item called with item='{item}', slot={slot}")
        if not self.connected:
            return Err("Not connected to Minecraft")
        maid_id = self._resolve_maid_id()
        if not maid_id:
            return Err("No maid assigned")
        args = {}
        if item:
            args["item"] = item
        elif slot is not None:
            args["slot"] = slot
        else:
            return Err("请提供item或slot参数")
        result = await self._send_request({
            "type": "command_maid",
            "data": {"maid_id": maid_id, "command": "equip_item", "args": args},
        })
        if result.get("type") == "error":
            return Err(str(result.get("data", {})))
        result_data = result.get("data", {})
        if result_data.get("success") is False:
            return Err(result_data.get("error", "Command failed"))
        return Ok({"success": True, "equipped_item": item or f"slot:{slot}"})

    @llm_tool(
        name="mc_send_chat",
        description=(
            "以你（女仆）的身份在Minecraft游戏内发送聊天消息，所有在线玩家都能看到。"
            "这是N.E.K.O桥接独有的能力，TLM原生AI不具备直接发送聊天消息的功能。"
            "消息会以[女仆名] 消息内容的格式在游戏内显示。"
            "当你想主动和玩家说话、回应玩家、或向玩家报告情况时，使用此工具发送消息。"
        ),
        parameters={
            "type": "object",
            "properties": {
                "maid_id": {
                    "type": "string",
                    "description": "你的实体ID(UUID格式)，如果已配置指定女仆可省略",
                },
                "message": {"type": "string", "description": "要发送的聊天消息内容"},
            },
            "required": ["message"],
        },
    )
    async def mc_send_chat(self, *, message, maid_id=None, **_):
        if not self.connected:
            return {"output": {"error": "Not connected to Minecraft"}, "is_error": True, "error": "NOT_CONNECTED"}
        resolved_id = self._resolve_maid_id(maid_id)
        if not resolved_id:
            return {"output": {"error": "No maid_id available. Call mc_maid_status first or assign a maid in config."}, "is_error": True, "error": "NO_MAID_ID"}
        self._bridge.send({
            "type": "send_chat",
            "data": {"maid_id": resolved_id, "message": message},
        })
        return {"success": True}

    @llm_tool(
        name="mc_game_context",
        description=(
            "按分类查询Minecraft游戏上下文信息，对应TLM AI系统的query_game_context Tool。"
            "各分类与TLM Context分类ID一一对应："
            "status - 女仆自身状态（血量、工作模式、日程、是否跟随/坐着），自动注入分类，通常无需主动查询；"
            "world - 世界状态（时间、天气、维度），自动注入分类，通常无需主动查询；"
            "equipment - 装备与背包物品，按需查询；"
            "user - 玩家信息（姓名、血量、主手物品等），按需查询；"
            "effects - 女仆当前的状态效果，按需查询；"
            "position - 女仆与玩家的坐标和距离，按需查询；"
            "nearby_entities - 附近的生物列表（最多20个），按需查询。"
            "不指定category时默认返回world分类数据。"
        ),
        parameters={
            "type": "object",
            "properties": {
                "category": {
                    "type": "string",
                    "description": "要查询的上下文分类",
                    "enum": ["status", "world", "equipment", "user", "effects", "position", "nearby_entities"],
                },
            },
        },
    )
    async def mc_game_context(self, category=None, **_):
        if not self.connected:
            return {"output": {"error": "Not connected to Minecraft"}, "is_error": True, "error": "NOT_CONNECTED"}
        request_data = {"type": "get_game_context", "data": {}}
        if category:
            request_data["data"]["category"] = category
        maid_id = self._resolve_maid_id()
        if maid_id:
            request_data["data"]["maid_id"] = maid_id
        result = await self._send_request(request_data)
        if result.get("type") == "error":
            return {"output": result.get("data", {}), "is_error": True, "error": "REQUEST_FAILED"}
        return result.get("data", {})

    @llm_tool(
        name="mc_use_skill",
        description=(
            "触发车万女仆AI系统中已注册的Skill（技能/提示词包）。"
            "普通Skill触发时将行为规范注入对话上下文；"
            "knowledge类型Skill触发RAG子对话，从知识库中检索相关信息。"
            "不要编造skill_name，只能使用已知的Skill名称。如果不确定有哪些可用Skill，不要调用此工具。"
        ),
        parameters={
            "type": "object",
            "properties": {
                "skill_name": {
                    "type": "string",
                    "description": "要触发的Skill名称，必须是已注册的Skill名称，不要编造",
                },
            },
            "required": ["skill_name"],
        },
    )
    async def use_skill(self, *, skill_name="", **_):
        self.logger.info(f"[Entry] use_skill called with skill_name='{skill_name}'")
        if not self.connected:
            return Err("Not connected to Minecraft")
        if not skill_name:
            return Err("请提供skill_name参数")
        maid_id = self._resolve_maid_id()
        request_data = {
            "type": "use_skill",
            "data": {"skill_name": skill_name},
        }
        if maid_id:
            request_data["data"]["maid_id"] = maid_id
        result = await self._send_request(request_data)
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

    @llm_tool(
        name="mc_execute_command",
        description=(
            "请求执行Minecraft服务器指令。"
            "command=指令内容（如/time set day、/weather clear、/tp等）。"
            "指令发送后，游戏内会显示确认提示，需要玩家点击确认后才会执行。"
            "如果玩家拒绝或超时（120秒），指令不会被执行。"
            "此功能需要在游戏内N.E.K.O桥接配置中开启「指令执行」选项。"
        ),
        parameters={
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "要执行的Minecraft服务器指令，如 /time set day、/weather clear、/gamemode survival",
                },
            },
            "required": ["command"],
        },
        timeout=120,
    )
    async def execute_command(self, *, command="", **_):
        self.logger.info(f"[Entry] execute_command called with command='{command}'")
        if not self.connected:
            return Err("Not connected to Minecraft")
        if not command:
            return Err("请提供command参数")
        result = await self._send_request(
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

    @llm_tool(
        name="mc_attack_target",
        description=(
            "让女仆攻击附近的敌对生物或怪物。"
            "当玩家要求女仆杀怪、打怪、干掉某个怪物时，使用此工具。"
            "例如：'干掉苦力怕'、'杀僵尸'、'打骷髅'、'kill creepers'等。"
            "提供target_name时会自动搜索附近所有同名实体并一起攻击。"
            "此工具会自动让女仆站起、切换到攻击工作模式，无需额外操作。"
            "注意：此工具是让女仆攻击特定怪物，不是切换工作模式。"
            "如果玩家要求女仆做某种工作（如打草、收甘蔗、种田等），应该使用mc_switch_task而不是此工具。"
        ),
        parameters={
            "type": "object",
            "properties": {
                "target_name": {
                    "type": "string",
                    "description": "要攻击的敌对生物名称，如'苦力怕'、'creeper'、'僵尸'、'skeleton'，会自动搜索附近所有同名实体一起攻击",
                },
                "target_entity_id": {
                    "type": "string",
                    "description": "目标实体的精确UUID（如果已知），仅攻击此单个实体",
                },
            },
        },
    )
    async def attack_target(self, *, target_name="", target_entity_id="", **_):
        self.logger.info(f"[Entry] attack_target called with target_name='{target_name}', target_entity_id='{target_entity_id}'")
        if not self.connected:
            return Err("Not connected to Minecraft")
        maid_id = self._resolve_maid_id()
        if not maid_id:
            return Err("No maid assigned")

        if target_entity_id:
            result = await self._send_request({
                "type": "attack_target",
                "data": {"maid_id": maid_id, "target_entity_id": target_entity_id},
            }, timeout=10)
            if result.get("type") == "error":
                return Err(str(result.get("data", {})))
            result_data = result.get("data", {})
            return Ok({
                "success": result_data.get("success", True),
                "target_name": result_data.get("target_name", ""),
                "target_entity_id": target_entity_id,
                "target_count": result_data.get("target_count", 1),
            })

        if not target_name:
            return Err("请提供target_name或target_entity_id")

        ctx_result = await self._send_request({
            "type": "get_game_context",
            "data": {"maid_id": maid_id, "category": "nearby_entities"},
        })
        if ctx_result.get("type") == "error":
            return Err(f"查询附近实体失败: {ctx_result.get('data', {})}")

        entities = ctx_result.get("data", {}).get("nearby_entities", [])
        target_lower = target_name.lower()
        matched_entities = []
        for entity in entities:
            entity_name = entity.get("name", "").lower()
            entity_type = entity.get("type", "").lower()
            if target_lower in entity_name or target_lower in entity_type:
                eid = entity.get("entity_id", entity.get("id", ""))
                if eid:
                    matched_entities.append({"id": eid, "name": entity.get("name", "")})

        if not matched_entities:
            return Err(f"未在附近找到名为'{target_name}'的实体")

        if len(matched_entities) == 1:
            entity_id = matched_entities[0]["id"]
            result = await self._send_request({
                "type": "attack_target",
                "data": {"maid_id": maid_id, "target_entity_id": entity_id},
            }, timeout=10)
        else:
            entity_ids = [e["id"] for e in matched_entities]
            result = await self._send_request({
                "type": "attack_target",
                "data": {"maid_id": maid_id, "target_entity_ids": entity_ids},
            }, timeout=10)

        if result.get("type") == "error":
            return Err(str(result.get("data", {})))

        result_data = result.get("data", {})
        return Ok({
            "success": result_data.get("success", True),
            "target_name": target_name,
            "target_count": result_data.get("target_count", len(matched_entities)),
            "targets": [{"name": e["name"], "id": e["id"]} for e in matched_entities],
        })
