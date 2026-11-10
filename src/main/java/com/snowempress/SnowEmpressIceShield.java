package com.snowempress;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraftforge.entity.PartEntity;
import twilightforest.init.TFSounds;

final class SnowEmpressIceShield extends PartEntity<SnowEmpressEntity> {
    private static final EntityDimensions SIZE = EntityDimensions.scalable(0.75F, 0.75F);

    SnowEmpressIceShield(SnowEmpressEntity parent) {
        super(parent);
        refreshDimensions();
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return SIZE;
    }

    @Override
    public boolean isPickable() {
        return getParent().isAlive();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.getDirectEntity() instanceof AbstractArrow arrow && arrow.getPierceLevel() > 0) {
            return true;
        }
        float pitch = ((random.nextFloat() - random.nextFloat()) * 0.7F + 1.0F) * 2.0F;
        playSound(TFSounds.SNOW_QUEEN_BREAK.get(), 1.0F, pitch);
        return false;
    }

    @Override
    protected void defineSynchedData() {}

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}
}
