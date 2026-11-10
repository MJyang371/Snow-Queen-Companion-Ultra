package com.snowempress;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import twilightforest.init.TFParticleType;

public final class IceSpikeEntity extends ThrowableProjectile {
    public IceSpikeEntity(EntityType<? extends IceSpikeEntity> type, Level level) {
        super(type, level);
        setNoGravity(true);
    }

    @Override
    protected void defineSynchedData() {}

    @Override
    public void tick() {
        super.tick();
        if (!isRemoved() && isInWater()) {
            // ThrowableProjectile applies 0.8 drag in water instead of the 0.99 used in air.
            setDeltaMovement(getDeltaMovement().scale(0.99F / 0.8F));
        }
        if (level().isClientSide() && !isRemoved()) {
            for (int i = 0; i < 3; i++) {
                var point = position().subtract(getDeltaMovement().scale(i / 3.0));
                level().addParticle(TFParticleType.SNOW_GUARDIAN.get(), point.x, point.y, point.z, 0, 0, 0);
            }
        } else if (tickCount >= 80) {
            discard();
        }
    }

    @Override
    protected boolean canHitEntity(net.minecraft.world.entity.Entity entity) {
        if (entity instanceof SnowEmpressIceShield shield && shield.getParent() == getOwner()) {
            return false;
        }
        return super.canHitEntity(entity)
                && (!(getOwner() instanceof SnowEmpressEntity empress) || empress.canDamage(entity));
    }

    @Override
    protected void onHit(HitResult hit) {
        if (!(level() instanceof ServerLevel server)) {
            return;
        }
        if (hit instanceof EntityHitResult entityHit
                && getOwner() instanceof SnowEmpressEntity empress && empress.canDamage(entityHit.getEntity())) {
            entityHit.getEntity().hurt(SnowEmpressEntity.freezeDamage(this, empress), 6.0F);
        }
        var point = hit.getLocation();
        server.sendParticles(TFParticleType.SNOW_GUARDIAN.get(), point.x, point.y, point.z,
                24, 0.28, 0.28, 0.28, 0.08);
        discard();
    }
}
