package com.neko_tlm_bridge.ws.handler;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidInfo;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidWorldData;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.UUID;

/** 女仆查找工具类 — 提供 findMaidById 和 findFirstMaid 等共享的静态方法 */
public final class MaidHelper {
    private MaidHelper() {}

    public static EntityMaid findMaidById(MinecraftServer server, String maidId) {
        if (server == null || maidId == null || maidId.isEmpty()) {
            return null;
        }
        try {
            UUID uuid = UUID.fromString(maidId);
            for (ServerLevel level : server.getAllLevels()) {
                Entity entity = level.getEntity(uuid);
                if (entity instanceof EntityMaid maid) {
                    return maid;
                }
            }
        } catch (IllegalArgumentException e) {
        }
        for (EntityMaid maid : getAllMaids(server)) {
            if (maid.getStringUUID().equals(maidId)) {
                return maid;
            }
        }
        return null;
    }

    public static java.util.List<EntityMaid> getAllMaids(MinecraftServer server) {
        java.util.List<EntityMaid> maids = new java.util.ArrayList<>();
        if (server == null) {
            return maids;
        }
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof EntityMaid maid) {
                    maids.add(maid);
                }
            }
        }
        return maids;
    }

    public static EntityMaid findFirstMaid(MinecraftServer server) {
        java.util.List<EntityMaid> maids = getAllMaids(server);
        if (!maids.isEmpty()) {
            return maids.get(0);
        }
        return null;
    }

    /**
     * 解析在线玩家所拥有且当前未加载女仆的 TLM 持久化最后位置。
     * 实体加入维度后 TLM 会立即删除此记录，因此匹配结果明确表示实体未加载。
     */
    public static UnloadedMaid findUnloadedMaid(MinecraftServer server, String maidId) {
        if (server == null || maidId == null || maidId.isBlank()) {
            return null;
        }
        UUID maidUuid;
        try {
            maidUuid = UUID.fromString(maidId);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        MaidWorldData worldData = overworld == null ? null : MaidWorldData.get(overworld);
        if (worldData == null) {
            return null;
        }
        for (ServerPlayer owner : server.getPlayerList().getPlayers()) {
            List<MaidInfo> infos = worldData.getPlayerMaidInfos(owner);
            if (infos == null) {
                continue;
            }
            for (MaidInfo info : infos) {
                if (!maidUuid.equals(info.getEntityId())) {
                    continue;
                }
                try {
                    ResourceLocation dimension = ResourceLocation.parse(info.getDimension());
                    ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
                    return level == null ? null : new UnloadedMaid(info, owner, level);
                } catch (RuntimeException invalidDimension) {
                    return null;
                }
            }
        }
        return null;
    }

    public record UnloadedMaid(MaidInfo info, ServerPlayer owner, ServerLevel level) {
    }
}
