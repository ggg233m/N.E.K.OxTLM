# 更新日志

## v1.0.7 (2026-08-12)

### 工具调用与自主能力

- 恢复自动找矿与附近资源采集的主动 Agent 能力：新增紧凑的 `mc_mine_ore` / `mc_gather_blocks` 直连工具，以及对应的 Agent 可发现入口。
- 明确区分自主 Skill 与普通 TLM 工作模式，避免“挖钻石”或“把附近的树砍了”被误路由到 `mc_switch_task`。
- 修复主动 Agent 的前置闸无法识别挖矿、采集、跟随和寻路请求：补齐插件关键词，使低 `external_intent` 的明确 Minecraft 操作仍会进入 Agent 评估。
- 修复热更新后 Agent 仍只看到旧入口：插件启动及延迟重试时通过 SDK 运行时目录消息重发自主挖矿、采集、跟随和寻路入口。
- 同一请求同时被直连工具和主动 Agent 命中时，按 Skill 名称和规范化参数复用当前活动，避免取消并重启正在执行的任务。
- 新增常用目的地与明确坐标两类 Agent 寻路入口，并对相同目标的直连工具/主动 Agent 双重命中进行幂等复用，避免取消并重启当前路径。

### 跟随与寻路

- 修复“过来”在 TLM 原生跟随可召回的近距离场景仍误启 Agent 寻路：现在由服务端模拟距离判定，范围内使用原生跟随，只有范围外才启动主动寻路。
- 远距召回现在能从 TLM 持久数据定位并加载已卸载女仆，使用有限的随行区块票据和分段寻路走向玩家；票据在完成、失败或取消时释放。
- 远距 Agent 召回仅在真实抵达后交还跟随，避免身体租约恢复旧驻守锚点后把女仆瞬移回去；失败、取消、地表/矿井返程和普通坐标寻路不会改变跟随状态。配套 Bridge Mod 更新至 1.0.7。
- 跟随/驻守命令现在会查询真实女仆状态二次确认；跟随时还会验证女仆已经站起，验证失败不再误报成功。
- 修复 `move_maid_to_destination` 在真实 N.E.K.O SDK 中读取 `Ok/Err` 结果对象时调用字典 `.get()` 而崩溃的问题；原生跟随和远距 Agent 召回现在都兼容真实 Result 对象，并保留旧测试适配器兼容性。

### 工作模式与装备

- 修复“下棋”“五子棋”等说法无法匹配 TLM 实际 `touhou_little_maid:board_games`（显示名“游戏”）模式的问题；包含这些词的自然语言工作指令现在也能解析到游戏模式。
- 战斗工作不再只依赖物品 ID 猜测武器。Bridge Mod 1.0.7 通过 TLM `IAttackTask.isWeapon` 返回各战斗任务对当前主手的权威兼容性，支持 SlashBlade 等带攻击属性的模组武器；旧版 Bridge 仍保留 SlashBlade 兼容回退。

### 测试

- 增加真实 SDK Result 形态的回归测试，覆盖模拟距离范围内的 TLM 原生跟随与范围外的 Agent 主动寻路两条调用链。

## v1.0.6 (2026-07-18)

### 新功能

- **女仆自主行动系统**：新增完整的 Agent 运行时，让女仆可在 Java 侧自主执行多步骤行动，并由 LLM 通过工具调度。
  - 新增 `MaidAction` 契约与 `MaidActionKind` 枚举，定义 navigate / harvest_blocks / excavate_segment / autonomous_mining / return_to_position / attack_target 等行动类型。
  - 新增 `MaidActionStore` 持久化运行时，统一管理行动生命周期、状态快照与决策投递。
  - 新增冲突安全的 `HandLease` / `MaidBodyLease` 租约 API，避免多个行动抢占女仆控制权。
  - 新增 `NekoAgentBehavior` 与 `MaidActionFactory`，将行动接入 TLM 模组主循环。
  - 新增 Python 侧 `maid_agent/service.py`，对接 WebSocket 启动、取消、查询、列举行动。
  - 新增 LLM 工具：`mc_start_maid_action`、`mc_cancel_maid_action`、`mc_get_maid_action_status`、`mc_list_active_maid_actions`。
  - 新增客户端紧急停止按键与 `EmergencyStopMaidActionsPayload`，玩家可一键中断所有行动。
  - 新增行动确认优先于事件上报的顺序约定，避免 LLM 误判行动未启动。
