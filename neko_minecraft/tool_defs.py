"""LLM 工具元数据常量 — 定义所有 @llm_tool 的 name、description 和 parameters，确保与 SDK 装饰器解耦"""

MC_MAID_STATUS = {
    "name": "mc_maid_status",
    "description": (
        "查询你在Minecraft世界中女仆的当前状态。"
        "返回所有女仆的信息，包括id(UUID格式)、名字、血量、位置、是否坐着、是否跟随、主人名字、手持物品等。"
        "返回数据还包含 available_tasks 和 available_modes，可用于决定应该切换到哪个工作模式。"
        "当玩家询问'有哪些模式/工作/能切换什么'时，必须先调用此工具，然后只按 available_modes 里的 id/name 回答；"
        "不要把搭房子、下矿洞、整理背包等未出现在 available_modes 中的玩法目标说成工作模式。"
        "当玩家询问'现在什么模式/你是什么模式/什么模式'时，必须先调用此工具，并按 current_mode/current_mode_answer 或 selected_maid.current_mode 回答真实当前模式。"
        "当玩家要求切换工作/模式但你不确定具体任务ID或任务名时，先调用此工具查看可用模式，再调用 mc_switch_task；不要直接反问玩家。"
    ),
    "parameters": {
        "type": "object",
        "properties": {},
    },
}

MC_SWITCH_FOLLOW = {
    "name": "mc_switch_follow",
    "description": (
        "切换女仆的跟随/驻守模式。"
        "当玩家要求女仆跟随、跟上、过来、不要走远时，action设为follow；"
        "当玩家要求女仆驻守、留在原地、不要跟随时，action设为stay。"
        "如果女仆正坐着且要跟随，会自动站起。"
        "若 Skill/Agent Action 正在控制女仆，本工具会拒绝以避免触发 USER_OVERRIDE；"
        "需要抢占时先调用 mc_stop_maid_activity，再重试本工具。"
        "【必须调用】玩家说'跟我来'、'过来'、'一起去'、'别离太远'时，不要只文字回应，必须调用本工具。"
        "【重要】如果玩家在跟随指令中还提到了要做什么工作（如'过来玩游戏''跟着我去打草''过来种田''过来收菜''跟我去挖矿''跟我去打怪'），在调用本工具的同时，必须也调用 mc_switch_task 切换到对应的工作模式。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "description": "follow=跟随主人移动，stay=驻守原地不动",
                "enum": ["follow", "stay"],
            },
        },
    },
}

MC_SWITCH_SIT = {
    "name": "mc_switch_sit",
    "description": (
        "切换女仆的坐下/站起状态。"
        "当玩家要求女仆坐下、休息时，action设为sit；"
        "当玩家要求女仆站起、起来、站起来时，action设为stand。"
        "坐下和跟随是两个独立的状态：坐下控制姿势，跟随控制移动。"
        "若 Skill/Agent Action 正在执行，先调用 mc_stop_maid_activity，不能并行改姿势。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "action": {
                "type": "string",
                "description": "sit=坐下，stand=站起",
                "enum": ["sit", "stand"],
            },
        },
    },
}

