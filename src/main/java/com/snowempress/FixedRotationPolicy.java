package com.snowempress;

import net.minecraft.world.entity.LivingEntity;

final class FixedRotationPolicy implements CombatPolicy {
    private static final SnowEmpressCombat.AbilityId[] ROTATION = {
        SnowEmpressCombat.AbilityId.ICE_SPIKE, SnowEmpressCombat.AbilityId.SLAM,
        SnowEmpressCombat.AbilityId.BREATH, SnowEmpressCombat.AbilityId.SUMMON_CRYSTALS,
        SnowEmpressCombat.AbilityId.SPIN_BREATH, SnowEmpressCombat.AbilityId.GUNSHIP_BOMBARDMENT,
        SnowEmpressCombat.AbilityId.SLAM_SPIN, SnowEmpressCombat.AbilityId.RETREAT
    };
    private int next;

    @Override
    public SnowEmpressCombat.AbilityRequest choose(LivingEntity target) {
        return new SnowEmpressCombat.AbilityRequest(preview(), target);
    }

    @Override
    public SnowEmpressCombat.AbilityId preview() {
        return ROTATION[next];
    }

    @Override
    public void onFinished(SnowEmpressCombat.AbilityOutcome outcome) {
        next = (next + 1) % ROTATION.length;
    }

    @Override
    public void onDeferred(SnowEmpressCombat.AbilityRequest request) {
        next = (next + 1) % ROTATION.length;
    }
}