- **自主挖矿系统**：新增持久化自主挖矿行动 `AutonomousMiningAction`，支持矿脉追踪、安全开采与受阻反馈。
  - 新增 `AutonomousMiningState` 状态机：SCAN_NEARBY → HARVEST_VEIN → VERIFY_COUNT → EXCAVATE_SEGMENT → NEXT_BRANCH → COMPLETED / BLOCKED / CANCELLED，并新增 WAITING_FOR_DECISION 用于等待 LLM 决策。
  - 新增 `MaidVeinTracker` 矿脉追踪器，使用 26 连通 BFS 识别连通矿脉，锁定当前矿脉直至完成。
  - 新增 `MiningWorldModelSavedData` 持久化挖矿世界模型，支持跨会话恢复。
  - 新增 `AutonomousMiningRecovery` 受阻重启对账逻辑，避免重复启动与通知循环。
  - 新增 `MiningPlan` 与 `MiningPlanner` 成本规划器，按距离与风险生成开采计划。
  - 新增 `MaidProgressiveBlockBreaker` 渐进式破块器，支持护甲校验与水/岩浆防护。
  - 新增 `ExcavateSegmentAction` 与 `HarvestBlocksAction`，分别处理无矿挖掘与按 selector 采集。
  - 新增 Python 侧 `mine_ore` 与 `gather_blocks` 技能，含检查点（checkpoint）原子写入与重启对账。
  - 新增挖矿 HUD 叠加层（`MiningHudOverlay` / `MiningHudClient`），仅在挖矿相关行动时显示，0.5s 刷新。
  - 新增真实累计资源统计，向 LLM 汇报实际开采量而非估算值。
- **地形感知导航与寻路**：新增 `MaidTerrainNavigator` 与配套寻路组件，支持复杂地形下的女仆移动。
  - 新增 `MaidTerrainSearch`、`MaidTerrainPath`、`MaidTerrainStep`、`MaidTerrainNodeEvaluator` 地形寻路核心。
  - 新增 `MaidTerrainSearch.FailureReason` 枚举区分 `OPEN_EXHAUSTED` 与 `EXPANSION_LIMIT`。
  - 新增 `NavigateAction` 与 `ReturnToPositionAction`，后者实现分层上升搜索以应对深层地下返回。
  - 新增 `MiningReturnRoutePlanner` 挖矿返回路径规划，生成 8 格高目标环并按距离排序。
  - 新增 `MaidTerrainBuilder` 与 `MaidTerrainInteractionSafety`，支持安全搭桥、填坑与水/岩浆封堵。
  - 新增安全隧道返回行动（safe tunnel return action），在地表返回失败时改用隧道方案。
- **女仆活动编排**：新增 `maid_activity.py` 统一管理女仆当前活动状态，避免行动冲突。
  - 新增 4 个 LLM 工具：`mc_get_maid_activity`、`mc_get_maid_capabilities`、`mc_set_maid_activity`、`mc_stop_maid_activity`。
  - 支持三种切换策略：`cancel_then_switch`、`after_current`、`reject_if_busy`。
  - 异步等待控制器进入终态（`_wait_for_controllers_terminal`），并支持 `after_current` 监视器。
- **路径调试同步**：新增 `MaidPathDebugService` 与 `MaidPathDebugClient`，在客户端可视化女仆寻路节点，便于调试。
- **配置项扩展**：新增女仆 Agent 与路径调试相关配置开关。

### 优化

