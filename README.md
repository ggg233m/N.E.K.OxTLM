# N.E.K.OxTLM

一个联动插件，为"车万女仆"（Touhou Little Maid）模组与 N.E.K.O 搭建兼容与交互桥梁，可借助 N.E.K.O 通过自然语言控制游戏内女仆行为。

## 功能特性

- **AI 驱动女仆对话** — 通过 N.E.K.O 的自然语言能力驱动女仆的 AI 对话，替代或扩展车万女仆内置的 LLM 交互
- **WebSocket 桥接** — 在 Minecraft 服务端启动 WebSocket 服务器，N.E.K.O 客户端通过 WebSocket 协议实时交互
- **女仆状态查询** — 获取所有女仆的生命值、位置、任务、装备等详细信息
- **女仆行为控制** — 通过自然语言指令控制女仆的跟随/停留、坐下/站起、切换任务、切换日程、装备物品等
- ~~攻击目标指定 — 指定女仆攻击特定实体，支持多目标队列与自动切换（未完全实现）~~
- **游戏上下文感知** — 提供世界时间、天气、在线玩家、附近实体等上下文信息，增强 AI 决策能力
- **游戏事件推送** — 将女仆受伤、玩家交互、聊天消息等游戏事件实时推送给 N.E.K.O
- **女仆聊天** — 让女仆在游戏内发送聊天消息并显示聊天气泡
- **技能系统** — 查询和使用车万女仆的 AI 技能（Skill）
- **指令执行（需确认）** — N.E.K.O 可请求执行 Minecraft 指令，但需要玩家在游戏内点击确认，防止滥用

## 环境要求

| 依赖                        | 版本                     |
| ------------------------- | ---------------------- |
| Minecraft                 | 1.21.1                 |
| NeoForge                  | 21.1.172+              |
| Java                      | 21                     |
| 车万女仆 (Touhou Little Maid) | 1.5.3+（可选，但无此模组本插件无意义） |

## 安装

1. 确保已安装 Minecraft 1.21.1 + NeoForge
2. 安装车万女仆模组
3. 将本模组 JAR 文件放入 `mods` 文件夹
4. 启动游戏

## 配置

在游戏内通过 Mod 菜单或配置文件 `neko_tlm_bridge-common.toml` 进行配置：

| 配置项                       | 默认值     | 说明                                         |
| ------------------------- | ------- | ------------------------------------------ |
| `nekoModeEnabled`         | `true`  | 启用 N.E.K.O 模式，开启后女仆的 AI 对话由 N.E.K.O 驱动     |
| `websocket.port`          | `48920` | WebSocket 服务器监听端口                          |
| `ignoreTlmBuiltinContext` | `true`  | 忽略车万女仆内置 AI 上下文，防止与 N.E.K.O 冲突             |
| `eventPushEnabled`        | `true`  | 启用游戏事件推送，将游戏事件通过 WebSocket 推送给 N.E.K.O     |
| `commandExecutionEnabled` | `false` | 启用指令执行，允许 N.E.K.O 请求执行 Minecraft 指令（需玩家确认） |

## WebSocket 协议

本模组在 `127.0.0.1:{port}` 上启动 WebSocket 服务器，仅接受本地连接。所有消息均为 JSON 格式。

### 请求消息

客户端发送的消息需包含 `type` 字段和可选的 `request_id` 字段：

```json
{
  "type": "消息类型",
  "request_id": "可选的请求ID，用于匹配响应",
  "data": { ... }
}
```

#### 支持的请求类型

| 类型                 | 说明                | data 字段                                             |
| ------------------ | ----------------- | --------------------------------------------------- |
| `ping`             | 心跳检测              | 无                                                   |
| `get_maid_status`  | 获取所有女仆状态          | 无                                                   |
| `command_maid`     | 控制女仆行为            | `maid_id`, `command`, `args`                        |
| `send_chat`        | 让女仆发送聊天消息         | `maid_id`, `message`                                |
| `get_game_context` | 获取游戏上下文           | `category`, `maid_id`(可选)                           |
| `use_skill`        | 使用/查询技能           | `skill_name`, `maid_id`(可选)                         |
| `attack_target`    | 指定女仆攻击目标          | `maid_id`, `target_entity_id` 或 `target_entity_ids` |
| `execute_command`  | 请求执行 Minecraft 指令 | `command`                                           |
| `get_config`       | 获取当前配置            | 无                                                   |

