package com.snowempress;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

final class SilverBellItem extends Item {
    SilverBellItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel server) {
            UUID owner = player.getUUID();
            BellSummons summons = BellSummons.get(server.getServer());
            UUID active = summons.activeFor(owner);
            List<SnowEmpressEntity> owned = new ArrayList<>();
            for (ServerLevel dimension : server.getServer().getAllLevels()) {
                owned.addAll(dimension.getEntities(EntityTypeTest.forClass(SnowEmpressEntity.class),
                        empress -> empress.isAlive() && owner.equals(empress.getOwnerUUID())));
            }
            if (active != null || !owned.isEmpty()) {
                if (active != null) {
                    summons.release(owner, active);
                }
                owned.forEach(SnowEmpressEntity::discard);
            } else if (!summon(server, player, summons)) {
                player.displayClientMessage(Component.translatable("item.snowempress.silver_bell.no_space"), true);
                return InteractionResultHolder.fail(stack);
            }
            player.getCooldowns().addCooldown(this, 8);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    AddonContent.SILVER_BELL_RING.get(), SoundSource.PLAYERS, 1.0F, 1.0F);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    private boolean summon(ServerLevel level, Player player, BellSummons summons) {
        SnowEmpressEntity empress = AddonContent.SNOW_EMPRESS.get().create(level);
        if (empress == null) {
            return false;
        }
        Vec3 spot = empress.findRecallPosition(level, player);
        if (spot == null) {
            return false;
        }
        empress.moveTo(spot.x, spot.y, spot.z, player.getYRot(), 0);
        empress.bindBellSummon(player);
        summons.bind(player.getUUID(), empress.getUUID());
        if (!level.addFreshEntity(empress)) {
            summons.release(player.getUUID(), empress.getUUID());
            return false;
        }
        return true;
    }
}