- **行动执行平稳性**：优化直角转弯与平地寻路的平滑度，避免女仆抖动；行动确认消息先于事件上报。
- **返回路径搜索**：简化语义化返回目标，允许仅含高度差的目标，分层处理深层地下的地表返回搜索，避免超出搜索预算。
- **挖矿策略**：优先利用自然矿道，仅在必要时搭桥；部分背包格位计入容量评估，避免提前判定背包已满。
- **地形交互安全**：使用本地 owner proxy 进行地形方块放置；遇到拒绝放置时自动绕行重路由。
- **事件上报范围**：将陪伴上报限定到玩家本人，忽略女仆自身的建造代理事件，减少噪音。
- **文档**：在 AI 指令中明确区分 agent action 与 maid task，避免 LLM 混淆两类概念。

### 修复

- **背包溢出**：修复背包即将满时仍继续挖矿导致溢出的问题，新增 `BACKPACK_FULL` 受阻原因并触发 LLM 决策。
- **矿脉追踪**：修复矿脉追踪初始 BFS 开放条件，确保已采集成员与桥接成员均被正确处理；修复两节点挖矿抖动与无效挖矿姿态恢复。
- **挖矿进度**：修复挖矿进度与矿脉追踪的健壮性问题，确保已承诺矿脉被完整采完再切换。
- **寻路稳定性**：修复平滑路径同步、平地寻路抖动、多步探查导航抖动、全高度地形步进与跟随已校验路径节点的问题。
- **水/岩浆防护**：修复误报水封失败与占用方格封水的问题，封水前先重定位至安全位置。
- **下落方块**：修复下落方块挖掘稳定性问题，新增受控下降恢复与受阻反馈。
- **探查方向**：修复探查方向扫描问题，新增备选方向扫描；修复缺失矿石时的有界探查重试。
- **返回路径**：修复高度差目标校验、地表返回方向指引、被拒放置后的绕行重路由与深层返回搜索分层。
- **行动对账**：修复女仆行动对账健壮性、行动确认先于事件上报的顺序、组合背包物品栏支持与命名采集请求的 selector 匹配。
- **调试同步**：修复女仆调试路径的网络同步安全问题，避免同步过程影响主线程。

***

## v1.0.5 (2026-07-10)

### 新功能

- **玩家登录事件处理**：新增玩家登录事件的收发与处理逻辑，解决玩家进游戏时检测不到女仆的问题。
  - Mod 端通过 `Protocol.java` 和 `GameEventHandler.java` 广播 `player_login` 事件。
  - 插件端在桥接连接/重连、玩家登录时刷新女仆状态缓存，初始扫描失败时延迟 2 秒重试。
  - 新增 `_refresh_maid_status_cache` 和 `_delayed_refresh_maid_status` 方法统一女仆状态刷新入口。
  - `player_login` 事件不推送给 LLM，避免无效上下文。
- **MaidHelper 工具类**：将女仆查找逻辑抽离到独立工具类，统一女仆实体查找方式。
  - 新增 `getAllMaids` 方法批量获取全服女仆，替换原有的范围查询逻辑，兼容 Sable 模组。
  - 重构 `MaidStatusHandler` 和 `GameEventHandler` 中的女仆查找代码。
- **LLM 工具注册重试**：新增重发 LLM 工具注册的方法，插件启动时立即执行一次，并添加 5 秒延迟兜底重试。
- **刷新状态 UI 展示**：新增刷新状态 UI 展示和后端状态记录，优化用户反馈。

### 优化

- **桥接线程简化**：移除 MC 退出检测相关逻辑，简化桥接线程运行逻辑。
- **诊断模块精简**：移除 `diagnostics.py` 中冗余的 Java 进程检测逻辑，简化桥接诊断。
- **陪玩模式默认值**：将陪玩模式默认值从 `custom` 调整为 `standard`，更新多处配置默认值。
- **女仆状态刷新调度**：优化 Python 侧的女仆状态刷新调度逻辑，新增任务取消处理。
- **UI 面板状态同步**：将自定义的 `useLocalState` 替换为原生 `useState`，新增依赖项以正确同步 `companionMode` 状态。
- **代码健壮性**：补充参数非空校验，提升代码健壮性。

### 修复

