package com.snowempress;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.Blocks;

final class IceSpikeRenderer extends EntityRenderer<IceSpikeEntity> {
    private final EntityRendererProvider.Context context;

    IceSpikeRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.context = context;
    }

    @Override
    public void render(IceSpikeEntity spike, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int light) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(Mth.rotLerp(partialTick, spike.yRotO, spike.getYRot())));
        pose.mulPose(Axis.XP.rotationDegrees(-Mth.lerp(partialTick, spike.xRotO, spike.getXRot())));
        pose.scale(0.16F, 0.16F, 0.65F);
        pose.translate(-0.5, -0.5, -0.5);
        context.getBlockRenderDispatcher().renderSingleBlock(Blocks.SNOW_BLOCK.defaultBlockState(),
                pose, buffer, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(IceSpikeEntity spike) {
        return InventoryMenu.BLOCK_ATLAS;
    }
}
