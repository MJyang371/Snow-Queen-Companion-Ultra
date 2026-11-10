package com.snowempress;

import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class CombatSpace {
    enum Fit { CLEAR, BLOCKED, UNKNOWN }

    private final SnowEmpressEntity empress;

    CombatSpace(SnowEmpressEntity empress) {
        this.empress = empress;
    }

    boolean loaded(AABB box) {
        // Include the neighboring block cells consulted by block collision queries.
        AABB bounds = box.inflate(1.0);
        for (int x = Mth.floor(bounds.minX) >> 4; x <= Mth.floor(bounds.maxX) >> 4; x++) {
            for (int z = Mth.floor(bounds.minZ) >> 4; z <= Mth.floor(bounds.maxZ) >> 4; z++) {
                if (!empress.level().hasChunk(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    Fit fit(AABB box, boolean avoidLiquid) {
        var level = empress.level();
        if (box.minY < level.getMinBuildHeight() || box.maxY > level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(box)) {
            return Fit.BLOCKED;
        }
        if (!loaded(box)) {
            return Fit.UNKNOWN;
        }
        return !level.noCollision(empress, box) || avoidLiquid && level.containsAnyLiquid(box)
                ? Fit.BLOCKED : Fit.CLEAR;
    }

    Fit step(Vec3 delta) {
        if (delta.lengthSqr() > 32 * 32) {
            return Fit.UNKNOWN;
        }
        return fit(empress.getBoundingBox().expandTowards(delta), true);
    }

    boolean hasHeadroom() {
        AABB overhead = empress.getBoundingBox().expandTowards(0, 0.3, 0);
        return overhead.maxY <= empress.level().getMaxBuildHeight() && loaded(overhead)
                && !empress.level().getBlockCollisions(empress, overhead).iterator().hasNext();
    }

    boolean loadedPath(Path path) {
        if (path.getNodeCount() > 64) {
            return false;
        }
        Vec3 previous = empress.position();
        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            Vec3 next = path.getEntityPosAtNode(empress, i);
            if (!loaded(empress.getBoundingBox().move(previous.subtract(empress.position()))
                    .expandTowards(next.subtract(previous)))) {
                return false;
            }
            previous = next;
        }
        return true;
    }

    Set<BlockPos> firingPositions(LivingEntity target) {
        Set<BlockPos> candidates = new LinkedHashSet<>();
        Vec3 aim = target.getBoundingBox().getCenter();
        double angle = Math.atan2(empress.getZ() - target.getZ(), empress.getX() - target.getX());
        for (int i = 0; i < 8; i++) {
            double heading = angle + i * Math.PI / 2.0 + (i >= 4 ? Math.PI / 4.0 : 0.0);
            double radius = i < 4 ? 4.0 : 7.0;
            BlockPos block = BlockPos.containing(target.getX() + radius * Math.cos(heading),
                    target.getY() + (i < 4 ? 0.0 : 2.0), target.getZ() + radius * Math.sin(heading));
            Vec3 feet = Vec3.atBottomCenterOf(block);
            AABB box = empress.getBoundingBox().move(feet.subtract(empress.position()));
            Vec3 eye = feet.add(0, empress.getEyeHeight(), 0);
            if (fit(box, true) == Fit.CLEAR && loaded(new AABB(eye, aim))
                    && empress.level().clip(new ClipContext(eye, aim, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, empress)).getType() == HitResult.Type.MISS) {
                candidates.add(block);
            }
        }
        return candidates;
    }
}
