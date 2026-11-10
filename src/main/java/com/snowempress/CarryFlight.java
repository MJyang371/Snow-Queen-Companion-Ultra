package com.snowempress;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class CarryFlight {
    // Frame zero in carry_hold: hip (-4, 17.4, -6.2) in Blockbench units.
    private static final Vec3 SEAT = new Vec3(-4.0 / 16, 17.4 / 16 - 0.751, 6.2 / 16);
    private final SnowEmpressEntity empress;
    private final MovementCoordinator movement;
    private final CombatSpace space;
    private MovementCoordinator.Lease lease;
    private int keys;
    private long inputTime;
    private long rescueAfter;
    private ServerPlayer observedOwner;
    private Vec3 previousOwnerPosition;
    private Vec3 flightVelocity = Vec3.ZERO;

    CarryFlight(SnowEmpressEntity empress, MovementCoordinator movement) {
        this.empress = empress;
        this.movement = movement;
        space = new CombatSpace(empress);
    }

    Vec3 seat(float yaw) {
        return empress.position().add(SEAT.yRot((float) -Math.toRadians(yaw)));
    }

    void acceptInput(ServerPlayer player, int input) {
        if (empress.getFirstPassenger() == player && empress.isOwnedBy(player)) {
            keys = input;
            inputTime = empress.level().getGameTime();
        }
    }

    boolean mount(ServerPlayer player) {
        if (!empress.isAlive() || !empress.isOwnedBy(player) || !player.isAlive()
                || player.isSpectator() || player.isSleeping() || player.isPassenger()
                || empress.isVehicle() || !clearRiderPath(player, seat(empress.getYRot()))) {
            return false;
        }
        if (!player.startRiding(empress, true)) {
            return false;
        }
        player.fallDistance = 0;
        player.setDeltaMovement(Vec3.ZERO);
        flightVelocity = Vec3.ZERO;
        keys = 0;
        takeControl();
        movement.hold(lease);
        return true;
    }

    boolean tick(ServerPlayer owner) {
        Vec3 fallMotion = sampleOwnerMotion(owner);
        if (empress.getFirstPassenger() == owner) {
            if (owner.isSleeping()) {
                owner.stopRiding();
                return false;
            }
            takeControl();
            fly(owner);
            return true;
        }
        if (empress.isVehicle()) {
            empress.ejectPassengers();
        }
        if (canRescue(owner, fallMotion)) {
            if (owner.distanceToSqr(empress) <= 9 && mount(owner)) {
                return true;
            }
            Vec3 aim = owner.position().add(fallMotion.scale(2)).subtract(seat(empress.getYRot()));
            Vec3 step = limit(aim, 3.2);
            if (space.step(step) != CombatSpace.Fit.CLEAR) {
                Vec3 lateral = step.multiply(1, 0, 1);
                Vec3 vertical = step.multiply(0, 1, 0);
                step = lateral.lengthSqr() > 0.01 && space.step(lateral) == CombatSpace.Fit.CLEAR
                        ? lateral : vertical;
            }
            if (step.lengthSqr() > 0.01 && space.step(step) == CombatSpace.Fit.CLEAR) {
                takeControl();
                movement.drive(lease, step);
                return true;
            }
        }
        stop();
        return false;
    }

    private Vec3 sampleOwnerMotion(ServerPlayer owner) {
        Vec3 position = owner.position();
        Vec3 reported = owner.getDeltaMovement();
        Vec3 measured = observedOwner == owner && previousOwnerPosition != null
                ? position.subtract(previousOwnerPosition) : Vec3.ZERO;
        observedOwner = owner;
        previousOwnerPosition = position;
        // A remote player's velocity can lag behind the server's accepted move packets.
        return measured.lengthSqr() < 25 && measured.y < reported.y ? measured : reported;
    }

    private boolean canRescue(ServerPlayer owner, Vec3 fallMotion) {
        return empress.level().getGameTime() >= rescueAfter && !owner.isPassenger()
                && !owner.isSleeping() && !owner.onGround() && !owner.isInWater()
                && !owner.isFallFlying() && !owner.getAbilities().flying
                && owner.fallDistance >= 4.0F && fallMotion.y < -0.45
                && owner.distanceToSqr(empress) <= 48 * 48;
    }

    private void takeControl() {
        if (!movement.owns(lease)) {
            empress.stopCombat(SnowEmpressCombat.InterruptReason.CARRY_FLIGHT);
            lease = movement.acquire(MovementCoordinator.Owner.CARRY, 0);
        }
    }

    private void fly(ServerPlayer owner) {
        owner.fallDistance = 0;
        float yaw = owner.getYRot();
        float turn = Mth.wrapDegrees(yaw - empress.getYRot());
        float heading = empress.getYRot() + (Math.abs(turn) < 0.5F ? turn : turn * 0.38F);
        if (turn != 0 && clearRiderPath(owner, seat(heading))) {
            empress.setYRot(heading);
            empress.setYBodyRot(heading);
            empress.setYHeadRot(heading);
        }
        int input = empress.level().getGameTime() - inputTime <= 15 ? keys : 0;
        double forward = axis(input, CarryFlightInput.FORWARD, CarryFlightInput.BACK);
        double strafe = axis(input, CarryFlightInput.LEFT, CarryFlightInput.RIGHT);
        double rise = axis(input, CarryFlightInput.UP, CarryFlightInput.DOWN);
        Vec3 targetHorizontal = new Vec3(strafe, 0, forward).normalize().scale(0.58)
                .yRot((float) -Math.toRadians(yaw));
        Vec3 horizontal = flightVelocity.multiply(1, 0, 1).lerp(targetHorizontal,
                targetHorizontal.lengthSqr() == 0 ? 0.42 : 0.32);
        double vertical = Mth.lerp(rise == 0 ? 0.48 : 0.38, flightVelocity.y, rise * 0.38);
        if (horizontal.lengthSqr() < 0.000064) {
            horizontal = Vec3.ZERO;
        }
        Vec3 velocity = new Vec3(0, Math.abs(vertical) < 0.008 ? 0 : vertical, 0);
        if (!clearStep(owner, velocity)) {
            velocity = Vec3.ZERO;
        }
        if (clearStep(owner, velocity.add(horizontal))) {
            velocity = velocity.add(horizontal);
        } else {
            Vec3 x = velocity.add(horizontal.x, 0, 0);
            if (clearStep(owner, x)) {
                velocity = x;
            }
            Vec3 z = velocity.add(0, 0, horizontal.z);
            if (clearStep(owner, z)) {
                velocity = z;
            }
        }
        flightVelocity = velocity;
        movement.drive(lease, flightVelocity);
    }

    private boolean clearStep(ServerPlayer owner, Vec3 step) {
        return space.step(step) == CombatSpace.Fit.CLEAR
                && clearRiderPath(owner, seat(empress.getYRot()).add(step));
    }

    private boolean clearRiderPath(ServerPlayer owner, Vec3 destination) {
        AABB sweep = owner.getBoundingBox().expandTowards(destination.subtract(owner.position()));
        var level = empress.level();
        return sweep.minY >= level.getMinBuildHeight() && sweep.maxY <= level.getMaxBuildHeight()
                && level.getWorldBorder().isWithinBounds(sweep) && space.loaded(sweep)
                && !level.getBlockCollisions(owner, sweep).iterator().hasNext();
    }

    private static int axis(int input, int positive, int negative) {
        return ((input & positive) == 0 ? 0 : 1) - ((input & negative) == 0 ? 0 : 1);
    }

    private static Vec3 limit(Vec3 vector, double speed) {
        return vector.lengthSqr() > speed * speed ? vector.normalize().scale(speed) : vector;
    }

    void dismounted() {
        rescueAfter = empress.level().getGameTime() + 30;
        stop();
    }

    void stop() {
        movement.release(lease);
        lease = null;
        keys = 0;
        flightVelocity = Vec3.ZERO;
    }

    void resetOwner() {
        stop();
        observedOwner = null;
        previousOwnerPosition = null;
    }
}
