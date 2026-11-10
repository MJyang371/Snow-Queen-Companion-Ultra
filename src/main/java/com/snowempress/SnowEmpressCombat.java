package com.snowempress;

import java.util.ArrayList;
import java.util.List;
import java.util.EnumMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import twilightforest.init.TFParticleType;
import twilightforest.init.TFSounds;

final class SnowEmpressCombat {
    private static final TagKey<EntityType<?>> MONSTER = TagKey.create(Registries.ENTITY_TYPE,
            new ResourceLocation(SnowEmpress.MOD_ID, "monster"));
    private static final double TARGET_RANGE = 24.0;
    private static final int SLAM_WARNING_TICKS = 14;
    private static final int SLAM_DIVE_TICKS = 24;
    private static final int SLAM_COMBO_HITS = 3;
    private static final double[] SLAM_START_HEIGHTS = {2.0, 1.2, 0.55};
    private static final double BREATH_RANGE = 10.0;
    private static final double BREATH_MIN_DOT = Math.cos(Math.toRadians(25.0));
    private static final int DEFAULT_SPIN_TICKS = 100;
    private static final int GUNSHIP_ASCENT_TIMEOUT = 45;
    private static final int GUNSHIP_SHOT_INTERVAL = 5;
    private static final double GUNSHIP_RADIUS = 5.5;
    private static final int RETREAT_SHOT_INTERVAL = 10;
    enum AbilityId {
        ICE_SPIKE, SLAM, BREATH, SUMMON_CRYSTALS, SPIN_BREATH, GUNSHIP_BOMBARDMENT, SLAM_SPIN, RETREAT
    }

    enum AbilityPhase {
        PREPARING, APPROACHING, FIRING, SUMMONING, WARNING, TELEPORTING, DIVING, BREATHING,
        SPINNING, ASCENDING, ORBITING, RETREATING
    }

    enum Status { RUNNING, SUCCESS, BLOCKED, INVALID_TARGET, TIMEOUT, INTERRUPTED }

    enum StartCheck { READY, INVALID_TARGET, UNAVAILABLE, BUSY, COOLDOWN, MOVEMENT_BUSY, LOCAL_RETRY_DELAY }

    enum InterruptReason {
        TARGET_CHANGED, TARGET_INVALID, OWNER_UNAVAILABLE, RECALL, CROSS_DIMENSION, DEATH, REMOVED,
        TACTICAL, MOVEMENT_PREEMPTED, OWNER_SLEEPING, CARRY_FLIGHT
    }

    record AbilityRequest(AbilityId ability, LivingEntity target) {}

    record AbilityOutcome(long actionId, AbilityId ability, AbilityPhase phase, Status status, String reason,
                          boolean performed, int elapsedTicks, boolean movementObstructed,
                          boolean ascentTimedOut, boolean navigationRejected) {}

    record AbilityView(AbilityOutcome current, AbilityOutcome last, int cooldown, MovementCoordinator.View movement) {}

    private record Step(Status status, String reason) {}

    private final SnowEmpressEntity empress;
    private final MovementCoordinator movement;
    private final CombatPolicy policy;
    private final CombatSpace space;
    private final CombatEvasion evasion;
    private LivingEntity protectedTarget;
    private long protectUntil;
    private long nextRescue;
    private int ownerHurtStamp = Integer.MIN_VALUE;
    private final EnumMap<AbilityId, FailureSite> failures = new EnumMap<>(AbilityId.class);
    private Vec3 approachTarget;
    private int nextPathAttempt;
    private int pathNode = -1;
    private double waypointDistance;
    private int stalledTicks;
    private int obstructedTicks;
    private Vec3 flightOrigin;
    private Vec3 flightCommand;

    private record FailureSite(UUID target, Vec3 origin, Vec3 targetPosition, long retryAt) {}
    private record DivePlan(Vec3 destination, boolean unknown) {}
    private long actionId;
    private AbilityRequest active;
    private AbilityPhase phase;
    private MovementCoordinator.Lease abilityMovement;
    private AbilityOutcome lastOutcome;
    private int runningTicks;
    private boolean performed;
    private boolean movementObstructed;
    private boolean ascentTimedOut;
    private boolean navigationRejected;
    private boolean advancing;
    private int elapsed;
    private int cooldown;
    private int slamWarning; // Zero before starting, positive during warning, negative during the dive.
    private int slamDive;
    private int completedSlams;
    private boolean slamConnected;
    private Vec3 slamHeading = Vec3.ZERO;
    private boolean breathing;
    private int spinBreathTicks = DEFAULT_SPIN_TICKS;
    private int slamSpinTicks = DEFAULT_SPIN_TICKS;
    private int activeSpinTicks;
    private GunshipSettings gunshipSettings = GunshipSettings.DEFAULT;
    private GunshipSettings activeGunship;
    private Vec3 gunshipAim;
    private int gunshipFireTicks;
    private RetreatSettings retreatSettings = RetreatSettings.DEFAULT;
    private RetreatSettings activeRetreat;
    private Vec3 retreatPreviousPosition;
    private double retreatDistance;

    SnowEmpressCombat(SnowEmpressEntity empress, MovementCoordinator movement, CombatPolicy policy) {
        this.empress = empress;
        this.movement = movement;
        this.policy = policy;
        this.space = new CombatSpace(empress);
        this.evasion = new CombatEvasion(empress, movement, space);
    }

    String nextSkillKey() {
        AbilityId skill = active != null ? active.ability() : policy.preview();
        return skill == null ? "hud.snowempress.no_target"
                : "skill.snowempress." + skill.name().toLowerCase(java.util.Locale.ROOT);
    }

    void setGunshipSettings(GunshipSettings settings) {
        gunshipSettings = settings;
    }

    void setRetreatSettings(RetreatSettings settings) {
        retreatSettings = settings;
    }

