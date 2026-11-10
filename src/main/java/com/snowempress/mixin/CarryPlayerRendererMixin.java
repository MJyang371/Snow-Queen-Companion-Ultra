package com.snowempress.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.snowempress.CarryRenderPose;
import com.snowempress.SnowEmpressEntity;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerRenderer.class)
abstract class CarryPlayerRendererMixin {
    @Inject(method = "setupRotations(Lnet/minecraft/client/player/AbstractClientPlayer;Lcom/mojang/blaze3d/vertex/PoseStack;FFF)V",
            at = @At("HEAD"), cancellable = true)
    private void snowempress$rotateCarriedPlayer(AbstractClientPlayer player, PoseStack pose, float age,
                                                float bodyYaw, float partialTick, CallbackInfo callback) {
        if (player.getVehicle() instanceof SnowEmpressEntity mount) {
            CarryRenderPose.playerRoot(pose, mount, partialTick);
            callback.cancel();
        }
    }
}
