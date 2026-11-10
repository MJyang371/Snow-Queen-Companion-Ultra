package com.snowempress;

import java.util.EnumMap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import static com.snowempress.SnowEmpressCombat.*;

final class UtilityCombatPolicy implements CombatPolicy {
    private final SnowEmpressEntity empress;
    private final EnumMap<AbilityId, Long> lastUsed = new EnumMap<>(AbilityId.class);
    private AbilityId preview;
    private long nextDecision;

    UtilityCombatPolicy(SnowEmpressEntity empress) {
        this.empress = empress;
    }

    @Override
    public AbilityRequest choose(LivingEntity target) {
        long now = empress.level().getGameTime();
        if (target == null || now < nextDecision) {
            return null;
        }
        nextDecision = now + 5;
        double distance = empress.distanceTo(target);
        boolean visible = empress.getSensing().hasLineOfSight(target);
        int nearby = empress.level().getEntitiesOfClass(LivingEntity.class,
                empress.getBoundingBox().inflate(8), mob -> empress.combat().isAutomaticEnemy(mob) && mob.isAlive()
                        && empress.canDamage(mob) && empress.getSensing().hasLineOfSight(mob)).size();
        double health = empress.getHealth() / empress.getMaxHealth();
        AbilityRequest best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        CombatSpace space = new CombatSpace(empress);
            empress.setSpinBreathDurationTicks(nearby >= 3 ? 100 : 60);
            empress.setSlamSpinDurationTicks(nearby >= 3 ? 100 : 60);
            empress.setRetreatSettings(new RetreatSettings(health < 0.4 ? 0.46 : 0.34,
                    health < 0.4 ? 50 : 30, health < 0.4 ? 12 : 7));
            double height = 6;
            while (height > 2 && space.step(new Vec3(0,
                    Math.max(0, target.getBoundingBox().getCenter().y + height - empress.getY()), 0))
                    != CombatSpace.Fit.CLEAR) {
                height -= 2;
            }
            empress.setGunshipSettings(new GunshipSettings(health < 0.4 ? 0.65 : 0.58,
                    nearby >= 3 ? 120 : 80, height));

        for (AbilityId ability : AbilityId.values()) {
            AbilityRequest request = new AbilityRequest(ability, target);
            if (empress.combat().canStart(request) != StartCheck.READY
                    || !empress.combat().spaceAvailable(ability, target)) {
                continue;
            }
            if (ability == AbilityId.RETREAT && space.step(empress.position().subtract(target.position())
                    .multiply(1, 0, 1).normalize().scale(2)) != CombatSpace.Fit.CLEAR) {
                continue;
            }
            double score = switch (ability) {
                case ICE_SPIKE -> visible ? 35 + Math.min(distance, 16) : 14;
                case BREATH -> visible && distance <= 10 ? 48 + nearby * 4 : 22;
                case SLAM -> distance > 4 ? 55 : 38;
                case SUMMON_CRYSTALS -> empress.summonedCrystalCount() < 6
                        ? 48 + (6 - empress.summonedCrystalCount()) * 3 : -20;
                case SPIN_BREATH -> visible && distance <= 10 ? 40 + nearby * 12 : 12;
                case GUNSHIP_BOMBARDMENT -> visible ? 60 : 5;
                case SLAM_SPIN -> 42 + nearby * 7;
                case RETREAT -> distance < 6 ? 35 + (1 - health) * 55 : -10;
            };
            long age = now - lastUsed.getOrDefault(ability, now - 200);
            score -= Math.max(0, 1 - age / 160.0) * 55;
            if (score > bestScore) {
                bestScore = score;
                best = request;
            }
        }
        preview = best == null ? null : best.ability();
        return best;
    }

    @Override
    public AbilityId preview() {
        return preview;
    }

    @Override
    public void onFinished(AbilityOutcome outcome) {
        lastUsed.put(outcome.ability(), empress.level().getGameTime());
    }
}