    void setSpinDurationTicks(int ticks, boolean afterSlam) {
        if (ticks <= 0) {
            throw new IllegalArgumentException("Spinning breath duration must be positive");
        }
        if (afterSlam) {
            slamSpinTicks = ticks;
        } else {
            spinBreathTicks = ticks;
        }
    }

    void clearTarget(InterruptReason reason) {
        evasion.stop();
        if (active != null) {
            cancel(actionId, reason);
        }
        if (empress.getTarget() != null) {
            empress.setTarget(null);
        }
    }

    void reconnectOwner(Player owner) {
        clearTarget(InterruptReason.OWNER_UNAVAILABLE);
        protectedTarget = null;
        protectUntil = 0;
        ownerHurtStamp = owner.getLastHurtByMobTimestamp();
    }

    AbilityView inspect() {
        return new AbilityView(active == null ? null : outcome(Status.RUNNING, null),
                lastOutcome, cooldown, movement.inspect());
    }

    StartCheck canStart(AbilityRequest request) {
        if (!empress.isAlive() || empress.isRemoved() || empress.level().isClientSide()) {
            return StartCheck.UNAVAILABLE;
        }
        if (!valid(request.target())) {
            return StartCheck.INVALID_TARGET;
        }
        if (active != null || advancing) {
            return StartCheck.BUSY;
        }
        if (cooldown > 0) {
            return StartCheck.COOLDOWN;
        }
        if (retryDelayed(request)) {
            return StartCheck.LOCAL_RETRY_DELAY;
        }
        return movement.canAcquire(MovementCoordinator.Owner.ABILITY) ? StartCheck.READY : StartCheck.MOVEMENT_BUSY;
    }

    boolean start(AbilityRequest request) {
        if (canStart(request) != StartCheck.READY) {
            return false;
        }
        MovementCoordinator.Lease lease = movement.acquire(MovementCoordinator.Owner.ABILITY, actionId + 1);
        if (lease == null) {
            return false;
        }
        actionId++;
        active = request;
        abilityMovement = lease;
        phase = AbilityPhase.PREPARING;
        if (empress.getTarget() != request.target()) {
            empress.setTarget(request.target());
        }
        return active != null;
    }

    boolean cancel(long expectedActionId, InterruptReason reason) {
        if (active == null || actionId != expectedActionId) {
            return false;
        }
        finish(reason == InterruptReason.TARGET_INVALID ? Status.INVALID_TARGET : Status.INTERRUPTED,
                reason.name(), false);
        if (reason == InterruptReason.TACTICAL || reason == InterruptReason.MOVEMENT_PREEMPTED) {
            cooldown = 20;
        }
        return true;
    }

    boolean tick(Player owner) {
        protectOwner(owner);
        boolean evading = evasion.tick(() -> {
            if (!breathing) {
                cancel(actionId, InterruptReason.TACTICAL);
            }
        });
        if (evading) {
            if (breathing && active != null) {
                LivingEntity target = active.target();
                if (valid(target)) {
                    tickAbility(owner, target, true);
                } else {
                    cancel(actionId, InterruptReason.TARGET_INVALID);
                }
            }
            return true;
        }
        if (breathing && !movement.owns(abilityMovement)) {
            abilityMovement = movement.acquire(MovementCoordinator.Owner.ABILITY, actionId);
        }
        // These phases historically keep running through target changes.
        if (active != null && (activeRetreat != null || activeGunship != null || phase == AbilityPhase.SPINNING)) {
            tickAbility(owner, empress.getTarget());
            return true;
        }
        LivingEntity previous = empress.getTarget();
        LivingEntity target = previous;
        LivingEntity assisted = owner.getLastHurtMob();
        if (empress.level().getGameTime() < protectUntil && valid(protectedTarget)) {
            target = protectedTarget;
        } else if (owner.tickCount - owner.getLastHurtMobTimestamp() < 200 && valid(assisted)) {
            target = assisted;
        } else if (active == null && policy instanceof UtilityCombatPolicy && empress.tickCount % 10 == 0) {
            LivingEntity candidate = findMonster();
            if (!valid(target) || candidate != null && targetScore(candidate) > targetScore(target) + 8) {
                target = candidate;
            }
        } else if (!valid(target)) {
            target = empress.tickCount % 10 == 0 ? findMonster() : null;
        }
        if (active != null && target != active.target()) {
            cancel(actionId, valid(active.target()) ? InterruptReason.TARGET_CHANGED : InterruptReason.TARGET_INVALID);
        }
        if (target != previous) {
            movement.stopFollowing();
            empress.setTarget(target);
        }
        if (target == null) {
            return false;
        }
        empress.getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (cooldown > 0) {
            cooldown--;
            return true;
        }
        if (active == null) {
            AbilityRequest request = policy.choose(target);
            if (request != null && canStart(request) == StartCheck.LOCAL_RETRY_DELAY) {
                policy.onDeferred(request);
                return true;
            }
            if (request == null || !start(request)) {
                return true;
            }
        }
        tickAbility(owner, active.target());
        return true;
    }

    private boolean retryDelayed(AbilityRequest request) {
        FailureSite failure = failures.get(request.ability());
        return failure != null && failure.retryAt() > empress.level().getGameTime()
                && failure.target().equals(request.target().getUUID())
                && failure.origin().distanceToSqr(empress.position()) < 16
                && failure.targetPosition().distanceToSqr(request.target().position()) < 16;
    }

    boolean spaceAvailable(AbilityId ability, LivingEntity target) {
        if (ability == AbilityId.SLAM || ability == AbilityId.SLAM_SPIN) {
            return findDive(target).destination() != null;
        }
        if (ability == AbilityId.GUNSHIP_BOMBARDMENT) {
            double height = Math.min(target.getBoundingBox().getCenter().y + gunshipSettings.ascentHeight(),
                    empress.level().getMaxBuildHeight() - empress.getBbHeight());
            return space.step(new Vec3(0, Math.max(0, height - empress.getY()), 0)) == CombatSpace.Fit.CLEAR;
        }
        return true;
    }

