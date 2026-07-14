package com.neko_tlm_bridge.ws;

public final class Protocol {
    public static final String TYPE_GET_MAID_STATUS = "get_maid_status";
    public static final String TYPE_COMMAND_MAID = "command_maid";
    public static final String TYPE_SEND_CHAT = "send_chat";
    public static final String TYPE_GET_GAME_CONTEXT = "get_game_context";
    public static final String TYPE_USE_SKILL = "use_skill";
    public static final String TYPE_EXECUTE_COMMAND = "execute_command";
    public static final String TYPE_ATTACK_TARGET = "attack_target";
    public static final String TYPE_GET_CONFIG = "get_config";
    public static final String TYPE_SET_MONITORED_MAID = "set_monitored_maid";
    public static final String TYPE_SET_PLAN = "set_plan";
    public static final String TYPE_GET_PLAN = "get_plan";
    public static final String TYPE_START_MAID_ACTION = "start_maid_action";
    public static final String TYPE_CANCEL_MAID_ACTION = "cancel_maid_action";
    public static final String TYPE_GET_MAID_ACTION_STATUS = "get_maid_action_status";
    public static final String TYPE_LIST_ACTIVE_MAID_ACTIONS = "list_active_maid_actions";
    public static final String TYPE_PING = "ping";

    public static final String TYPE_MAID_STATUS = "maid_status";
    public static final String TYPE_GAME_CONTEXT = "game_context";
    public static final String TYPE_SKILL_RESULT = "skill_result";
    public static final String TYPE_EVENT = "event";
    public static final String TYPE_CHAT_MESSAGE = "chat_message";
    public static final String TYPE_PONG = "pong";
    public static final String TYPE_ERROR = "error";
    public static final String TYPE_COMMAND_RESULT = "command_result";
    public static final String TYPE_CHAT_RESULT = "chat_result";
    public static final String TYPE_COMMAND_EXECUTION_RESULT = "command_execution_result";
    public static final String TYPE_ATTACK_TARGET_RESULT = "attack_target_result";
    public static final String TYPE_CONFIG = "config";
    public static final String TYPE_CONFIG_UPDATE = "config_update";
    public static final String TYPE_PLAN_RESULT = "plan_result";
    public static final String TYPE_PLAN_UPDATE = "plan_update";
    public static final String TYPE_MAID_ACTION_START_RESULT = "maid_action_start_result";
    public static final String TYPE_MAID_ACTION_CANCEL_RESULT = "maid_action_cancel_result";
    public static final String TYPE_MAID_ACTION_STATUS = "maid_action_status";
    public static final String TYPE_MAID_ACTION_LIST = "maid_action_list";
    public static final String TYPE_MAID_ACTION_PROGRESS = "maid_action_progress";
    public static final String TYPE_MAID_ACTION_FINISHED = "maid_action_finished";

    // New event types for companion awareness
    public static final String EVENT_MAID_HURT = "maid_hurt";
    public static final String EVENT_MAID_DEATH = "maid_death";
    public static final String EVENT_PLAYER_DEATH = "player_death";
    public static final String EVENT_WEATHER_CHANGE = "weather_change";
    public static final String EVENT_TIME_PHASE_CHANGE = "time_phase_change";
    public static final String EVENT_INVENTORY_CHANGE = "inventory_change";
    public static final String EVENT_ADVANCEMENT = "advancement";
    public static final String EVENT_BIOME_CHANGE = "biome_change";
    public static final String EVENT_BLOCK_ACTIVITY = "block_activity";
    public static final String EVENT_PLAYER_HURT = "player_hurt";
    public static final String EVENT_PLAYER_KILL_ENTITY = "player_kill_entity";
    public static final String EVENT_CONTAINER_INTERACTION = "container_interaction";
    public static final String EVENT_FISHING_START = "fishing_start";
    public static final String EVENT_ITEM_FISHED = "item_fished";
    public static final String EVENT_PLAYER_LOGIN = "player_login";

    // Chess game events
    public static final String EVENT_CHESS_GAME_START = "chess_game_start";
    public static final String EVENT_CHESS_MID_GAME = "chess_mid_game";
    public static final String EVENT_CHESS_GAME_END = "chess_game_end";
    public static final String EVENT_DIMENSION_CHANGE = "dimension_change";

    private Protocol() {}
}
