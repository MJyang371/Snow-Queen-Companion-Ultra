package com.snowempress;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class CombatEvasion {
    private static final double SPEED = 0.6;
    private static final int BURST_TICKS = 4;
    private final SnowEmpressEntity empress;
    private final MovementCoordinator movement;
    private final CombatSpace space;
    private MovementCoordinator.Lease lease;
    private Vec3 direction;
    private long nextDodge;
    private int remaining;

    CombatEvasion(SnowEmpressEntity empress, MovementCoordinator movement, CombatSpace space) {
        this.empress = empress;
        this.movement = movement;
        this.space = space;
    }

    boolean tick(Runnable interrupt) {
        if (lease != null) {
            if (movement.owns(lease) && remaining-- > 0
                    && space.step(direction.scale(SPEED)) == CombatSpace.Fit.CLEAR) {
                movement.drive(lease, direction.scale(SPEED));
                return true;
            }
            stop();
            return false;
        }
        long now = empress.level().getGameTime();
        if (now < nextDodge || !movement.canAcquire(MovementCoordinator.Owner.EMERGENCY)) {
            return false;
        }
        direction = findDirection();
        if (direction == null) {
            nextDodge = now + 2;
            return false;
        }
        interrupt.run();
        lease = movement.acquire(MovementCoordinator.Owner.EMERGENCY, now);
        if (lease == null) {
            return false;
        }
        nextDodge = now + 24;
        remaining = BURST_TICKS - 1;
        movement.drive(lease, direction.scale(SPEED));
        return true;
    }

    void stop() {
        movement.release(lease);
        lease = null;
        remaining = 0;
    }

    private Vec3 findDirection() {
        AABB body = empress.getBoundingBox();
        Projectile imminent = null;
        Vec3 impact = null;
        double nearestTime = Double.POSITIVE_INFINITY;
        for (Projectile projectile : empress.level().getEntitiesOfClass(Projectile.class, body.inflate(14),
                p -> p.getOwner() == null || empress.canDamage(p.getOwner()))) {
            Vec3 from = projectile.position();
            Vec3 velocity = projectile.getDeltaMovement();
            if (velocity.lengthSqr() < 0.04) {
                continue;
            }
            Vec3 to = from.add(velocity.scale(6));
            var hit = body.inflate(0.25).clip(from, to);
            if (hit.isEmpty() || !space.loaded(new AABB(from, hit.get()))) {
                continue;
            }
            double time = from.distanceTo(hit.get()) / velocity.length();
            if (time < nearestTime && empress.level().clip(new ClipContext(from, hit.get(),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, projectile)).getType() == HitResult.Type.MISS) {
                imminent = projectile;
                impact = to;
                nearestTime = time;
            }
        }
        if (imminent != null) {
            return choose(imminent.position(), impact, imminent.getDeltaMovement());
        }
        LivingEntity threat = empress.level().getEntitiesOfClass(Mob.class, body.inflate(3),
                mob -> mob.isAlive() && mob.getTarget() == empress && empress.canDamage(mob)
                        && empress.getSensing().hasLineOfSight(mob)
                        && (mob.swinging || empress.getLastHurtByMob() == mob
                        && empress.tickCount - empress.getLastHurtByMobTimestamp() < 15
                        || mob.distanceToSqr(empress) < Math.pow(1.4 + mob.getBbWidth(), 2)
                        && mob.getDeltaMovement().subtract(empress.getDeltaMovement())
                        .dot(empress.position().subtract(mob.position())) > 0.05))
                .stream().min(java.util.Comparator.comparingDouble(empress::distanceToSqr)).orElse(null);
        return threat == null ? null : choose(threat.position(), null, empress.position().subtract(threat.position()));
    }

    private Vec3 choose(Vec3 origin, Vec3 projectileEnd, Vec3 axis) {
        Vec3 side = new Vec3(-axis.z, 0, axis.x).normalize();
        if (side.lengthSqr() < 0.01) {
            side = new Vec3(1, 0, 0);
        }
        Vec3 away = empress.position().subtract(origin).multiply(1, 0, 1).normalize();
        for (Vec3 option : new Vec3[] {new Vec3(0, 1, 0), side, side.reverse(), away}) {
            Vec3 shift = option.scale(SPEED * BURST_TICKS);
            if (shift.lengthSqr() > 0.01 && space.step(shift) == CombatSpace.Fit.CLEAR
                    && (projectileEnd == null || empress.getBoundingBox().move(shift).inflate(0.2)
                    .clip(origin, projectileEnd).isEmpty())) {
                return option;
            }
        }
        return null;
    }
}
