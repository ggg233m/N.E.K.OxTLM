package com.neko_tlm_bridge.tlm;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.phys.AABB;

import java.util.Optional;
import java.util.UUID;

public class NekoAttackTargetBehavior {

    public static BehaviorControl<EntityMaid> create() {
        return BehaviorBuilder.create((context) -> context.group(
                context.registered(MemoryModuleType.ATTACK_TARGET),
                context.registered(MemoryModuleType.LOOK_TARGET),
                context.registered(MemoryModuleType.WALK_TARGET)
        ).apply(context, (attackTargetAccessor, lookTargetAccessor, walkTargetAccessor)
                -> (ServerLevel level, EntityMaid maid, long gameTime) -> {
            NekoAttackTargetStore.TargetEntry entry = NekoAttackTargetStore.getCurrentTarget(maid.getUUID());
            if (entry == null) {
                return false;
            }

            if (entry.isExpired()) {
                boolean hasMore = NekoAttackTargetStore.advanceTarget(maid.getUUID());
                if (!hasMore) {
                    NekoAttackTargetStore.removeTarget(maid.getUUID());
                }
                return false;
            }

            Optional<LivingEntity> currentTarget = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET);

            if (currentTarget.isPresent()) {
                LivingEntity current = currentTarget.get();
                if (current.getUUID().equals(entry.targetEntityId)) {
                    if (current.isAlive()) {
                        return true;
                    } else {
                        boolean hasMore = NekoAttackTargetStore.advanceTarget(maid.getUUID());
                        if (!hasMore) {
                            NekoAttackTargetStore.removeTarget(maid.getUUID());
                            return false;
                        }
                        NekoAttackTargetStore.TargetEntry nextEntry = NekoAttackTargetStore.getCurrentTarget(maid.getUUID());
                        if (nextEntry == null) {
                            return false;
                        }
                        LivingEntity nextTarget = findEntityByUUID(maid, nextEntry.targetEntityId);
                        if (nextTarget == null || !nextTarget.isAlive()) {
                            NekoAttackTargetStore.advanceTarget(maid.getUUID());
                            return true;
                        }
                        attackTargetAccessor.set(nextTarget);
                        lookTargetAccessor.set(new EntityTracker(nextTarget, true));
                        return true;
                    }
                }
            }

            LivingEntity nekoTarget = findEntityByUUID(maid, entry.targetEntityId);
            if (nekoTarget == null || !nekoTarget.isAlive()) {
                boolean hasMore = NekoAttackTargetStore.advanceTarget(maid.getUUID());
                if (!hasMore) {
                    NekoAttackTargetStore.removeTarget(maid.getUUID());
                }
                return true;
            }

            attackTargetAccessor.set(nekoTarget);
            lookTargetAccessor.set(new EntityTracker(nekoTarget, true));
            return true;
        }));
    }

    private static LivingEntity findEntityByUUID(EntityMaid maid, UUID targetUUID) {
        if (maid.level() instanceof ServerLevel serverLevel) {
            var entity = serverLevel.getEntity(targetUUID);
            if (entity instanceof LivingEntity living && living.isAlive()) {
                return living;
            }
        }

        double radius = 64.0;
        AABB searchBox = new AABB(
                maid.getX() - radius, maid.getY() - radius, maid.getZ() - radius,
                maid.getX() + radius, maid.getY() + radius, maid.getZ() + radius);
        var entities = maid.level().getEntitiesOfClass(
                LivingEntity.class,
                searchBox,
                e -> e.getUUID().equals(targetUUID) && e.isAlive()
        );
        if (!entities.isEmpty()) {
            return entities.get(0);
        }
        return null;
    }
}
