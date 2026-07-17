package com.neko_tlm_bridge.tlm.agent.action;

import com.google.gson.JsonObject;
import com.neko_tlm_bridge.tlm.agent.ActionEndReason;
import com.neko_tlm_bridge.tlm.agent.MaidActionKind;
import com.neko_tlm_bridge.tlm.agent.MaidActionResource;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.List;
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
    void acceptsYOnlyButRejectsHalfSpecifiedHorizontalCoordinates() {
        JsonObject yOnlyTarget = new JsonObject();
        yOnlyTarget.addProperty("y", 96);
        JsonObject yOnly = new JsonObject();
        yOnly.add("target", yOnlyTarget);
        assertEquals(MaidActionKind.RETURN_TO_POSITION,
                ReturnToPositionAction.fromArgs(yOnly).kind());

        JsonObject halfTarget = new JsonObject();
        halfTarget.addProperty("x", 12);
        halfTarget.addProperty("y", 96);
        JsonObject half = new JsonObject();
        half.add("target", halfTarget);
        assertThrows(IllegalArgumentException.class,
                () -> ReturnToPositionAction.fromArgs(half));
    }

    @Test
    void acceptsSimpleSemanticDestinationsAndRejectsAmbiguity() {
        for (String destination : List.of("surface", "mine_entry", "player")) {
            JsonObject semantic = new JsonObject();
            semantic.addProperty("destination", destination);
            assertEquals(MaidActionKind.RETURN_TO_POSITION,
                    ReturnToPositionAction.fromArgs(semantic).kind());
        }

        JsonObject ambiguous = args();
        ambiguous.addProperty("destination", "surface");
        assertThrows(IllegalArgumentException.class,
                () -> ReturnToPositionAction.fromArgs(ambiguous));

        JsonObject unknown = new JsonObject();
        unknown.addProperty("destination", "somewhere");
        assertThrows(IllegalArgumentException.class,
                () -> ReturnToPositionAction.fromArgs(unknown));
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

    @Test
    void remembersOnlyServerRejectedPlacementCoordinates() {
        JsonObject rejected = new JsonObject();
        rejected.addProperty("placement_status", "PLACE_REJECTED");
        rejected.addProperty("placement_x", -987);
        rejected.addProperty("placement_y", -8);
        rejected.addProperty("placement_z", 334);

        assertEquals(BlockPos.ZERO.offset(-987, -8, 334),
                ReturnToPositionAction.rejectedPlacementTarget(
                        ActionEndReason.BLOCK_PROTECTED, rejected).orElseThrow());
        assertTrue(ReturnToPositionAction.rejectedPlacementTarget(
                ActionEndReason.PATH_NOT_FOUND, rejected).isEmpty());

        JsonObject materialFailure = rejected.deepCopy();
        materialFailure.addProperty("placement_status", "NO_SAFE_MATERIAL");
        assertTrue(ReturnToPositionAction.rejectedPlacementTarget(
                ActionEndReason.BLOCK_PROTECTED, materialFailure).isEmpty());
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