MC_SWITCH_TASK = {
    "name": "mc_switch_task",
    "description": (
        "切换女仆的工作模式/任务/职业，让女仆执行某种工作。"
        "task参数优先传 available_tasks 中的精确任务ID或任务名称；如果还没查过可用任务，可先调用 mc_maid_status。"
        "也可以传玩家描述的工作内容（如'收菜'、'收获'、'打草'、'收甘蔗'、'种田'、'攻击'、'待机'、'小游戏'），插件会尽力匹配，但不同 mod 的任务名可能不同，精确ID/名称更可靠。"
        "调用成功后工具会再次查询女仆状态验证 current_task 是否真的切换到 expected_task；请根据 verified 字段判断是否已经生效。"
        "如果返回 TASK_SWITCH_VERIFY_FAILED 或 verified=false，表示真实当前模式不是目标模式，不能说已经切换成功或正在执行目标任务。"
        "如果返回 TASK_SWITCH_RECOVERABLE，说明这次没切成但会返回 available_tasks 和 retry_hint；应立刻选择最接近的精确 id/name 再次调用本工具，不要只口头说明失败。"
        "【必须调用】只要玩家明确要求你开始、停止或更换游戏内工作模式，就必须调用本工具，不能只用文字答应，也不要先反问。"
        "【短命令也必须调用】玩家只说'收菜'、'收获'、'打草'、'种田'、'打怪'、'休息'、'待机'、'下棋'时，已经是明确模式切换意图。"
        "【承接上下文】如果玩家先说过'收菜'，随后说'切换模式'、'换模式'、'切模式'，应承接上一轮工作意图并调用本工具；不知道具体模式ID时先调用 mc_maid_status 查 available_tasks。"
        "典型必须调用场景：'打怪/保护我/清怪'、'收菜/收获/收作物/种田/收田/收甘蔗/打草'、'剪羊毛/挤奶/喂动物'、'来玩游戏/下棋/小游戏'、'停下/休息/待机'。"
        "这些典型场景只是理解玩家意图的例子，不是可用工作模式列表；回答'有哪些模式'时禁止引用这些例子，必须先用 mc_maid_status 的 available_modes。"
        "玩家给出明确方块、方块标签或坐标的挖掘请求时，不要调用本工具冒充挖矿，应使用 mc_start_maid_action。"
        "只有笼统的下矿/探洞目标暂时无法形成原子动作时，才可切换到真实存在的火把/照明辅助模式并跟随玩家。"
        "如果玩家要求女仆打怪、杀怪，切换到攻击模式即可（如'攻击'、'打怪'），女仆会自行搜索并攻击附近的敌对生物。"
        "【重要】切换到攻击模式前，应先调用 mc_game_context(category='equipment') 检查女仆主手是否有武器。如果主手为空或只有非武器物品，应提醒玩家给女仆装备武器后再切换。"
        "【重要】当玩家说'停下'、'别干了'、'休息'、'不做了'等要求停止当前工作时，必须调用此工具切换到待机模式（task传'待机'或'idle'），不能只回复文字而不操作。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "task": {
                "type": "string",
                "description": "要切换的任务。优先使用 mc_maid_status 返回的 available_tasks 中的精确任务ID或名称；也可传玩家原话作为兜底，例如：收菜、打怪、种田、待机。不同 mod 的任务名可能不同，精确ID/名称最可靠。",
            },
        },
        "required": ["task"],
    },
}

MC_SWITCH_SCHEDULE = {
    "name": "mc_switch_schedule",
    "description": (
        "切换女仆的日程安排。"
        "schedule=day白天工作、schedule=night夜晚工作、schedule=all全天工作。"
        "活动执行中修改日程会破坏身体租约，因此忙碌时应先调用 mc_stop_maid_activity。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "schedule": {
                "type": "string",
                "description": "日程安排：day(白天)、night(夜晚)、all(全天)",
                "enum": ["day", "night", "all"],
            },
        },
    },
}

