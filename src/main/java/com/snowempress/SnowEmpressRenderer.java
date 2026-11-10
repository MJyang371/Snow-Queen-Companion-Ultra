package com.snowempress;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import twilightforest.TwilightForestMod;
import twilightforest.client.model.entity.SnowQueenModel;

@Mod.EventBusSubscriber(modid = SnowEmpress.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class SnowEmpressRenderer extends MobRenderer<SnowEmpressEntity, HumanoidModel<SnowEmpressEntity>> {
    private static final ModelLayerLocation LAYER =
            new ModelLayerLocation(new ResourceLocation(SnowEmpress.MOD_ID, "snow_empress"), "main");
    private static final ResourceLocation TEXTURE = TwilightForestMod.getModelTexture("snowqueen.png");
    private final BlockRenderDispatcher blocks;

    public SnowEmpressRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<SnowEmpressEntity>(context.bakeLayer(LAYER)) {
            private boolean carryPose;

            @Override
            public void setupAnim(SnowEmpressEntity entity, float swing, float amount, float age,
                                  float headYaw, float headPitch) {
                if (entity.isVehicle()) {
                    CarryRenderPose.queen(this);
                    carryPose = true;
                    return;
                }
                if (carryPose) {
                    CarryRenderPose.resetParts(this);
                    carryPose = false;
                }
                boolean spinning = entity.isSpinning();
                boolean aimedFlight = entity.isInAimedFlight();
                super.setupAnim(entity, swing, amount, age,
                        spinning || aimedFlight ? 0.0F : headYaw, spinning ? 0.0F : headPitch);
            }
        }, 0.625F);
        blocks = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(SnowEmpressEntity empress, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int light) {
        super.render(empress, yaw, partialTick, pose, buffer, light);
        if (!empress.isAlive() || empress.isInvisible()) {
            return;
        }
        for (int i = 0; i < SnowEmpressEntity.SHIELD_COUNT; i++) {
            Vec3 offset = empress.shieldOffset(i, partialTick - 1.0F);
            pose.pushPose();
            pose.translate(offset.x - 0.5, offset.y, offset.z - 0.5);
            blocks.renderSingleBlock(Blocks.PACKED_ICE.defaultBlockState(), pose, buffer, light,
                    OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }

    @SubscribeEvent
    public static void registerLayer(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(LAYER, SnowQueenModel::create);
    }

    @SubscribeEvent
    public static void registerRenderer(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(AddonContent.SNOW_EMPRESS.get(), SnowEmpressRenderer::new);
        event.registerEntityRenderer(AddonContent.ICE_SPIKE.get(), IceSpikeRenderer::new);
        event.registerEntityRenderer(AddonContent.SUMMONED_ICE_CRYSTAL.get(), SummonedIceCrystalRenderer::new);
    }

    @Override
    public ResourceLocation getTextureLocation(SnowEmpressEntity empress) {
        return TEXTURE;
    }

    @Override
    protected void setupRotations(SnowEmpressEntity empress, PoseStack pose, float age,
                                  float bodyYaw, float partialTick) {
        if (empress.isVehicle()) {
            bodyYaw = CarryRenderPose.vehicleYaw(empress, partialTick);
        } else if (empress.isSpinning()) {
            bodyYaw = empress.spinYaw(partialTick - 1.0F);
        } else if (empress.isInAimedFlight()) {
            bodyYaw = Mth.rotLerp(partialTick, empress.yRotO, empress.getYRot());
        }
        super.setupRotations(empress, pose, age, bodyYaw, partialTick);
    }

    @Override
    protected void scale(SnowEmpressEntity empress, PoseStack pose, float partialTick) {
        pose.scale(1.2F, 1.2F, 1.2F);
    }
}
