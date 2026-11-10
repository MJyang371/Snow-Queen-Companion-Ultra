package com.snowempress;

import java.util.function.Supplier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SnowEmpress.MOD_ID)
public final class SnowEmpressSpawnEgg extends ForgeSpawnEggItem {
    private static final ThreadLocal<Player> PLACING_PLAYER = new ThreadLocal<>();

    public SnowEmpressSpawnEgg(Supplier<? extends EntityType<? extends Mob>> type, Properties properties) {
        super(type, 0xb1b2d4, 0x87006e, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide() || context.getPlayer() == null) {
            return super.useOn(context);
        }
        return withPlacingPlayer(context.getPlayer(), () -> super.useOn(context));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            return super.use(level, player, hand);
        }
        return withPlacingPlayer(player, () -> super.use(level, player, hand));
    }

    private static <T> T withPlacingPlayer(Player player, Supplier<T> use) {
        Player previous = PLACING_PLAYER.get();
        PLACING_PLAYER.set(player);
        try {
            return use.get();
        } finally {
            if (previous == null) {
                PLACING_PLAYER.remove();
            } else {
                PLACING_PLAYER.set(previous);
            }
        }
    }

    @SubscribeEvent
    public static void bindOwner(EntityJoinLevelEvent event) {
        // Both vanilla egg paths add the entity synchronously during use.
        Player player = PLACING_PLAYER.get();
        if (player != null && !event.loadedFromDisk() && event.getLevel() == player.level()
                && event.getEntity() instanceof SnowEmpressEntity empress) {
            empress.tame(player);
        }
    }
}