- **LLM 工具注册失败**：修复插件初始化阶段宿主 session 未就绪导致 LLM 工具注册失败的问题。
- **UI 状态同步**：修复 UI 面板 `companionMode` 状态同步不正确的问题。

### 文档

- 更新 README 文档：补充 `get_plan` API 条目、awareness 返回数据说明、Push 聚合与 `coalesce_key` 节流说明。
- 移除冗余的 `config.json` 提及项，修正链接描述。
- 更新构建命令适配 Windows 环境（`./gradlew` → `./gradlew.bat`）。

***

## v1.0.4 (2026-07-03)

### 新功能

- **结构化游戏内目标板**：`mc_set_plan` 保持旧版 `plan` 文本兼容，同时新增 `title`、`steps`、`completed_steps`、`uncompleted_steps`、`append_steps`、`clear` 参数。
  - Python 插件侧保存当前目标板结构化状态，并渲染为纯文本同步到 Minecraft HUD。
  - 插件面板新增目标板显示与编辑，可更新标题、追加步骤、标记完成/未完成、清空。
  - 玩家通过 `/neko plan` 设置的文本仍可同步回插件侧，并解析为当前目标板状态。
  - 目标板仅用于当前 Minecraft 会话目标与 HUD/context 注入，不替代 N.E.K.O 宿主的长期记忆或通用任务系统。
- **模式切换触发增强**：强化 LLM 工具调用策略，解决明确工作请求时只聊天、不切模式的问题。
  - 玩家只说“收菜”等短命令时也应先调用工具，不先口头答应。
  - 玩家先提出具体工作后再说“切换模式/换模式”时，LLM 应承接上一轮工作意图。
  - 不确定具体任务 ID/名称时，先调用 `mc_maid_status` 查看 `available_tasks`，再用精确任务调用 `mc_switch_task`，避免依赖插件静态同义词表。
  - `mc_switch_task` 成功后会再次查询女仆状态进行验证；失败时返回结构化 `TASK_SWITCH_RECOVERABLE`、`available_tasks` 和重试提示，便于 LLM 立即二次调用精确任务。
- **陪玩活跃度预设**：新增 `companion_mode` 配置，支持 `quiet`、`standard`、`active`、`custom`。
  - 三种预设只调整女仆主动搭话相关参数：`playmate_quiet_stable_seconds`、`playmate_quiet_cooldown`、`playmate_suggestion_cooldown`。
  - `custom` 只开放这 3 个发言频率参数，不控制 Minecraft 感知频率、活动防抖、消息聚合、防刷屏限流、N.E.K.O 宿主模型、TTS 或全局工具策略。
- **桥接诊断**：新增 `diagnose_bridge` action/entry 和面板按钮。
  - 检查 Java/Minecraft 进程、WebSocket、mod 配置、聊天显示、女仆状态和已指定女仆。
  - 明确诊断范围只覆盖本插件与 Minecraft mod 的桥接边界。
- **WebSocket 端口动态配置**：新增端口配置 UI 与后端逻辑，支持运行时修改 WebSocket 连接端口，无需重启插件。
- **地下生物群系事件过滤**：awareness 新增对地下生物群系事件的过滤逻辑，避免无效推送。
- **respond 推送 coalesce_key 节流**：为主动回复推送添加分组覆盖机制，相同 key 的新推送自动覆盖旧的未消费推送，避免 LLM 忙时堆积过时回复
  - 紧急警报（受伤、低血量等）→ `mc_alert`
  - 非紧急感知（敌怪等）→ `mc_awareness`
  - 环境变化（群系/天气/昼夜/背包/钓鱼）→ `mc_event`
  - 活动状态变化 → `mc_activity`
  - 安静陪伴 → `mc_companion`
  - 主动建议 → `mc_suggestion`
  - 棋局事件 → `mc_chess`
  - 聊天消息、死亡事件、成就解锁、维度切换不设 key（每条独立保留，不覆盖）