MC_EQUIP_ITEM = {
    "name": "mc_equip_item",
    "description": (
        "将女仆背包中的物品装备到主手。"
        "item=物品ID（如item=minecraft:torch、minecraft:diamond_sword）或slot=背包槽位编号指定物品。"
        "玩家说'举火把'、'拿火把'、'换火把'、'把火把拿手上'时，应该调用本工具装备 minecraft:torch，而不是只切换工作模式。"
        "本工具会在装备后重新查询状态验证 main_hand_item；只有 verified=true 才表示主手真的切换成功。"
        "Agent/Skill 占用主手时本工具会拒绝以避免 HAND_CONFLICT；需要换装备时先安全停止当前活动。"
        "如果返回 EQUIP_VERIFY_FAILED 或 verified=false，不能告诉玩家已经拿好了，必须说明当前主手实际物品并重试或提醒检查背包。"
        "'插火把/照明模式'是工作模式切换，使用 mc_switch_task；'举火把/拿火把'是主手装备，使用本工具。"
    ),
    "parameters": {
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
}

MC_SEND_CHAT = {
    "name": "mc_send_chat",
    "description": (
        "在Minecraft游戏内显示聊天消息（聊天气泡+聊天框）。"
        "你的语音回复由TTS系统自动处理，此工具仅用于在游戏画面上显示文字。"
        "不要用它重复你已经在语音中说过的话，避免重复发言。"
        "适用场景：需要在游戏画面上显示重要提示、让其他玩家看到消息"
        "注意：管理员可能在配置中关闭了聊天气泡或聊天框，此时消息可能只以其中一种方式显示，或完全无法显示。"
    ),
    "parameters": {
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
}

MC_GAME_CONTEXT = {
    "name": "mc_game_context",
    "description": (
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
    "parameters": {
        "type": "object",
        "properties": {
            "category": {
                "type": "string",
                "description": "要查询的上下文分类",
                "enum": ["status", "world", "equipment", "user", "effects", "position", "nearby_entities"],
            },
        },
    },
}

MC_START_MAID_ACTION = {
    "name": "mc_start_maid_action",
    "description": (
        "让已绑定女仆开始一个服务端自主动作。navigate 会以非破坏方式主动寻路到指定坐标；"
        "return_to_position 会优先利用已记录矿道返回指定坐标，必要时安全清障、搭桥、补足支撑或封水，"
        "并始终保留玩家可步行的一格宽两格高通路；"
        "harvest_blocks 会前往目标方块或搜索附近指定方块，并可在 search_radius 内通过 Java 地形感知"
        "规划清理安全、允许破坏且工具条件满足的阻挡，进行短距离下挖或开通道后采集；"
        "harvest_blocks 是明确坐标、只搜附近、精确单块或调试 mining_plan 的底层原子动作；"
        "普通自动找矿、开矿道或累计指定数量应使用 mc_start_skill(skill='mine_ore')。"
        "旧的 selector 持续探矿与 mining_plan 字段仅保留为底层协议兼容能力，不是默认高层方案。"
        "工具只返回是否接受，动作完成或失败会异步通知。新动作默认会覆盖旧动作。"
        "明确要求去某坐标、主动挖掘或采集时应调用本工具，不要用 mc_switch_task 假装挖矿。"
        "按名称采集资源（例如挖石头、挖煤、砍木头）必须使用 selector；"
        "target_pos 仅限玩家明确给出或可信工具返回的方块坐标，禁止使用玩家/女仆坐标或猜测坐标。"
        "矿石优先使用 minecraft:*_ores 标签选择器；矿石 selector 默认 vein_mining=true 并尝试采完整矿脉。"
        "显式 mining_plan.mode=nearby 可将原子采集限制为附近扫描。"
        "矿石持续探矿会强制使用 timeout_ms=0（无常规截止时间），直到完成、急停或安全故障。"
        "普通 navigate 不会破坏地形；harvest_blocks 仍不会搭桥或垫方块；"
        "return_to_position 的搭建会真实消耗女仆背包中的安全方块。所有动作都不会强制加载未加载区块。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "kind": {
                "type": "string",
                "enum": ["navigate", "harvest_blocks", "return_to_position"],
                "description": (
                    "navigate=非破坏性寻路，harvest_blocks=主动采集方块，"
                    "return_to_position=沿矿道安全返程并按需修路"
                ),
            },
            "args": {
                "type": "object",
                "description": (
                    "navigate: {target:{x,y,z}, speed?, stop_distance?}；"
                    "return_to_position: {target:{x,y,z}, speed?, stop_distance?, operation_id?,"
                    "route_policy?:recorded_tunnels_first|safe_shortest,"
                    "placement_policy?:disabled|safe_support_and_water_seal,max_placements?:0..4096}。"
                    "operation_id 省略时服务端选择该女仆同维度最近的挖矿记录；默认优先已有矿道并允许"
                    "消耗普通稳定方块搭桥、补支撑或封水，max_placements=0 表示不设人工上限。"
                    "返程路线始终要求玩家也能步行通过，不能关闭两格净空和稳定支撑约束。"
                    "harvest_blocks: target_pos 与 selector 二选一。挖石头等按资源名称的请求必须传"
                    "selector，例如 {type:'tag', id:'minecraft:base_stone_overworld'}；target_pos 只能是玩家明确"
                    "指定或可信工具返回的方块坐标，不得猜测。selector 也可使用 tag；可传 search_radius、"
                    "max_blocks、vein_mining、tool_policy(require_correct|allow_wrong)、speed。矿石优先传"
                    "tag selector（如 minecraft:diamond_ores），不要只传单一 minecraft:diamond_ore；tag id"
                    "以 _ores 结尾或 block id 以 _ore 结尾时，省略 vein_mining 会默认 true、max_blocks 默认"
                    "1 且允许 1..64；此数量只是最低目标，一旦命中就必须采尽整个 26 邻接连通矿脉。"
                    "不可达、受保护或区块未加载时阻塞/失败，不得按数量提前成功。vein_mining=false 时 max_blocks 默认 1、范围 1..8。"
                    "附近原子采集明确给出数量时设置对应 max_blocks；自动找矿或累计数量改用 mine_ore Skill 的"
                    "target_count。说只挖一块时设置 vein_mining=false,max_blocks=1。显式 mode=nearby 可限制"
                    "为附近扫描。持续探矿是旧协议兼容能力；只有低层调试或明确要求原子动作方案时才传"
                    "mining_plan：{mode:nearby|forward_tunnel|staircase_down|auto,"
                    "direction:maid_facing|north|south|east|west,max_distance:1..16,max_depth:0..12,"
                    "max_segments:1..4,excavation_budget:0..256}。max_segments/excavation_budget 是旧协议"
                    "兼容字段，服务端不再用它们终止动作。非 nearby 模式只允许与 selector 搭配；forward_tunnel 的"
                    "max_depth 必须为 0；staircase_down 要求 max_distance>=max_depth，auto 要求"
                    "max_distance>max_depth。max_distance/max_depth 只定义每段矿道形状，段结束后会从女仆"
                    "实际位置继续；不构成总上限。harvest_blocks 可清理安全可破坏"
                    "阻挡；navigate 始终非破坏性；harvest_blocks 不搭桥或垫方块；"
                    "return_to_position 可安全修路但不会强制加载未加载区块"
                ),
                "additionalProperties": True,
            },
            "action_id": {
                "type": "string",
                "description": "可选幂等 UUID；省略时插件自动生成",
            },
            "timeout_ms": {
                "type": "integer",
                "minimum": 0,
                "maximum": 120000,
                "description": (
                    "0=无常规截止时间；矿石 selector 会强制使用0；"
                    "return_to_position 默认0；其它动作默认60000"
                ),
            },
            "replace_existing": {
                "type": "boolean",
                "description": "是否安全终止并覆盖女仆当前动作，默认 true",
            },
        },
        "required": ["kind", "args"],
    },
}

MC_CANCEL_MAID_ACTION = {
    "name": "mc_cancel_maid_action",
    "description": (
        "取消正在执行的女仆 Agent 动作。可传 action_id；省略时取消已绑定女仆最近的进行中动作。"
        "玩家说停下寻路、停止挖掘或取消刚才动作时必须调用本工具。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "action_id": {"type": "string", "description": "要取消的动作 UUID；可省略"},
        },
    },
}

