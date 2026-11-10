package com.snowempress;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import twilightforest.TwilightForestMod;
import twilightforest.client.model.TFModelLayers;

final class SummonedIceCrystalRenderer extends MobRenderer<SummonedIceCrystal, SummonedIceCrystalRenderer.Model> {
    SummonedIceCrystalRenderer(EntityRendererProvider.Context context) {
        super(context, new Model(context.bakeLayer(TFModelLayers.ICE_CRYSTAL)), 0.25F);
    }

    @Override
    protected void scale(SummonedIceCrystal entity, PoseStack stack, float partialTick) {
        stack.translate(0, Mth.sin((entity.tickCount + partialTick) * 0.2F) * 0.15F, 0);
    }

    @Override
    public ResourceLocation getTextureLocation(SummonedIceCrystal entity) {
        return TwilightForestMod.getModelTexture("icecrystal.png");
    }

    static final class Model extends HierarchicalModel<SummonedIceCrystal> {
        private final ModelPart root;
        private final ModelPart[] spikes = new ModelPart[16];

        Model(ModelPart root) {
            super(RenderType::entityTranslucent);
            this.root = root;
            for (int i = 0; i < spikes.length; i++) {
                spikes[i] = root.getChild("spike_" + i);
            }
        }

        @Override
        public ModelPart root() {
            return root;
        }

        @Override
        public void setupAnim(SummonedIceCrystal entity, float swing, float amount, float age, float yaw, float pitch) {
            float phase = age / 5.0F;
            for (int i = 0; i < spikes.length; i++) {
                spikes[i].xRot = Mth.sin(phase) / 4.0F + i * Mth.PI / 8.0F;
                spikes[i].yRot = phase + (i % 4 == 0 ? 1.0F : i % 4 == 2 ? -1.0F : 0.0F);
                spikes[i].zRot = Mth.cos(phase) / 4.0F;
            }
        }

        @Override
        public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlay,
                                   float red, float green, float blue, float alpha) {
            super.renderToBuffer(pose, buffer, light, overlay, red, green, blue, alpha * 0.6F);
        }
    }
}