- **饥饿警告**：感知系统新增玩家饥饿值检测
  - 饥饿值 ≤ 6 时推送紧急警告（5 分钟冷却）
  - 饥饿值 ≤ 12 且处于危险状态时推送普通提醒（10 分钟冷却）
  - Java 端 awareness 和 user context 新增 `player_food_level`、`player_saturation` 字段
- **维度切换事件**：玩家维度变化时推送 `dimension_change` 事件
  - 包含 `from_dimension` 和 `to_dimension` 字段
  - Python 端自动映射为中文名称（主世界/下界/末地）
  - Java 端 awareness 新增 `player_dimension` 字段
- **安静陪伴场景扩展**：新增 4 种活动状态识别与陪伴文本
  - 红石工程（`redstone_engineering`）：手持红石相关物品时触发
  - 下界探索（`nether_exploring`）：玩家位于下界维度时触发
  - 末地探索（`end_exploring`）：玩家位于末地维度时触发
  - 村民交易（`trading`）：手持绿宝石且有容器交互证据时触发
  - 安静陪伴排除列表调整：移除 `mob_farming`，新增 `trading`
- **游戏内计划显示**：在 Minecraft 右上角 HUD 显示当前计划
  - LLM 可通过新增 `mc_set_plan` 工具下发计划
  - 玩家可通过 `/neko plan <text>` 命令设置计划
  - 玩家可通过 `/neko plan clear` 命令清除计划
  - 计划支持多行显示（换行分隔）
  - Java 端新增 `PlanOverlayRenderer` HUD 渲染器、`SetPlanHandler` 消息处理器
  - Python 端 plan 和 goal 独立注入 LLM 上下文
- **玩家进度上下文**：awareness 新增玩家经验等级与装备耐久度字段
  - 新增 `player_experience_level`（经验等级）、`player_experience_progress`（当前等级进度 0-1）
  - 新增 `player_equipment_durability` 数组（主手/副手/头/胸/腿/脚 6 个槽位，仅记录有耐久度的物品）
  - 每项含 `slot`、`item`、`durability`、`max_durability`、`durability_ratio`（耐久百分比）
  - LLM 通过 `mc_game_context(awareness)` 主动查询时获取，不主动推送

### 优化

- **敌对生物检测逻辑**：移除硬编码的敌对生物类型列表，改用实体分类（`MobCategory.MONSTER`）判断；实体数据新增 `hostile` 字段标记，简化感知模块筛选逻辑。
- **女仆状态字段命名统一**：将 `is_underground`、`light_level` 等字段统一重命名为 `maid_is_underground`、`maid_light_level` 等带前缀命名，避免命名冲突；删除冗余的 `building` 活动类型配置和相关提示文本。
- **配置管理重构**：移除旧版 `config.json` 配置文件，将配置统一迁移至 `plugin.toml`，简化配置加载流程。
- **WebSocket 桥接稳定性**：重构桥接类新增错误记录与重连优化逻辑，增加 pong 超时重连检测，新增桥接重连后状态重置机制避免跨会话状态污染。
- **迷你游戏节流**：优化对局冷却清理和推送节流逻辑。
- **主线程任务队列**：增加限流和慢任务告警，避免慢任务阻塞。
- **诊断模块**：新增 WebSocket 握手错误检测，完善诊断覆盖范围。
- **工具调用完善**：重构任务与装备工具添加结果校验与错误提示；新增指令执行权限配置和更安全的命令执行逻辑；完善工具调用文档与 AI 指令，新增装备校验与模式查询逻辑。
- **Java 进程检测**：重构检测逻辑，精准识别 Minecraft 进程，避免误匹配其他 Java 进程。
- **UI 布局优化**：连接端口控件改为网格布局，重构面板布局优化目标板展示与操作体验。

### 修复

- **多线程安全问题**：为多个静态共享变量添加 `volatile` 关键字保证可见性。
- **WebSocket 发送稳定性**：发送消息添加异常捕获，避免连接异常导致崩溃。
- **客户端 GUI 注册**：修复仅在客户端环境注册的问题，避免服务端侧加载报错。
- **状态残留**：新增状态清理方法并在存档切换时调用，避免跨会话状态污染。
- **死亡事件上报**：修复游戏死亡事件的坐标上报和推送逻辑。
- **配置加载保存**：修复配置加载保存的异步兼容问题。
- **服务器 tick 事件**：重构服务器 tick 事件逻辑，优化代码结构。
- **聊天发送错误处理**：完善聊天发送功能的错误处理逻辑。