MC_GET_MAID_ACTION_STATUS = {
    "name": "mc_get_maid_action_status",
    "description": "按 action_id 查询女仆 Agent 动作的服务端真实状态、阶段和终止原因。",
    "parameters": {
        "type": "object",
        "properties": {
            "action_id": {"type": "string", "description": "动作 UUID"},
        },
        "required": ["action_id"],
    },
}

MC_LIST_ACTIVE_MAID_ACTIONS = {
    "name": "mc_list_active_maid_actions",
    "description": "列出已绑定女仆当前仍在服务端执行或清理中的 Agent 动作。",
    "parameters": {"type": "object", "properties": {}},
}

MC_START_SKILL = {
    "name": "mc_start_skill",
    "description": (
        "启动由 Python SkillRunner 持久化编排的高级女仆技能。当前支持 mine_ore。"
        "mine_ore 默认只启动一个 Java autonomous_mining 子动作；世界感知、选路、开矿道、"
        "重规划和数量累计均由 Java 持续完成，LLM 只提供目标和有限偏好，不编排逐段动作。"
        "Java MiningPlanner 会同时比较天然通道、清障、目标矿层、搭桥/垫脚和封水候选的"
        "预计时间、风险、材料与近期访问成本，不再由固定状态分支硬选路线。"
        "自主矿工的路线清障可挖掘任何允许破坏且工具支持的矿石：目标矿石计入 target_count，"
        "其他矿石正常掉落但不计入目标。默认 placement_policy=safe_support_and_water_seal，"
        "会从女仆真实背包消耗普通实心方块来搭桥、补足脚下支撑或封水；不会复制物品，"
        "不绕过领地保护，不会封岩浆或处理大型水域。"
        "execution_mode=legacy 仅用于显式回退和旧检查点兼容。target_count 是最低目标，"
        "最终 blocks_harvested 可能超出。启动只表示已接受，必须等待异步 Skill 终态。"
        "Java 只有在无法安全继续且 decision_required=true 时才以 BLOCKED 请求决策；"
        "当前无原地恢复协议，需按诊断调整参数后新建 Skill。不要与提示词/RAG用途的"
        "mc_use_skill 混淆。普通自动找矿优先使用本工具；原子动作调试才使用"
        "mc_start_maid_action。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "skill": {
                "type": "string",
                "enum": ["mine_ore"],
                "description": "高级技能名；当前仅支持 mine_ore",
            },
            "args": {
                "type": "object",
                "properties": {
                    "selector": {
                        "type": "object",
                        "properties": {
                            "type": {"type": "string", "enum": ["block", "tag"]},
                            "id": {
                                "type": "string",
                                "description": "带命名空间的矿物方块/标签ID，如 minecraft:diamond_ores",
                            },
                        },
                        "required": ["type", "id"],
                        "additionalProperties": False,
                    },
                    "target_count": {
                        "type": "integer", "minimum": 1, "maximum": 4096,
                        "description": "最低目标方块数，只决定是否寻找下一矿脉；已命中的连通矿脉必须挖尽，结果可超出该数量",
                    },
                    "target_metric": {
                        "type": "string", "enum": ["blocks_harvested"],
                        "description": "只按服务端确认的实际采集方块计数",
                    },
                    "strategy": {
                        "type": "string", "enum": ["fishbone", "auto"],
                        "description": "旧鱼骨回退兼容字段；autonomous 模式不用于逐段规划",
                    },
                    "execution_mode": {
                        "type": "string", "enum": ["autonomous", "legacy"],
                        "description": "默认 autonomous，由 Java 全程自主；legacy 仅显式回退",
                    },
                    "direction": {
                        "type": "string",
                        "enum": ["auto", "north", "east", "south", "west"],
                        "description": "可选方向偏好；默认 auto 由 Java 选择",
                    },
                    "shape": {
                        "type": "string", "enum": ["auto", "level", "staircase_down"],
                        "description": "可选矿道形状偏好；默认 auto 由 Java 选择",
                    },
                    "segment_length": {
                        "type": "integer", "minimum": 1, "maximum": 8,
                        "description": "Java 自主规划的基础段长，默认8",
                    },
                    "speed": {
                        "type": "number", "minimum": 0.4, "maximum": 1.0,
                        "description": "移动速度，默认0.7",
                    },
                    "discovery_mode": {
                        "type": "string", "enum": ["loaded_scan", "exposed_only"],
                        "description": "默认 loaded_scan 扫描已加载区域；exposed_only 只认暴露矿物",
                    },
                    "placement_policy": {
                        "type": "string",
                        "enum": ["disabled", "safe_support_and_water_seal"],
                        "description": (
                            "默认 safe_support_and_water_seal，允许消耗女仆背包中的普通实心方块"
                            "搭桥、垫脚和封水；disabled 禁止任何路线放置"
                        ),
                    },
                    "max_placements": {
                        "type": "integer", "minimum": 0, "maximum": 4096,
                        "description": "最多消耗的路线建筑方块数；默认0表示不设人工上限，仍受背包材料限制",
                    },
                },
                "required": ["selector", "target_count", "target_metric"],
                "additionalProperties": False,
            },
            "skill_id": {
                "type": "string",
                "description": "可选幂等 UUID；省略时自动生成",
            },
            "replace_existing": {
                "type": "boolean",
                "description": "是否安全取消该女仆当前 Skill 后启动，默认 true",
            },
        },
        "required": ["skill", "args"],
    },
}

