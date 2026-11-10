package com.snowempress.mixin;

import com.snowempress.CarryRenderPose;
import com.snowempress.SnowEmpressEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerModel.class)
abstract class CarryPlayerModelMixin {
    @Unique
    private boolean snowempress$carriedPose;

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"))
    private void snowempress$resetPreviousPose(LivingEntity entity, float swing, float amount, float age,
                                               float yaw, float pitch, CallbackInfo callback) {
        if (snowempress$carriedPose) {
            CarryRenderPose.resetParts((PlayerModel<?>) (Object) this);
            snowempress$carriedPose = false;
        }
    }

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void snowempress$poseRider(LivingEntity entity, float swing, float amount, float age,
                                       float yaw, float pitch, CallbackInfo callback) {
        if (entity.getVehicle() instanceof SnowEmpressEntity) {
            CarryRenderPose.player((PlayerModel<?>) (Object) this);
            snowempress$carriedPose = true;
        }
    }
}
