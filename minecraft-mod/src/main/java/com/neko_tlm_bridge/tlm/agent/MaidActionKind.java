package com.neko_tlm_bridge.tlm.agent;

import java.util.Locale;

public enum MaidActionKind {
    NAVIGATE("navigate"),
    HARVEST_BLOCKS("harvest_blocks"),
    EXCAVATE_SEGMENT("excavate_segment"),
    AUTONOMOUS_MINING("autonomous_mining"),
    RETURN_TO_POSITION("return_to_position"),
    LEGACY_ATTACK("legacy_attack");

    private final String wireName;

    MaidActionKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static MaidActionKind fromWireName(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (MaidActionKind kind : values()) {
            if (kind.wireName.equals(normalized)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown maid action kind: " + value);
    }
}
