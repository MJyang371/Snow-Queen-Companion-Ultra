package com.snowempress;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = SnowEmpress.MOD_ID, value = Dist.CLIENT)
public final class CarryFlightClient {
    private static final KeyMapping DESCEND = new KeyMapping("key.snowempress.descend",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, "key.categories.snowempress");
    private static SnowEmpressEntity lastMount;
    private static int lastKeys;

    @Mod.EventBusSubscriber(modid = SnowEmpress.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Keys {
        @SubscribeEvent
        public static void register(RegisterKeyMappingsEvent event) {
            event.register(DESCEND);
        }
    }

    @SubscribeEvent
    public static void sendInput(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || !(client.player.getVehicle() instanceof SnowEmpressEntity mount)) {
            lastMount = null;
            return;
        }
        var keys = client.options;
        int input = 0;
        if (client.screen == null) {
            if (keys.keyUp.isDown()) input |= CarryFlightInput.FORWARD;
            if (keys.keyDown.isDown()) input |= CarryFlightInput.BACK;
            if (keys.keyLeft.isDown()) input |= CarryFlightInput.LEFT;
            if (keys.keyRight.isDown()) input |= CarryFlightInput.RIGHT;
            if (keys.keyJump.isDown()) input |= CarryFlightInput.UP;
            if (DESCEND.isDown()) input |= CarryFlightInput.DOWN;
        }
        if (mount != lastMount || input != lastKeys || client.player.tickCount % 5 == 0) {
            CarryFlightInput.send(input);
            lastKeys = input;
            lastMount = mount;
        }
    }
}
