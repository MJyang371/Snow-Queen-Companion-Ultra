package com.snowempress;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/** Static frame zero of the edited Blockbench carry_hold preset. */
public final class CarryRenderPose {
    private CarryRenderPose() {
    }

    public static void resetParts(HumanoidModel<?> model) {
        model.head.resetPose();
        model.hat.resetPose();
        model.body.resetPose();
        model.rightArm.resetPose();
        model.leftArm.resetPose();
        model.rightLeg.resetPose();
        model.leftLeg.resetPose();
    }

    static void queen(HumanoidModel<?> model) {
        resetParts(model);
        rotate(model.head, -5.7875F, -39.9455F, 1.4099F);
        model.head.y += 4F / 1.2F;
        rotate(model.rightArm, 73.8055F, 6.7372F, 0.6969F);
        rotate(model.leftArm, 25.5796F, -12.6997F, 30.2556F);
        model.leftArm.z += 2F / 1.2F;
    }

    public static void player(PlayerModel<?> model) {
        resetParts(model);
        rotate(model.head, -8.0681F, -7.4267F, 6.0497F);
        rotate(model.rightArm, 40.5019F, -9.0732F, 8.2583F);
        model.rightArm.x -= 3;
        model.rightArm.z -= 3;
        rotate(model.leftArm, 25.8065F, -3.2802F, -1.2516F);
        rotate(model.rightLeg, 24, 0, -3);
        rotate(model.leftLeg, 30, 0, 3);
        model.hat.copyFrom(model.head);
        model.jacket.copyFrom(model.body);
        model.rightSleeve.copyFrom(model.rightArm);
        model.leftSleeve.copyFrom(model.leftArm);
        model.rightPants.copyFrom(model.rightLeg);
        model.leftPants.copyFrom(model.leftLeg);
    }

    public static float vehicleYaw(SnowEmpressEntity mount, float partialTick) {
        return Mth.rotLerp(partialTick, mount.yRotO, mount.getYRot());
    }

    public static void playerRoot(PoseStack pose, SnowEmpressEntity mount, float partialTick) {
        pose.mulPose(Axis.YP.rotationDegrees(180 - vehicleYaw(mount, partialTick)));
        pose.translate(0, 0.751, 0);
        rotateRoot(pose, 87.7013F, 15.7957F, 30.7789F);
        rotateRoot(pose, 25, 0, 0);
        rotateRoot(pose, 0, 0, -73);
        pose.translate(0, -0.751, 0);
    }

    private static void rotate(ModelPart part, float x, float y, float z) {
        part.xRot = -x * Mth.DEG_TO_RAD;
        part.yRot = y * Mth.DEG_TO_RAD;
        part.zRot = -z * Mth.DEG_TO_RAD;
    }

    private static void rotateRoot(PoseStack pose, float x, float y, float z) {
        pose.mulPose(Axis.ZP.rotationDegrees(-z));
        pose.mulPose(Axis.YP.rotationDegrees(-y));
        pose.mulPose(Axis.XP.rotationDegrees(x));
    }
}
