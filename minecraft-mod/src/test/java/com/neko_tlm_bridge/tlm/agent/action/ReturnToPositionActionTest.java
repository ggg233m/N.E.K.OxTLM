package com.neko_tlm_bridge.tlm.agent.action;

import com.google.gson.JsonObject;
import com.neko_tlm_bridge.tlm.agent.MaidActionKind;
import com.neko_tlm_bridge.tlm.agent.MaidActionResource;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReturnToPositionActionTest {
    @Test
    void parsesSafeConstructionDefaults() {
        ReturnToPositionAction action = ReturnToPositionAction.fromArgs(args());

        assertEquals(MaidActionKind.RETURN_TO_POSITION, action.kind());
        assertEquals(Set.of(MaidActionResource.MOVE, MaidActionResource.HAND,
                MaidActionResource.BREAK, MaidActionResource.PLACE), action.resources());
    }

    @Test
    void disabledConstructionDoesNotClaimPlaceResource() {
        JsonObject args = args();
        args.addProperty("placement_policy", "disabled");

        ReturnToPositionAction action = ReturnToPositionAction.fromArgs(args);

        assertEquals(Set.of(MaidActionResource.MOVE, MaidActionResource.HAND,
                MaidActionResource.BREAK), action.resources());
    }

    @Test
    void acceptsExplicitOperationAndRejectsUnsafeOrUnknownFields() {
        JsonObject valid = args();
        valid.addProperty("operation_id", UUID.randomUUID().toString());
        valid.addProperty("route_policy", "safe_shortest");
        valid.addProperty("max_placements", 4096);
        assertEquals(MaidActionKind.RETURN_TO_POSITION,
                ReturnToPositionAction.fromArgs(valid).kind());

        JsonObject invalidUuid = args();
        invalidUuid.addProperty("operation_id", "not-a-uuid");
        assertThrows(IllegalArgumentException.class,
                () -> ReturnToPositionAction.fromArgs(invalidUuid));

        JsonObject unknown = args();
        unknown.addProperty("follower_safe", false);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ReturnToPositionAction.fromArgs(unknown));
        assertTrue(failure.getMessage().contains("Unsupported"));
    }

    @Test
    void validatesPlacementLimitAndCoordinateBounds() {
        JsonObject placements = args();
        placements.addProperty("max_placements", 4097);
        assertThrows(IllegalArgumentException.class,
                () -> ReturnToPositionAction.fromArgs(placements));

        JsonObject coordinate = args();
        coordinate.getAsJsonObject("target").addProperty("x", 30_000_001);
        assertThrows(IllegalArgumentException.class,
                () -> ReturnToPositionAction.fromArgs(coordinate));
    }

    private static JsonObject args() {
        JsonObject target = new JsonObject();
        target.addProperty("x", 12);
        target.addProperty("y", 72);
        target.addProperty("z", -5);
        JsonObject args = new JsonObject();
        args.add("target", target);
        return args;
    }
}