    private void tickAbility(Player owner, LivingEntity target) {
        tickAbility(owner, target, false);
    }

    private void tickAbility(Player owner, LivingEntity target, boolean evading) {
        if (!evading && !movement.owns(abilityMovement)) {
            cancel(actionId, InterruptReason.MOVEMENT_PREEMPTED);
            return;
        }
        advancing = true;
        runningTicks++;
        elapsed++;
        try {
            Step result = advance(owner, target);
            if (active != null && result != null) {
                finish(result.status(), result.reason(), true);
            }
        } catch (RuntimeException exception) {
            if (active != null) {
                finish(Status.INTERRUPTED, "EXECUTION_ERROR", false);
            }
            SnowEmpress.LOGGER.error("Snow Empress {} ability action {} failed", empress.getUUID(), actionId, exception);
            throw exception;
        } finally {
            advancing = false;
        }
    }

    // A null step result means the current phase continues on the next tick.
    private Step advance(Player owner, LivingEntity target) {
        if (activeRetreat != null) {
            return tickRetreat(target);
        }
        if (activeGunship != null) {
            return tickGunship(owner);
        }
        if (phase == AbilityPhase.SPINNING) {
            if (elapsed > activeSpinTicks) {
                return completed("SPIN_FINISHED");
            }
            if (elapsed % 5 == 1 && !hasSpinEnemy()) {
                return completed("SPIN_NO_ENEMIES");
            }
            spinBreathTick();
            return null;
        }
        return switch (active.ability()) {
            case ICE_SPIKE -> shoot(target);
            case SLAM -> slam(target, false);
            case BREATH -> breathe(target);
            case SUMMON_CRYSTALS -> {
                phase = AbilityPhase.SUMMONING;
                stopNavigation();
                String result = empress.summonIceCrystals(target);
                performed = result != null;
                yield performed ? completed(result) : new Step(Status.BLOCKED, "NO_CRYSTAL_SPAWNED");
            }
            case SPIN_BREATH -> startSpinBreath(target);
            case SLAM_SPIN -> slam(target, true);
            case RETREAT -> {
                activeRetreat = retreatSettings;
                retreatPreviousPosition = empress.position();
                retreatDistance = 0.0;
                elapsed = 0;
                phase = AbilityPhase.RETREATING;
                stopNavigation();
                empress.setAimedFlight(true);
                yield tickRetreat(target);
            }
            case GUNSHIP_BOMBARDMENT -> {
                activeGunship = gunshipSettings;
                gunshipAim = target.getBoundingBox().getCenter();
                gunshipFireTicks = -1;
                elapsed = 0;
                phase = AbilityPhase.ASCENDING;
                stopNavigation();
                empress.setAimedFlight(true);
                yield null;
            }
        };
    }

    private AbilityOutcome outcome(Status status, String reason) {
        return new AbilityOutcome(actionId, active.ability(), phase, status, reason, performed,
                runningTicks, movementObstructed, ascentTimedOut, navigationRejected);
    }

    private void finish(Status status, String reason, boolean advancePolicy) {
        lastOutcome = outcome(status, reason);
        if (status == Status.BLOCKED || status == Status.TIMEOUT) {
            LivingEntity target = active.target();
            failures.put(active.ability(), new FailureSite(target.getUUID(), empress.position(), target.position(),
                    empress.level().getGameTime() + (reason.contains("UNLOADED") ? 30 : 240)));
        } else if (status == Status.SUCCESS) {
            failures.remove(active.ability());
        }
        SnowEmpress.LOGGER.debug("Snow Empress {} ability {}", empress.getUUID(), lastOutcome);
        empress.stopSpin();
        empress.setAimedFlight(false);
        movement.release(abilityMovement);
        abilityMovement = null;
        active = null;
        phase = null;
        activeGunship = null;
        gunshipAim = null;
        gunshipFireTicks = 0;
        activeRetreat = null;
        retreatPreviousPosition = null;
        retreatDistance = 0;
        activeSpinTicks = 0;
        runningTicks = 0;
        approachTarget = null;
        nextPathAttempt = 0;
        pathNode = -1;
        waypointDistance = Double.POSITIVE_INFINITY;
        stalledTicks = 0;
        obstructedTicks = 0;
        flightOrigin = null;
        flightCommand = null;
        elapsed = 0;
        slamWarning = 0;
        slamDive = 0;
        completedSlams = 0;
        slamConnected = false;
        breathing = false;
        performed = false;
        movementObstructed = false;
        ascentTimedOut = false;
        navigationRejected = false;
        if (advancePolicy) {
            cooldown = 20;
            policy.onFinished(lastOutcome);
        }
    }

    private static Step completed(String reason) {
        return new Step(Status.SUCCESS, reason);
    }

    private void stopNavigation() {
        movement.stopNavigation(abilityMovement);
    }

    private void drive(Vec3 velocity) {
        movement.drive(abilityMovement, velocity);
    }