### 文档

- README 更新：补充维度切换事件、饥饿警告、计划显示、新活动状态、目标板、陪玩预设、桥接诊断等说明
- 使用教程更新：补充饥饿警告、维度切换、计划显示、目标板、陪玩预设等功能说明
- 补充计划使用的详细说明文档
- 汉化并优化多处注释与提示文本

***

## v1.0.3 (2026-06-14)

### 新功能

- **陪玩助手系统**：新增完整的游戏陪伴模块，包括：
  - 短期记忆管理，记录玩家近期行为
  - 活动状态推断，自动识别挖矿、钓鱼、赶路、整理物品、危险探索、刷怪等 14 种场景
  - 低打扰陪伴触发，在安静时段自然插入陪伴话术
  - 短期共同目标管理
- **主动建议与小游戏陪伴**：新增主动建议模块和小游戏陪伴模块
  - 主动建议根据场景生成一句话提醒（暗处缺火把、挖矿缺光、建造有材料、钓鱼/赶路/整理/刷怪等）
  - 小游戏陪伴处理棋局事件（五子棋/国际象棋/中国象棋），含中盘冷却和上下文裁剪
  - 新增 `playmate_minigame_feedback_cooldown` 和 `playmate_minigame_context_chars` 配置项
- **事件系统扩展**：
  - 新增玩家受伤（`player_hurt`）、击杀实体（`player_kill_entity`）事件（行为聚合统计，避免刷屏）
  - 新增方块活动事件（`block_activity`，多方块聚合推送，含倾向判断）
  - 新增容器交互（`container_interaction`）、钓鱼开始（`fishing_start`）、钓到物品（`item_fished`）事件
  - 新增棋局事件支持：五子棋、国际象棋、中国象棋事件推送（`chess_game_start`/`chess_mid_game`/`chess_game_end`）
- **行为与方块活动聚合统计**：新增受伤/击杀行为聚合和方块活动聚合逻辑，连续发生的事件合并为一条推送
  - Mod 端新增 5 个聚合配置项：`behaviorAggregateIdleTicks`、`behaviorAggregateMaxWindowTicks`、`blockActivityIdleTicks`、`blockActivityMaxWindowTicks`、`blockActivityMinCount`
- **陪玩调试日志**：新增陪玩调试日志功能，可配置日志开关与最大大小，全流程埋点记录活动、推送、建议等调试信息
- **任务系统扩展**：
  - 新增桌游/小游戏任务同义词，支持"游戏""玩游戏"等关键词触发小游戏模式
  - 为 torch 任务添加挖矿、探洞等相关同义词
- **游戏上下文增强**：新增光照和地下状态采集，用于更精准的场景判断
- **聊天配置项**：新增 `chatBubbleEnabled` 和 `chatBoxEnabled` 配置，可独立控制聊天气泡和聊天框的显示

### 优化

- 重构消息处理逻辑，将原 `MessageHandler` 拆分为多个独立的消息处理器类，实现职责单一化
- 新增消息路由分发器 `MessageRouter`，统一管理消息分发和主线程任务队列
- 重构活动推断与上下文推送流程，优化状态识别
- 统一 Minecraft 上下文 push 聚合节流，减少无效刷屏
- 重构玩家活动推断逻辑，移除光照等级判断，仅保留地下状态判断挖矿行为
- 优化伤害事件的优先级和情绪提示
- 优化对局提示文本和胜负提示话术
- 补充移动+工作复合指令处理规则，新增跟随指令带工作的特殊处理逻辑
- 更新 AI 身份说明，调整初始引导文案
- 完善主动行动原则与工具调用规范，明确跟随和任务切换的强制调用要求

### 修复

