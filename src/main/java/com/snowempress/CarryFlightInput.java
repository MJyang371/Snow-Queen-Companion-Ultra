package com.snowempress;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

final class CarryFlightInput {
    static final int FORWARD = 1, BACK = 2, LEFT = 4, RIGHT = 8, UP = 16, DOWN = 32;
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(SnowEmpress.MOD_ID, "carry_flight"), () -> VERSION,
            VERSION::equals, VERSION::equals);

    private record Input(int keys) {}

    static void register() {
        CHANNEL.messageBuilder(Input.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((input, buffer) -> buffer.writeByte(input.keys()))
                .decoder((FriendlyByteBuf buffer) -> new Input(buffer.readUnsignedByte()))
                .consumerMainThread((input, context) -> {
                    var player = context.get().getSender();
                    if (player != null && player.getVehicle() instanceof SnowEmpressEntity empress) {
                        empress.acceptCarryInput(player, input.keys() & 63);
                    }
                    context.get().setPacketHandled(true);
                }).add();
    }

    static void send(int keys) {
        CHANNEL.sendToServer(new Input(keys));
    }
}