    private Step tickRetreat(LivingEntity target) {
        if (elapsed >= activeRetreat.durationTicks()) {
            return completed("RETREAT_DURATION_REACHED");
        }
        if (target == null || !target.isAlive() || target.level() != empress.level()
                || target.isSpectator() || !empress.canDamage(target)) {
            return new Step(Status.INVALID_TARGET, "RETREAT_TARGET_LOST");
        }
        if (flightStalled()) {
            return new Step(Status.BLOCKED, "RETREAT_NO_PROGRESS");
        }
        Vec3 current = empress.position();
        retreatDistance += current.subtract(retreatPreviousPosition).horizontalDistance();
        retreatPreviousPosition = current;
        double remaining = activeRetreat.maxDistance() - retreatDistance;
        if (remaining <= 0.01) {
            return completed("RETREAT_DISTANCE_REACHED");
        }

        Vec3 aimPoint = target.getBoundingBox().getCenter();
        faceAimPoint(aimPoint);
        Vec3 away = current.subtract(target.position()).multiply(1, 0, 1).normalize();
        if (away.lengthSqr() < 1.0E-6) {
            away = Vec3.directionFromRotation(0.0F, empress.getYRot()).reverse();
        }
        Vec3 step = away.scale(Math.min(activeRetreat.flightSpeed(), remaining));
        if (!tryFlightStep(step) && !tryFlightStep(step.yRot((float) (Math.PI / 4)))
                && !tryFlightStep(step.yRot((float) (-Math.PI / 4)))) {
            movementObstructed = true;
            return new Step(Status.BLOCKED, "RETREAT_NO_CLEAR_STEP");
        }
        if (elapsed % RETREAT_SHOT_INTERVAL == 0
                && clipBreath((ServerLevel) empress.level(), empress.getEyePosition(), aimPoint)
                        .getType() == HitResult.Type.MISS) {
            fireIceSpike(aimPoint);
        }
        return null;
    }

    private boolean tryFlightStep(Vec3 step) {
        if (space.step(step) != CombatSpace.Fit.CLEAR) {
            return false;
        }
        commandFlight(step);
        performed = true;
        return true;
    }

    private Step tickGunship(Player owner) {
        LivingEntity target = empress.getTarget();
        if (!valid(target)) {
            target = null;
            if (elapsed % GUNSHIP_SHOT_INTERVAL == 1) {
                LivingEntity assisted = owner.getLastHurtMob();
                target = owner.tickCount - owner.getLastHurtMobTimestamp() < 200 && valid(assisted)
                        ? assisted : findMonster();
            }
            if (empress.getTarget() != target) {
                empress.setTarget(target);
            }
        }
        if (active == null) {
            return null;
        }
        if (target != null) {
            gunshipAim = target.getBoundingBox().getCenter();
        }

        if (flightStalled()) {
            return new Step(Status.BLOCKED, "GUNSHIP_NO_PROGRESS");
        }
        Vec3 aim = gunshipAim.subtract(empress.getEyePosition());
        faceAimPoint(gunshipAim);

        double speed = activeGunship.flightSpeed();
        double height = Math.min(gunshipAim.y + activeGunship.ascentHeight(),
                empress.level().getMaxBuildHeight() - empress.getBbHeight());
        if (gunshipFireTicks < 0 && empress.getY() < height - 0.35 && elapsed < GUNSHIP_ASCENT_TIMEOUT) {
            if (elapsed == 1) {
                var fit = space.step(new Vec3(0, height - empress.getY(), 0));
                if (fit != CombatSpace.Fit.CLEAR) {
                    movementObstructed = true;
                    return new Step(Status.BLOCKED, fit == CombatSpace.Fit.UNKNOWN
                            ? "GUNSHIP_ASCENT_UNLOADED_OR_OUT_OF_BUDGET" : "GUNSHIP_ASCENT_NO_CLEARANCE");
                }
            }
            return moveGunship(new Vec3(0.0, Math.min(speed, height - empress.getY()), 0.0));
        }
        if (gunshipFireTicks < 0) {
            ascentTimedOut = empress.getY() < height - 0.35;
            if (ascentTimedOut) {
                return new Step(Status.TIMEOUT, "GUNSHIP_ASCENT_TIMEOUT");
            }
            phase = AbilityPhase.ORBITING;
            gunshipFireTicks = 0;
        }

        Vec3 radial = empress.position().subtract(gunshipAim).multiply(1, 0, 1).normalize();
        if (radial.lengthSqr() < 1.0E-6) {
            radial = new Vec3(1, 0, 0);
        }
        Vec3 orbit = gunshipAim.add(radial.yRot((float) (speed / GUNSHIP_RADIUS)).scale(GUNSHIP_RADIUS));
        Vec3 step = new Vec3(orbit.x, height, orbit.z).subtract(empress.position());
        Step obstruction = moveGunship(step.lengthSqr() > speed * speed ? step.normalize().scale(speed) : step);
        if (obstruction != null) {
            return obstruction;
        }
        if (gunshipFireTicks % GUNSHIP_SHOT_INTERVAL == 0 && aim.lengthSqr() > 1.0E-6) {
            fireIceSpike(gunshipAim);
        }
        return activeGunship != null && ++gunshipFireTicks >= activeGunship.durationTicks()
                ? completed("GUNSHIP_DURATION_REACHED") : null;
    }

