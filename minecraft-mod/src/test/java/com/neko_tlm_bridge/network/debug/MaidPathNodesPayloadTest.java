package com.neko_tlm_bridge.network.debug;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MaidPathNodesPayloadTest {
    @Test
    void roundTripsProgrammaticPathWithoutVanillaDebugData() {
        Path path = new Path(List.of(
                new Node(1, 64, 1),
                new Node(2, 64, 1),
                new Node(2, 63, 1)
        ), new BlockPos(2, 63, 1), true);
        path.advance();
        MaidPathNodesPayload expected = MaidPathNodesPayload.fromPath(42, path, 0.5F);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            MaidPathNodesPayload.STREAM_CODEC.encode(buffer, expected);
            MaidPathNodesPayload actual = MaidPathNodesPayload.STREAM_CODEC.decode(buffer);

            assertEquals(expected, actual);
            assertEquals(0, buffer.readableBytes(), "decoder must consume the complete payload");
            assertNull(path.debugData(),
                    "regression fixture must remain a programmatically constructed path without DebugData");
        } finally {
            buffer.release();
        }
    }
}