- 修复 MC 退出时未停止桥接的问题，补充调用桥接实例的 `stop` 方法清理资源
- 检测并处理 Minecraft 进程退出的情况，新增 Java 进程检测逻辑（适配 Windows 和类 Unix 系统）
- 修改棋局对局中的 `ai_behavior` 标识为 `read`，只插入上下文防止阻塞

### 文档

- README 全面更新，补充陪玩式感知功能说明、能力列表、配置项
- 新增 Mermaid 架构流程图
- 补充 Minecraft Mod 端和 Python 插件端的项目结构说明（含 playmate 子模块）
- 补充缺失的聊天配置项、行为聚合配置项、事件类型、响应类型
- 完善使用文档，新增棋局/小游戏使用说明、陪玩式感知说明、FAQ 扩展

***

## v1.0.2 (2026-06-10)

### 新功能

- **感知系统**：Python 插件新增定时感知循环，每 5 秒轮询游戏状态，自动检测并响应以下情况：
  - 低血量警告（玩家血量 < 30%，冷却 5 分钟）
  - 着火警告（冷却 2 分钟）
  - 溺水警告（冷却 2 分钟）
  - 近处敌对生物警告（新敌怪出现且距离 < 10 格，冷却 3 分钟）
  - 复仇情绪（死亡复活后一次性触发）
  - 食物分享提示（背包有食物且玩家血量 < 70%，冷却 5 分钟）
  - 矿洞暗处提醒（地下 + 亮度 < 7，冷却 10 分钟）
  - 玩家距离感知（远离 > 50 格 / 归来 < 30 格，带迟滞区间防抖，冷却 1 分钟）
- **上下文静默注入**（不触发 LLM 回复，仅丰富上下文）：
  - 玩家手持物品变化检测（2 次连续检测防抖）
  - 附近结构/地标发现（128 格内新出现的村庄、地牢等）
- **游戏事件推送新增**：
  - 成就解锁事件
  - 群系切换事件（10 秒防抖避免边界反复触发）
  - 背包物品变化事件（打开背包时快照，关闭时对比差异，无变化不推送）
  - 玩家聊天消息推送
- **WebSocket 协议新增**：
  - `awareness` — 感知系统综合数据查询类别
  - `set_monitored_maid` — 设置监控的女仆 ID（用于背包追踪）
  - `config_update` — 配置变更推送

### 优化

- 敌怪检测距离阈值从 16 格调整为 10 格，远处敌怪不再注入上下文（LLM 需通过 `mc_game_context(nearby_entities)` 主动查询）
- 敌怪同类型自动计数合并显示（如"苦力怕x2、骷髅"）
- 手持物品检测采用 2 次防抖机制（约 10 秒确认），避免频繁切换导致误报
- 结构名称使用原始 ID（如 `minecraft:village`）替代友好名
- 事件过滤优化：仅携带 `maid_id` 的事件需要校验归属，无 `maid_id` 的事件（如玩家死亡、天气变化）直接放行
- 物品 ID 和数量分隔符更换为 `|`，解决含有冒号的 ID 解析错误问题
- 感知检测统一改为"当前状态检查 + 冷却"模式，不再依赖旧状态对比
- 非紧急感知事件使用 `ai_behavior="read"` 注入上下文，避免不必要地触发 LLM 回复

### 修复

- 修复 awareness 检测中 `now` 变量未定义导致的运行时错误
- 修复背包物品变化不推送的问题

### 文档

- README 全面更新，补充 WebSocket 协议、感知系统、事件推送等章节
- 新增 [使用教程](使用教程.md)
- 完善 `.gitignore`，排除构建产物和外部依赖
- 移除冗余的 `minecraft-mod/README.md`

***

## v1.0.1

- 版本号从 1.0.0 升级到 1.0.1
- 攻击模式切换前需检查武器（LLM 规则）
- 停止工作时必须切换待机而非仅回复文字（LLM 规则）
- 新增聊天气泡和聊天框配置
- 移除冗余的 `mc_attack_target` 工具
- 简化任务匹配逻辑
- 调整攻击指令返回字段格式

## v1.0.0

- 初始发布
