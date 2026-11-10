package com.snowempress;

import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import twilightforest.entity.boss.SnowQueen;

@Mod.EventBusSubscriber(modid = SnowEmpress.MOD_ID)
public final class SilverBellEvents {
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void dropBell(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof SnowQueen queen) || !(queen.level() instanceof ServerLevel level)) {
            return;
        }
        ItemEntity bell = new ItemEntity(level, queen.getX(), queen.getY() + 0.5, queen.getZ(),
                new ItemStack(AddonContent.SILVER_BELL.get()));
        bell.setDefaultPickUpDelay();
        // Direct insertion avoids Twilight Forest's boss-loot capture and reward chest.
        if (!level.addFreshEntity(bell)) {
            return;
        }
        Entity attacker = event.getSource().getEntity();
        ServerPlayer recipient = attacker instanceof ServerPlayer player ? player : null;
        if (recipient == null && attacker != null) {
            UUID owner = HungryCraftOwnership.ownerOf(attacker);
            if (owner != null) {
                recipient = level.getServer().getPlayerList().getPlayer(owner);
            }
        }
        if (recipient == null && queen.getKillCredit() instanceof ServerPlayer player) {
            recipient = player;
        }
        if (recipient != null) {
            recipient.sendSystemMessage(Component.translatable("item.snowempress.silver_bell.redemption"));
        }
    }

    @SubscribeEvent
    public static void protectBell(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof ItemEntity item && item.getItem().is(AddonContent.SILVER_BELL.get())) {
            item.setInvulnerable(true);
            item.setGlowingTag(true);
            item.setUnlimitedLifetime();
            item.noCulling = true;
        }
    }
}