MC_CANCEL_SKILL = {
    "name": "mc_cancel_skill",
    "description": (
        "取消高级女仆 Skill 及其当前内部动作。skill_id 可省略，此时取消已绑定女仆当前 Skill。"
        "玩家要求停止自动挖矿或鱼骨矿道时必须调用。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "skill_id": {"type": "string", "description": "可选 Skill UUID"},
        },
    },
}

MC_GET_SKILL_STATUS = {
    "name": "mc_get_skill_status",
    "description": "按 skill_id 查询高级女仆 Skill 的真实检查点、阶段、实际累计数和终态。",
    "parameters": {
        "type": "object",
        "properties": {
            "skill_id": {"type": "string", "description": "Skill UUID"},
        },
        "required": ["skill_id"],
    },
}

MC_LIST_SKILLS = {
    "name": "mc_list_skills",
    "description": "列出已绑定女仆的高级 Skill 检查点，可选择是否包含最近终态。",
    "parameters": {
        "type": "object",
        "properties": {
            "include_terminal": {
                "type": "boolean",
                "description": "是否包含最近成功、失败、取消或阻塞的 Skill，默认 true",
            },
        },
    },
}

MC_GET_MAID_ACTIVITY = {
    "name": "mc_get_maid_activity",
    "description": (
        "查询女仆当前由谁控制以及正在进行的统一活动。返回 Skill、Agent Action、"
        "TLM 工作模式、待处理切换和真实女仆状态；不要只凭先前对话猜测当前活动。"
    ),
    "parameters": {"type": "object", "properties": {}},
}

