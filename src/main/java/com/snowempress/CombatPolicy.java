package com.snowempress;

import net.minecraft.world.entity.LivingEntity;

interface CombatPolicy {
    // Null means the policy has no action to start at this time.
    SnowEmpressCombat.AbilityRequest choose(LivingEntity target);

    default SnowEmpressCombat.AbilityId preview() {
        return null;
    }

    default void onFinished(SnowEmpressCombat.AbilityOutcome outcome) {
    }

    default void onDeferred(SnowEmpressCombat.AbilityRequest request) {
    }
}
