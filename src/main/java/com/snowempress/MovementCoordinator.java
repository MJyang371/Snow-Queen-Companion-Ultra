package com.snowempress;

import java.util.function.Supplier;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

final class MovementCoordinator {
    enum Owner { FOLLOW, POSITIONING, ABILITY, EMERGENCY, CARRY, LIFECYCLE }
    enum Intent { NAVIGATE, DIRECT_VELOCITY, HOLD, TELEPORT }

    record Lease(Owner owner, long actionId) {}
    record View(Owner owner, Intent intent, long actionId) {}

    private final SnowEmpressEntity empress;
    private final CombatSpace space;
    private Lease current;
    private Intent intent;
    private Vec3 commandedVelocity = Vec3.ZERO;

    MovementCoordinator(SnowEmpressEntity empress) {
        this.empress = empress;
        this.space = new CombatSpace(empress);
    }

    Lease acquire(Owner owner, long actionId) {
        if (!canAcquire(owner)) {
            return null;
        }
        if (current != null) {
            release(current);
        }
        current = new Lease(owner, actionId);
        return current;
    }

    boolean canAcquire(Owner owner) {
        return current == null || owner.ordinal() > current.owner().ordinal();
    }

    boolean owns(Lease lease) {
        return lease != null && lease == current;
    }

    View inspect() {
        return current == null ? null : new View(current.owner(), intent, current.actionId());
    }

    void release(Lease lease) {
        if (!owns(lease)) {
            return;
        }
        stopNavigation(lease);
        if (intent == Intent.DIRECT_VELOCITY && commandedVelocity.lengthSqr() > 1.0E-8) {
            // Preserve velocity orthogonal to the command and impulses opposing it.
            Vec3 velocity = empress.getDeltaMovement();
            Vec3 axis = commandedVelocity.normalize();
            double controlled = Math.max(0, Math.min(commandedVelocity.length(), velocity.dot(axis)));
            empress.setDeltaMovement(velocity.subtract(axis.scale(controlled)));
        }
        current = null;
        intent = null;
        commandedVelocity = Vec3.ZERO;
    }

    void follow(Vec3 ownerFeet) {
        Vec3 destination = ownerFeet.add(0, 1, 0);
        if (space.fit(empress.getBoundingBox().move(destination.subtract(empress.position())), false)
                != CombatSpace.Fit.CLEAR) {
            destination = ownerFeet;
        }
        Lease lease = current != null && current.owner() == Owner.FOLLOW ? current : acquire(Owner.FOLLOW, 0);
        navigate(lease, destination, 1.0);
    }

    void stopFollowing() {
        if (current != null && current.owner() == Owner.FOLLOW) {
            release(current);
        }
    }

    boolean navigate(Lease lease, Vec3 destination, double speed) {
        if (!owns(lease)) {
            return false;
        }
        intent = Intent.NAVIGATE;
        return empress.getNavigation().moveTo(destination.x, destination.y, destination.z, speed);
    }

    boolean navigate(Lease lease, Path path, double speed) {
        if (!owns(lease)) {
            return false;
        }
        intent = Intent.NAVIGATE;
        return empress.getNavigation().moveTo(path, speed);
    }

    void stopNavigation(Lease lease) {
        if (owns(lease)) {
            empress.getNavigation().stop();
            ((FlightControl) empress.getMoveControl()).stop();
        }
    }

    void drive(Lease lease, Vec3 velocity) {
        if (owns(lease)) {
            intent = Intent.DIRECT_VELOCITY;
            commandedVelocity = velocity;
            empress.setDeltaMovement(velocity);
        }
    }

    void hold(Lease lease) {
        if (owns(lease)) {
            intent = Intent.HOLD;
            empress.setDeltaMovement(Vec3.ZERO);
        }
    }

    void maintainSpinHold() {
        if (current != null && current.owner() == Owner.ABILITY && intent == Intent.HOLD) {
            hold(current);
        }
    }

    void teleport(Lease lease, Runnable transfer) {
        if (owns(lease)) {
            intent = Intent.TELEPORT;
            transfer.run();
        }
    }

    <T> T changeDimension(Lease lease, Supplier<T> transfer) {
        if (!owns(lease)) {
            return null;
        }
        intent = Intent.TELEPORT;
        return transfer.get();
    }

    static final class FlightControl extends FlyingMoveControl {
        private final SnowEmpressEntity empress;
        private final CombatSpace space;

        FlightControl(SnowEmpressEntity empress) {
            super(empress, 20, true);
            this.empress = empress;
            this.space = new CombatSpace(empress);
        }

        @Override
        public void tick() {
            boolean rising = operation == Operation.MOVE_TO && wantedY > empress.getY() + 0.02;
            super.tick();
            if (rising && !space.hasHeadroom()) {
                empress.setYya(0);
                Vec3 velocity = empress.getDeltaMovement();
                if (velocity.y > 0) {
                    empress.setDeltaMovement(new Vec3(velocity.x, 0, velocity.z));
                }
            }
        }

        void stop() {
            operation = Operation.WAIT;
            empress.setZza(0.0F);
            empress.setYya(0.0F);
        }
    }
}