MC_GET_MAID_CAPABILITIES = {
    "name": "mc_get_maid_capabilities",
    "description": (
        "查询当前女仆真实可用的 TLM 工作模式，以及插件注册的 Agent Action、"
        "高级 Skill 和支持的活动切换策略。不同整合包的 TLM 模式以返回结果为准。"
    ),
    "parameters": {"type": "object", "properties": {}},
}

MC_SET_MAID_ACTIVITY = {
    "name": "mc_set_maid_activity",
    "description": (
        "统一切换女仆活动。activity_type=tlm_task 时传 task；agent_action 时传 kind/args；"
        "skill 时传 skill/args；idle 表示安全停止后切到待机。"
        "需要从矿井沿可供玩家通行的安全路线返回坐标时，agent_action 使用"
        "kind=return_to_position，并在 args.target 提供可信目标坐标。"
        "cancel_then_switch 会先等待当前 Skill/Action 真正终止并释放身体租约后再切换；"
        "after_current 排队等待自然完成；reject_if_busy 在忙碌时拒绝。"
        "职业或玩法切换优先使用本工具，避免直接切 TLM task 导致 Agent 被 USER_OVERRIDE。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "activity_type": {
                "type": "string",
                "enum": ["tlm_task", "agent_action", "skill", "idle"],
                "description": "目标活动类型",
            },
            "task": {
                "type": "string",
                "description": "tlm_task 的任务 ID、名称或工作描述",
            },
            "kind": {
                "type": "string",
                "description": "agent_action 的动作类型",
            },
            "skill": {
                "type": "string",
                "description": "skill 的技能名称",
            },
            "args": {
                "type": "object",
                "description": "Agent Action 或 Skill 参数",
            },
            "switch_policy": {
                "type": "string",
                "enum": ["cancel_then_switch", "after_current", "reject_if_busy"],
                "description": "当前忙碌时的切换方式，默认 cancel_then_switch",
            },
            "request_id": {
                "type": "string",
                "description": "可选幂等请求 UUID；重试同一次切换时复用",
            },
        },
        "required": ["activity_type"],
    },
}