#### command\_maid 支持的指令

| command           | args                                 | 说明      |
| ----------------- | ------------------------------------ | ------- |
| `switch_follow`   | `{"follow": true/false}`             | 切换跟随/停留 |
| `switch_sit`      | `{"sit": true/false}`                | 切换坐下/站起 |
| `switch_task`     | `{"task": "任务ID"}`                   | 切换女仆任务  |
| `switch_schedule` | `{"schedule": "DAY/NIGHT/ALL"}`      | 切换日程模式  |
| `equip_item`      | `{"slot": 槽位号}` 或 `{"item": "物品ID"}` | 装备物品到主手 |

#### get\_game\_context 支持的类别

| category          | 说明               |
| ----------------- | ---------------- |
| `world`           | 世界信息（时间、天气、在线玩家） |
| `status`          | 女仆状态（生命值、任务、日程）  |
| `equipment`       | 女仆装备与背包          |
| `user`            | 女仆主人信息           |
| `effects`         | 女仆当前药水效果         |
| `position`        | 女仆与主人的位置         |
| `nearby_entities` | 女仆附近实体           |

### 响应消息

服务端返回的消息格式：

```json
{
  "type": "响应类型",
  "request_id": "对应的请求ID",
  "data": { ... }
}
```

#### 响应类型

| 类型                         | 说明               |
| -------------------------- | ---------------- |
| `pong`                     | 心跳响应             |
| `maid_status`              | 女仆状态数据           |
| `command_result`           | 指令执行结果           |
| `chat_result`              | 聊天发送结果           |
| `game_context`             | 游戏上下文数据          |
| `skill_result`             | 技能查询/使用结果        |
| `attack_target_result`     | 攻击目标设置结果         |
| `command_execution_result` | Minecraft 指令执行结果 |
| `config`                   | 当前配置             |
| `event`                    | 游戏事件推送           |
| `chat_message`             | 聊天消息推送           |
| `error`                    | 错误信息             |

### 事件推送

当启用事件推送时，服务端会主动向客户端推送以下事件：

| 事件类型              | 说明      |
| ----------------- | ------- |
| `player_interact` | 玩家与女仆交互 |
| `maid_hurt`       | 女仆受伤    |
| `chat`            | 玩家聊天消息  |

## 游戏内指令

| 指令                          | 说明                            |
| --------------------------- | ----------------------------- |
| `/neko accept <pending_id>` | 确认执行 N.E.K.O 请求的 Minecraft 指令 |
| `/neko reject <pending_id>` | 拒绝 N.E.K.O 请求的 Minecraft 指令   |

当 N.E.K.O 请求执行指令时，所有在线玩家会收到带有可点击按钮的提示消息。

## 项目结构

```
src/main/java/com/neko_tlm_bridge/
├── NekoTlmBridge.java          # 模组主类，生命周期管理
├── client/
│   └── NekoConfigScreen.java   # 配置界面
├── config/
│   └── ModConfig.java          # 配置定义
├── event/
│   └── GameEventHandler.java   # 游戏事件监听与推送
├── tlm/
│   ├── LittleMaidCompat.java       # 车万女仆 API 扩展注册
│   ├── NekoBridgeTool.java         # AI 工具：消息转发到 N.E.K.O
│   ├── NekoBridgeContext.java      # AI 上下文：桥接连接状态
│   ├── NekoExtraMaidBrain.java     # 女仆额外行为注册
│   ├── NekoAttackTargetBehavior.java  # 自定义攻击行为
│   ├── NekoAttackTargetStore.java     # 攻击目标存储与管理
│   └── NekoWebSocketServerHolder.java # WebSocket 服务器引用持有
└── ws/
    ├── NekoWebSocketServer.java     # WebSocket 服务器实现
    ├── MessageHandler.java          # 消息处理与游戏操作
    ├── NekoCommand.java             # 游戏内指令注册
    ├── PendingCommandManager.java   # 待确认指令管理
    └── Protocol.java                # 协议常量定义
```

## 构建

```bash
./gradlew build
```

构建产物位于 `build/libs/` 目录下。

## 许可证

MIT License

## 致谢

- [车万女仆 (Touhou Little Maid)](https://github.com/TartaricAcid/TouhouLittleMaid) — 提供了优秀的女仆系统与 AI 扩展 API
- [N.E.K.O](https://github.com/mc-nekoneko/neko-core) — 自然语言 AI 控制框架，很好用的猫娘👍👍👍