    private void faceAimPoint(Vec3 point) {
        Vec3 aim = point.subtract(empress.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-aim.x, aim.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-aim.y, aim.horizontalDistance()));
        empress.setYRot(yaw);
        empress.setYBodyRot(yaw);
        empress.setYHeadRot(yaw);
        empress.setXRot(pitch);
        empress.getLookControl().setLookAt(point.x, point.y, point.z, 180.0F, 180.0F);
    }

    private void commandFlight(Vec3 step) {
        flightOrigin = empress.position();
        flightCommand = step;
        drive(step);
    }

    private boolean flightStalled() {
        if (flightCommand == null || flightCommand.lengthSqr() < 1.0E-8) {
            return false;
        }
        double progress = empress.position().subtract(flightOrigin).dot(flightCommand.normalize());
        stalledTicks = progress < Math.min(0.04, flightCommand.length() * 0.2) ? stalledTicks + 1 : 0;
        if (stalledTicks >= 8) {
            movementObstructed = true;
            return true;
        }
        return false;
    }

    private Step moveGunship(Vec3 step) {
        var fit = space.step(step);
        if (fit == CombatSpace.Fit.CLEAR) {
            obstructedTicks = 0;
            commandFlight(step);
            performed |= step.lengthSqr() > 1.0E-8;
            return null;
        }
        movementObstructed = true;
        if (++obstructedTicks >= 5 || phase == AbilityPhase.ASCENDING && !space.hasHeadroom()) {
            return new Step(Status.BLOCKED, fit == CombatSpace.Fit.UNKNOWN
                    ? "GUNSHIP_ROUTE_UNLOADED_OR_OUT_OF_BUDGET" : "GUNSHIP_ROUTE_BLOCKED");
        }
        commandFlight(Vec3.ZERO);
        return null;
    }

    private Step startSpinBreath(LivingEntity target) {
        if (empress.distanceToSqr(target) > BREATH_RANGE * BREATH_RANGE
                || !empress.getSensing().hasLineOfSight(target)) {
            return approach(target, 60);
        }
        beginSpinBreath(target, spinBreathTicks);
        return null;
    }

    private void beginSpinBreath(LivingEntity target, int durationTicks) {
        Vec3 aim = target.position().subtract(empress.position());
        float yaw = aim.horizontalDistanceSqr() < 1.0E-6
                ? empress.getYRot() : (float) Math.toDegrees(Math.atan2(-aim.x, aim.z));
        phase = AbilityPhase.SPINNING;
        performed = true;
        stopNavigation();
        movement.hold(abilityMovement);
        empress.startSpin(yaw);
        empress.playSound(TFSounds.SNOW_QUEEN_ATTACK.get(), 1.0F, 0.8F);
        activeSpinTicks = durationTicks;
        elapsed = 1;
        spinBreathTick();
    }

    private void spinBreathTick() {
        ServerLevel level = (ServerLevel) empress.level();
        Vec3 mouth = empress.getEyePosition();
        if (elapsed % 2 == 1) {
            Vec3 direction = Vec3.directionFromRotation(0.0F, empress.spinYaw(0.0F));
            spawnBreathParticles(level, mouth, direction);
        }
        if (elapsed % 10 != 1) {
            return;
        }
        Vec3 center = empress.getBoundingBox().getCenter();
        for (LivingEntity hit : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(BREATH_RANGE), empress::canDamage)) {
            Vec3 point = hit.getBoundingBox().getCenter();
            if (point.distanceToSqr(center) <= BREATH_RANGE * BREATH_RANGE
                    && clipBreath(level, mouth, point).getType() == HitResult.Type.MISS) {
                hit.hurt(SnowEmpressEntity.freezeDamage(empress, empress), 4.0F);
                if (active == null) {
                    return;
                }
            }
        }
    }

    private boolean hasSpinEnemy() {
        Vec3 center = empress.getBoundingBox().getCenter();
        Vec3 mouth = empress.getEyePosition();
        ServerLevel level = (ServerLevel) empress.level();
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(BREATH_RANGE),
                candidate -> valid(candidate) && (candidate == empress.getTarget()
                        || isAutomaticEnemy(candidate)))) {
            Vec3 point = entity.getBoundingBox().getCenter();
            if (point.distanceToSqr(center) <= BREATH_RANGE * BREATH_RANGE
                    && space.loaded(new AABB(mouth, point))
                    && clipBreath(level, mouth, point).getType() == HitResult.Type.MISS) {
                return true;
            }
        }
        return false;
    }

    private boolean valid(LivingEntity entity) {
        return entity != null && entity.isAlive() && !entity.isSpectator()
                && entity.level() == empress.level() && empress.canDamage(entity)
                && empress.distanceToSqr(entity) <= (entity == protectedTarget
                && empress.level().getGameTime() < protectUntil ? 64 * 64 : TARGET_RANGE * TARGET_RANGE);
    }

    private void protectOwner(Player owner) {
        int stamp = owner.getLastHurtByMobTimestamp();
        LivingEntity attacker = owner.getLastHurtByMob();
        long now = empress.level().getGameTime();
        if (stamp == ownerHurtStamp || owner.tickCount - stamp > 20 || attacker == null) {
            return;
        }
        ownerHurtStamp = stamp;
        if (!attacker.isAlive() || attacker.isSpectator() || attacker.level() != empress.level()
                || !empress.canDamage(attacker) || empress.distanceToSqr(attacker) > 64 * 64) {
            return;
        }
        protectedTarget = attacker;
        protectUntil = now + 80;
        empress.setTarget(attacker);
        if (now < nextRescue) {
            return;
        }
        AbilityId rescue = spaceAvailable(AbilityId.SLAM, attacker) ? AbilityId.SLAM : AbilityId.ICE_SPIKE;
        AbilityRequest request = new AbilityRequest(rescue, attacker);
        if (retryDelayed(request)) {
            return;
        }
        evasion.stop();
        if (active != null) {
            cancel(actionId, InterruptReason.TACTICAL);
        }
        // Rescue has its own five-second gate and can interrupt the normal rotation cooldown.
        int previousCooldown = cooldown;
        cooldown = 0;
        if (start(request)) {
            nextRescue = now + 100;
        } else {
            cooldown = previousCooldown;
        }
    }

    private double targetScore(LivingEntity target) {
        double score = 24 - empress.distanceTo(target);
        score += 6 * (1 - target.getHealth() / target.getMaxHealth());
        if (target instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() == empress.getOwner()) {
            score += 12;
        }
        return score;
    }

    private LivingEntity findMonster() {
        LivingEntity nearest = null;
        double best = Double.NEGATIVE_INFINITY;
        for (LivingEntity candidate : empress.level().getEntitiesOfClass(LivingEntity.class,
                empress.getBoundingBox().inflate(TARGET_RANGE),
                entity -> isAutomaticEnemy(entity) && valid(entity))) {
            double distance = targetScore(candidate);
            if (distance > best && empress.getSensing().hasLineOfSight(candidate)) {
                nearest = candidate;
                best = distance;
            }
        }
        return nearest;
    }

    boolean isAutomaticEnemy(LivingEntity candidate) {
        if (candidate instanceof Player) {
            return false;
        }
        return switch (empress.factionDisposition(candidate)) {
            case HOSTILE -> true;
            case PROTECTED -> false;
            case UNCLASSIFIED -> candidate instanceof Enemy || candidate.getType().is(MONSTER)
                    || candidate.getTags().contains("monster");
        };
    }

    private Step shoot(LivingEntity target) {
        if (!empress.getSensing().hasLineOfSight(target)) {
            return approach(target, 40);
        }
        phase = AbilityPhase.FIRING;
        stopNavigation();
        return fireIceSpike(target.getBoundingBox().getCenter())
                ? completed("PROJECTILE_SPAWNED") : new Step(Status.BLOCKED, "PROJECTILE_SPAWN_REJECTED");
    }

    private boolean fireIceSpike(Vec3 aimPoint) {
        IceSpikeEntity spike = new IceSpikeEntity(AddonContent.ICE_SPIKE.get(), empress.level());
        Vec3 direction = aimPoint.subtract(empress.getEyePosition()).normalize();
        Vec3 origin = empress.getEyePosition().add(direction.scale(0.5));
        spike.setOwner(empress);
        spike.setPos(origin.x, origin.y, origin.z);
        spike.shoot(direction.x, direction.y, direction.z, 1.5F, 0.0F);
        boolean spawned = empress.level().addFreshEntity(spike);
        if (active != null) {
            performed |= spawned;
            empress.playSound(TFSounds.SNOW_QUEEN_ATTACK.get(), 0.8F, 1.25F);
        }
        return spawned;
    }

    private Step slam(LivingEntity target, boolean spinAfterImpact) {
        boolean rebound = completedSlams > 0;
        if (slamWarning == 0) {
            stopNavigation();
            if (rebound) {
                return teleportAndDive(target);
            }
            DivePlan plan = findDive(target);
            if (plan.destination() == null) {
                return blockedDive(plan);
            }
            phase = AbilityPhase.WARNING;
            slamWarning = SLAM_WARNING_TICKS;
        }
        if (slamWarning > 0) {
            movement.hold(abilityMovement);
            ServerLevel level = (ServerLevel) empress.level();
            for (int i = 0; i < 4; i++) {
                level.sendParticles(TFParticleType.SNOW_WARNING.get(),
                        empress.getX(), empress.getY() - 0.6 - i * 0.55, empress.getZ(),
                        5, 0.45, 0.2, 0.45, 0.04);
            }
            if (--slamWarning == 0) {
                return teleportAndDive(target);
            }
            return null;
        }
        if (++slamDive > SLAM_DIVE_TICKS) {
            drive(Vec3.ZERO);
            return new Step(Status.TIMEOUT, "SLAM_DIVE_TIMEOUT");
        }
        boolean landed = empress.onGround() || empress.verticalCollisionBelow;
        boolean reachedTargetHeight = empress.getY() <= target.getY() + 0.65;
        AABB impact = empress.getBoundingBox().expandTowards(0, landed ? 0 : -1.35, 0).inflate(0.45, 0, 0.45);
        for (LivingEntity hit : empress.level().getEntitiesOfClass(LivingEntity.class, impact, empress::canDamage)) {
            if (empress.getSensing().hasLineOfSight(hit)) {
                boolean damaged = hit.hurt(SnowEmpressEntity.freezeDamage(empress, empress), 10.0F);
                if (active == null) {
                    return null;
                }
                if (damaged) {
                    launchSlamVictim(hit);
                    empress.playSound(TFSounds.SNOW_QUEEN_ATTACK.get(), 1.0F, 1.0F);
                    slamConnected = true;
                }
            }
        }
        if (landed || reachedTargetHeight || !target.isAlive()) {
            drive(Vec3.ZERO);
            boolean completed = slamConnected || impact.intersects(target.getBoundingBox())
                    && empress.getSensing().hasLineOfSight(target);
            if (landed || completed) {
                spawnSlamImpact((ServerLevel) empress.level());
            }
            if (spinAfterImpact) {
                if (landed || completed) {
                    beginSpinBreath(target, slamSpinTicks);
                    return null;
                }
                return completed("SLAM_DESCENT_FINISHED_WITHOUT_SPIN");
            }
            if ((!rebound && !completed) || !target.isAlive() || ++completedSlams >= SLAM_COMBO_HITS) {
                return completed("SLAM_DESCENT_FINISHED");
            }
            elapsed = 0;
            phase = AbilityPhase.PREPARING;
            slamWarning = 0;
            slamDive = 0;
            slamConnected = false;
            return null;
        }
        drive(new Vec3(0.0, -0.95, 0.0));
        return null;
    }

    private void launchSlamVictim(LivingEntity hit) {
        Vec3 direction = empress.getDeltaMovement().multiply(1, 0, 1);
        if (direction.horizontalDistanceSqr() < 1.0E-6) {
            direction = slamHeading;
        }
        if (direction.horizontalDistanceSqr() < 1.0E-6) {
            direction = Vec3.directionFromRotation(0.0F, empress.getYRot());
        }
        double vertical = hit.getDeltaMovement().y;
        double lift = 0.4F * Math.max(0, 1 - hit.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
        hit.knockback(0.5, -direction.x, -direction.z);
        Vec3 velocity = hit.getDeltaMovement();
        hit.setDeltaMovement(velocity.x, vertical + lift, velocity.z);
        hit.hasImpulse = true;
    }

    private Step teleportAndDive(LivingEntity target) {
        phase = AbilityPhase.TELEPORTING;
        DivePlan plan = findDive(target);
        Vec3 destination = plan.destination();
        if (destination == null) {
            return blockedDive(plan);
        }
        ServerLevel level = (ServerLevel) empress.level();
        slamHeading = destination.subtract(empress.position()).multiply(1, 0, 1);
        spawnSlamTeleportBurst(level, empress.getBoundingBox().getCenter());
        movement.teleport(abilityMovement, () -> empress.teleportTo(destination.x, destination.y, destination.z));
        if (active == null) {
            return null;
        }
        spawnSlamTeleportBurst(level, empress.getBoundingBox().getCenter());
        empress.setOnGround(false);
        phase = AbilityPhase.DIVING;
        drive(new Vec3(0.0, -0.95, 0.0));
        slamWarning = -1;
        performed = true;
        return null;
    }

    private DivePlan findDive(LivingEntity target) {
        boolean unknown = false;
        double head = target.getBoundingBox().maxY;
        for (double height : SLAM_START_HEIGHTS) {
            Vec3 destination = new Vec3(target.getX(), head + height, target.getZ());
            AABB diveSpace = empress.getBoundingBox().move(destination.subtract(empress.position()))
                    .expandTowards(0, 0.05 - height, 0);
            var fit = space.fit(diveSpace, true);
            if (fit == CombatSpace.Fit.CLEAR) {
                return new DivePlan(destination, false);
            }
            unknown |= fit == CombatSpace.Fit.UNKNOWN;
        }
        return new DivePlan(null, unknown);
    }

    private Step blockedDive(DivePlan plan) {
        movementObstructed = true;
        return new Step(Status.BLOCKED, plan.unknown() ? "SLAM_DESTINATION_UNLOADED" : "SLAM_NO_SAFE_DIVE_SPACE");
    }

    private void spawnSlamTeleportBurst(ServerLevel level, Vec3 center) {
        var random = empress.getRandom();
        for (int i = 0; i < 24; i++) {
            double y = 1.0 - 2.0 * (i + 0.5) / 24.0;
            double angle = i * 2.399963;
            double radius = Math.sqrt(1.0 - y * y);
            Vec3 velocity = new Vec3(Math.cos(angle) * radius, y, Math.sin(angle) * radius)
                    .scale(0.25 + random.nextDouble() * 0.16);
            level.sendParticles(TFParticleType.SNOW_GUARDIAN.get(), center.x, center.y, center.z,
                    0, velocity.x, velocity.y, velocity.z, 1.0);
        }
    }

    private void spawnSlamImpact(ServerLevel level) {
        for (int i = 0; i < 32; i++) {
            double angle = i * Math.PI / 16.0;
            double x = Math.cos(angle);
            double z = Math.sin(angle);
            double speed = 0.22 + empress.getRandom().nextDouble() * 0.12;
            level.sendParticles(TFParticleType.SNOW_GUARDIAN.get(),
                    empress.getX() + x * 0.4, empress.getY() + 0.15, empress.getZ() + z * 0.4,
                    0, x * speed, 0.06, z * speed, 1.0);
        }
    }

    private Step breathe(LivingEntity target) {
        if (!breathing) {
            if (empress.distanceToSqr(target) > BREATH_RANGE * BREATH_RANGE
                    || !empress.getSensing().hasLineOfSight(target)) {
                return approach(target, 60);
            }
            breathing = true;
            phase = AbilityPhase.BREATHING;
            performed = true;
            elapsed = 1;
            nextPathAttempt = 0;
            empress.playSound(TFSounds.SNOW_QUEEN_ATTACK.get(), 0.9F, 0.85F);
        }
        if (movement.owns(abilityMovement)) {
            moveWhileBreathing(target);
        }
        faceAimPoint(target.getBoundingBox().getCenter());
        ServerLevel level = (ServerLevel) empress.level();
        Vec3 start = empress.getEyePosition();
        Vec3 direction = target.getBoundingBox().getCenter().subtract(start).normalize();
        if (elapsed % 2 == 1) {
            spawnBreathParticles(level, start, direction);
        }
        if (elapsed % 10 == 0) {
            for (LivingEntity hit : level.getEntitiesOfClass(LivingEntity.class,
                    new AABB(start, start).inflate(BREATH_RANGE), empress::canDamage)) {
                Vec3 center = hit.getBoundingBox().getCenter();
                Vec3 offset = center.subtract(start);
                double distanceSquared = offset.lengthSqr();
                if (distanceSquared <= BREATH_RANGE * BREATH_RANGE
                        && offset.dot(direction) >= Math.sqrt(distanceSquared) * BREATH_MIN_DOT
                        && clipBreath(level, start, center).getType() == HitResult.Type.MISS) {
                    hit.hurt(SnowEmpressEntity.freezeDamage(empress, empress), 4.0F);
                    if (active == null) {
                        return null;
                    }
                }
            }
        }
        return elapsed >= 40 ? completed("BREATH_FINISHED") : null;
    }

    private void moveWhileBreathing(LivingEntity target) {
        Vec3 offset = target.getBoundingBox().getCenter().subtract(empress.getEyePosition());
        if (!empress.getSensing().hasLineOfSight(target) || offset.lengthSqr() > BREATH_RANGE * BREATH_RANGE) {
            if (moveToFiringPosition(target) != null) {
                stopNavigation();
                nextPathAttempt = elapsed + 6;
            }
            return;
        }

        stopNavigation();
        Vec3 forward = offset.normalize();
        Vec3 side = new Vec3(-forward.z, 0, forward.x).normalize();
        if ((actionId & 1) != 0) {
            side = side.reverse();
        }
        double distance = offset.length();
        double radial = distance > 7.5 ? 0.26 : distance < 4 ? -0.20 : 0;
        for (Vec3 step : new Vec3[] {forward.scale(radial).add(side.scale(0.16)),
                forward.scale(radial).subtract(side.scale(0.16)), forward.scale(radial)}) {
            if (space.step(step) == CombatSpace.Fit.CLEAR) {
                drive(step);
                return;
            }
        }
        drive(Vec3.ZERO);
    }

    private void spawnBreathParticles(ServerLevel level, Vec3 start, Vec3 direction) {
        var random = empress.getRandom();
        List<BlockHitResult> contacts = new ArrayList<>(3);
        Vec3 reference = Math.abs(direction.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 side = direction.cross(reference).normalize();
        Vec3 up = direction.cross(side);
        double phase = random.nextDouble() * Math.PI * 2.0;
        for (int ray = 0; ray < 7; ray++) {
            Vec3 spread = direction;
            if (ray != 0) {
                double angle = phase + (ray - 1) * Math.PI / 3.0;
                double axial = 1.0 - (1.0 - BREATH_MIN_DOT) * random.nextDouble();
                double radial = Math.sqrt(1.0 - axial * axial);
                spread = direction.scale(axial).add(side.scale(Math.cos(angle) * radial))
                        .add(up.scale(Math.sin(angle) * radial));
            }
            BlockHitResult hit = clipBreath(level, start, start.add(spread.scale(BREATH_RANGE)));
            double reach = start.distanceTo(hit.getLocation());
            for (int sample = 0; sample < 4 && reach > 0.55; sample++) {
                double distance = 0.35 + (reach - 0.55) * (sample + random.nextDouble()) / 4.0;
                Vec3 point = start.add(spread.scale(distance));
                sendBreathParticle(level, point, spread.scale(Math.min(0.27, (reach - distance - 0.1) / 10.0)));
            }
            if (hit.getType() == HitResult.Type.BLOCK && contacts.size() < 3
                    && contacts.stream().noneMatch(other -> other.getDirection() == hit.getDirection()
                    && other.getLocation().distanceToSqr(hit.getLocation()) < 0.36)) {
                contacts.add(hit);
            }
        }
        for (BlockHitResult hit : contacts) {
            scatterBreathOnFace(level, hit);
        }
    }

    private void scatterBreathOnFace(ServerLevel level, BlockHitResult hit) {
        Vec3 normal = Vec3.atLowerCornerOf(hit.getDirection().getNormal());
        Vec3 tangent = normal.cross(Math.abs(normal.y) > 0.5 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0));
        Vec3 bitangent = normal.cross(tangent);
        Vec3 origin = hit.getLocation().add(normal.scale(0.09));
        for (int i = 0; i < 4; i++) {
            double angle = (i + empress.getRandom().nextDouble()) * Math.PI / 2.0;
            Vec3 velocity = tangent.scale(Math.cos(angle) * 0.17)
                    .add(bitangent.scale(Math.sin(angle) * 0.17)).add(normal.scale(0.035));
            BlockHitResult obstruction = clipBreath(level, origin, origin.add(velocity.scale(10.0)));
            if (obstruction.getType() == HitResult.Type.BLOCK) {
                double available = Math.max(0.0, origin.distanceTo(obstruction.getLocation()) - 0.1);
                velocity = velocity.scale(Math.min(1.0, available / (velocity.length() * 10.0)));
            }
            sendBreathParticle(level, origin, velocity);
        }
    }

    private void sendBreathParticle(ServerLevel level, Vec3 point, Vec3 velocity) {
        // A count of zero sends the offsets as a single particle's velocity.
        level.sendParticles(TFParticleType.ICE_BEAM.get(), point.x, point.y, point.z,
                0, velocity.x, velocity.y, velocity.z, 1.0);
    }

    private BlockHitResult clipBreath(ServerLevel level, Vec3 start, Vec3 end) {
        return level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, empress));
    }

    private Step approach(LivingEntity target, int timeout) {
        phase = AbilityPhase.APPROACHING;
        if (elapsed >= timeout) {
            return new Step(Status.TIMEOUT, "APPROACH_TIMEOUT");
        }
        return moveToFiringPosition(target);
    }

    private Step moveToFiringPosition(LivingEntity target) {
        var nav = empress.getNavigation();
        var path = nav.getPath();
        if (elapsed >= nextPathAttempt && (approachTarget == null || nav.isDone()
                || target.position().distanceToSqr(approachTarget) >= 9.0)) {
            nextPathAttempt = elapsed + 12;
            approachTarget = target.position();
            var positions = space.firingPositions(target);
            var candidate = positions.isEmpty() ? null : nav.createPath(positions, 0);
            if (candidate != null && candidate.canReach() && !space.loadedPath(candidate)) {
                return new Step(Status.BLOCKED, "APPROACH_ROUTE_UNLOADED_OR_OUT_OF_BUDGET");
            }
            if (candidate == null || !candidate.canReach() || candidate.isDone()
                    || !movement.navigate(abilityMovement, candidate, 1.2)) {
                navigationRejected = true;
                if (nav.isDone()) {
                    return new Step(Status.BLOCKED, "APPROACH_NO_REACHABLE_FIRING_POSITION");
                }
            }
            path = nav.getPath();
            pathNode = -1;
        }
        if (path != null && !path.isDone()) {
            Vec3 waypoint = path.getNextEntityPos(empress);
            if (!space.loaded(new AABB(empress.position(), waypoint))) {
                movementObstructed = true;
                return new Step(Status.BLOCKED, "APPROACH_ROUTE_UNLOADED");
            }
            int node = path.getNextNodeIndex();
            double distance = empress.position().distanceTo(waypoint);
            if (node != pathNode || distance + 0.08 < waypointDistance) {
                pathNode = node;
                waypointDistance = distance;
                stalledTicks = 0;
            } else if (++stalledTicks >= 12) {
                movementObstructed = true;
                return new Step(Status.BLOCKED, "APPROACH_NO_PROGRESS");
            }
        }
        return null;
    }
}
