package com.snowempress;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import twilightforest.entity.monster.IceCrystal;
import twilightforest.init.TFSounds;
import twilightforest.init.TFParticleType;

public final class SummonedIceCrystal extends PathfinderMob implements OwnableEntity {
    private static final int LIFETIME = 600;
    private static final int ATTACK_INTERVAL = 10;
    private static final int PATH_INTERVAL = 8;
    private SnowEmpressEntity summoner;
    private long expiresAt = Long.MIN_VALUE;
    private int nextAttackTick;
    private int nextPathTick;

    public SummonedIceCrystal(EntityType<? extends SummonedIceCrystal> type, Level level) {
        super(type, level);
        moveControl = new FlyingMoveControl(this, 25, true);
        setNoGravity(true);
        setPersistenceRequired();
    }

    static AttributeSupplier.Builder attributes() {
        return IceCrystal.registerAttributes().add(Attributes.FLYING_SPEED, 0.32)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        if (expiresAt == Long.MIN_VALUE) {
            expiresAt = level().getGameTime() + LIFETIME;
        }
    }

    void bindSummoner(SnowEmpressEntity empress, LivingEntity target) {
        summoner = empress;
        retarget(target);
    }

    void retarget(LivingEntity target) {
        setTarget(target);
        getNavigation().stop();
        nextPathTick = tickCount;
    }

    @Override
    public UUID getOwnerUUID() {
        return summoner == null ? null : summoner.getOwnerUUID();
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        FlyingPathNavigation navigation = new FlyingPathNavigation(this, level);
        navigation.setCanFloat(true);
        return navigation;
    }

    @Override
    public void tick() {
        if (!level().isClientSide() && level().getGameTime() >= expiresAt) {
            vanish();
            return;
        }
        super.tick();
        if (level().isClientSide() && isAlive()) {
            for (int i = 0; i < 3; i++) {
                double x = xOld + (random.nextFloat() - random.nextFloat()) * 0.3F;
                double y = yOld + getEyeHeight() + (random.nextFloat() - random.nextFloat()) * 0.5F;
                double z = zOld + (random.nextFloat() - random.nextFloat()) * 0.3F;
                level().addParticle(TFParticleType.SNOW_GUARDIAN.get(), x, y, z, 0, 0, 0);
            }
        }
    }

    void vanish() {
        if (isRemoved()) {
            return;
        }
        if (level() instanceof ServerLevel server) {
            server.sendParticles(TFParticleType.SNOW_GUARDIAN.get(), getX(), getY(0.5), getZ(),
                    24, 0.35, 0.45, 0.35, 0.10);
        }
        discard();
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        if (summoner == null || !summoner.isAlive() || summoner.level() != level()) {
            getNavigation().stop();
            return;
        }
        LivingEntity target = summoner.getTarget();
        if (target == null || !target.isAlive()
                || target.level() != level() || target.isSpectator() || !summoner.canDamage(target)) {
            target = null;
        }
        if (target != getTarget()) {
            retarget(target);
        }
        if (target == null) {
            double angle = (tickCount + getId() * 19L) * 0.045;
            Vec3 orbit = summoner.position().add(Math.cos(angle) * 2.6, 0.8, Math.sin(angle) * 2.6);
            flyToward(orbit, 0.85);
            return;
        }

        getLookControl().setLookAt(target, 30.0F, 30.0F);
        if (getBoundingBox().inflate(0.65).intersects(target.getBoundingBox())
                && hasClearStrike(getEyePosition(), target)) {
            getNavigation().stop();
            setDeltaMovement(getDeltaMovement().scale(0.6));
            if (tickCount >= nextAttackTick) {
                if (target.hurt(SnowEmpressEntity.freezeDamage(this, this),
                        (float) getAttributeValue(Attributes.ATTACK_DAMAGE))) {
                    setLastHurtMob(target);
                    if (target instanceof net.minecraft.world.entity.Mob mob && mob.isAlive()) {
                        mob.setLastHurtByMob(this);
                        mob.setTarget(this);
                    }
                }
                nextAttackTick = tickCount + ATTACK_INTERVAL;
            }
            return;
        }
        if (tickCount >= nextPathTick) {
            nextPathTick = tickCount + PATH_INTERVAL;
            updateAttackPath(target);
        }
    }

    private void updateAttackPath(LivingEntity target) {
        BlockPos feet = target.blockPosition();
        Set<BlockPos> positions = new HashSet<>();
        for (int height = 0; height <= 2; height++) {
            BlockPos layer = feet.above(height);
            addAttackPosition(positions, layer, target);
            for (Direction side : Direction.Plane.HORIZONTAL) {
                addAttackPosition(positions, layer.relative(side), target);
            }
        }
        var path = positions.isEmpty() ? getNavigation().createPath(target, 1)
                : getNavigation().createPath(positions, 0);
        if (path != null && !path.isDone()) {
            getNavigation().moveTo(path, 1.5);
        } else if (getNavigation().isDone() && hasClearStrike(getEyePosition(), target)) {
            flyToward(target.position(), 1.5);
        }
    }

    private void addAttackPosition(Set<BlockPos> positions, BlockPos block, LivingEntity target) {
        Vec3 feet = Vec3.atBottomCenterOf(block);
        var box = getBoundingBox().move(feet.subtract(position()));
        if (level().hasChunkAt(block) && box.inflate(0.6).intersects(target.getBoundingBox())
                && level().noCollision(this, box) && hasClearStrike(feet.add(0, getEyeHeight(), 0), target)) {
            positions.add(block);
        }
    }

    private boolean hasClearStrike(Vec3 origin, LivingEntity target) {
        return level().clip(new ClipContext(origin, target.getBoundingBox().getCenter(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
    }

    private void flyToward(Vec3 destination, double speed) {
        Vec3 delta = destination.subtract(position());
        Vec3 step = delta.lengthSqr() > 9.0 ? delta.normalize().scale(3.0) : delta;
        if (level().noCollision(this, getBoundingBox().expandTowards(step))) {
            getNavigation().stop();
            getMoveControl().setWantedPosition(destination.x, destination.y, destination.z, speed);
        } else if (tickCount % 5 == 0) {
            getNavigation().moveTo(destination.x, destination.y, destination.z, speed);
        }
    }

    @Override
    public boolean causeFallDamage(float distance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    protected boolean canRide(Entity entity) {
        return false;
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return TFSounds.ICE_CORE_AMBIENT.get();
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return TFSounds.ICE_CORE_HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return TFSounds.ICE_CORE_DEATH.get();
    }
}