MC_STOP_MAID_ACTIVITY = {
    "name": "mc_stop_maid_activity",
    "description": (
        "统一停止女仆当前的高级 Skill、Agent Action 或 TLM 工作。"
        "会等待服务端动作完成取消和租约清理，再按需切换到真实待机任务。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "switch_to_idle": {
                "type": "boolean",
                "description": "停止后是否切换到 TLM 待机模式，默认 true",
            },
            "request_id": {
                "type": "string",
                "description": "可选幂等请求 UUID；重试同一次停止时复用",
            },
        },
    },
}

MC_USE_SKILL = {
    "name": "mc_use_skill",
    "description": (
        "触发车万女仆AI系统中已注册的Skill（技能/提示词包）。"
        "普通Skill触发时将行为规范注入对话上下文；"
        "knowledge类型Skill触发RAG子对话，从知识库中检索相关信息。"
        "不要编造skill_name，只能使用已知的Skill名称。如果不确定有哪些可用Skill，不要调用此工具。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "skill_name": {
                "type": "string",
                "description": "要触发的Skill名称，必须是已注册的Skill名称，不要编造",
            },
        },
        "required": ["skill_name"],
    },
}

MC_EXECUTE_COMMAND = {
    "name": "mc_execute_command",
    "description": (
        "请求执行Minecraft服务器指令。"
        "command=指令内容（如/time set day、/weather clear、/tp等）。"
        "指令发送后，游戏内会显示确认提示，需要玩家点击确认后才会执行。"
        "如果玩家拒绝或超时（120秒），指令不会被执行。"
        "此功能需要在游戏内N.E.K.O桥接配置中开启「指令执行」选项。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "command": {
                "type": "string",
                "description": "要执行的Minecraft服务器指令，如 /time set day、/weather clear、/gamemode survival",
            },
        },
        "required": ["command"],
    },
    "timeout": 120,
}

MC_SET_PLAN = {
    "name": "mc_set_plan",
    "description": (
        "设置或更新游戏内右上角显示的当前 Minecraft 目标板。"
        "这是显示/记录工具，不是行动工具；不能用它代替 mc_switch_task、mc_switch_follow、mc_switch_sit。"
        "这是插件侧的轻量目标板，只负责保存、显示和注入当前游戏目标；不要把它当作 N.E.K.O 宿主的长期任务系统。"
        "仅在玩家明确要求记录/显示/更新目标板，或已经讨论了多步骤 Minecraft 目标时调用。"
        "不要在普通工作模式切换后顺手调用；例如玩家只说'去打怪'、'收菜'、'休息'时，只应调用 mc_switch_task，禁止调用本工具。"
        "新建目标时优先传 title 和 steps；完成进度变化时传 completed_steps 或 uncompleted_steps（1 基序号）；"
        "追加步骤时传 append_steps；clear=true 或 plan='' 可清除目标板。"
        "兼容旧用法：plan 参数仍可传多行文本，系统会解析成结构化目标板并显示。"
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "plan": {
                "type": "string",
                "description": "兼容旧用法的计划文本，多行用换行分隔；空字符串清除目标板",
            },
            "title": {
                "type": "string",
                "description": "当前目标板标题，例如'今天先把据点搭起来'",
            },
            "steps": {
                "type": "array",
                "description": "替换全部步骤。每项是一条具体 Minecraft 步骤，按显示顺序排列",
                "items": {"type": "string"},
            },
            "completed_steps": {
                "type": "array",
                "description": "标记为完成的步骤序号，使用 1 基序号，例如 [1, 3]",
                "items": {"type": "integer"},
            },
            "uncompleted_steps": {
                "type": "array",
                "description": "重新标记为未完成的步骤序号，使用 1 基序号",
                "items": {"type": "integer"},
            },
            "append_steps": {
                "type": "array",
                "description": "追加到当前目标板末尾的新步骤",
                "items": {"type": "string"},
            },
            "clear": {
                "type": "boolean",
                "description": "是否清除当前目标板",
            },
        },
    },
}
