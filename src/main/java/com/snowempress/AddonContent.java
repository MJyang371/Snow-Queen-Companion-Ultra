package com.snowempress;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * All registry objects of the addon. Keeping registration in one place makes it
 * easy to see what the mod adds and to extend it later.
 */
public final class AddonContent {
    private AddonContent() {
    }

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, SnowEmpress.MOD_ID);
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, SnowEmpress.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SnowEmpress.MOD_ID);

    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, SnowEmpress.MOD_ID);
    private static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, SnowEmpress.MOD_ID);

    static final RegistryObject<SoundEvent> SILVER_BELL_RING = SOUNDS.register("silver_bell_ring",
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(SnowEmpress.MOD_ID, "silver_bell_ring")));

    public static final RegistryObject<Item> SILVER_BELL = ITEMS.register("silver_bell",
            () -> new SilverBellItem(new Item.Properties().stacksTo(1).fireResistant()));

    public static final RegistryObject<EntityType<SummonedIceCrystal>> SUMMONED_ICE_CRYSTAL =
            ENTITIES.register("summoned_ice_crystal", () -> EntityType.Builder
                    .of(SummonedIceCrystal::new, MobCategory.CREATURE)
                    .sized(0.6F, 1.8F)
                    .noSave()
                    .build("snowempress:summoned_ice_crystal"));

    public static final RegistryObject<EntityType<IceSpikeEntity>> ICE_SPIKE =
            ENTITIES.register("ice_spike", () -> EntityType.Builder
                    .<IceSpikeEntity>of(IceSpikeEntity::new, MobCategory.MISC)
                    .sized(0.22F, 0.22F)
                    .clientTrackingRange(6)
                    .updateInterval(2)
                    .build("snowempress:ice_spike"));

    public static final RegistryObject<EntityType<SnowEmpressEntity>> SNOW_EMPRESS =
            ENTITIES.register("snow_empress", () -> EntityType.Builder
                    .of(SnowEmpressEntity::new, MobCategory.CREATURE)
                    .sized(0.7F, 2.2F)
                    .updateInterval(1)
                    .build("snowempress:snow_empress"));

    public static final RegistryObject<Item> SNOW_EMPRESS_SPAWN_EGG =
            ITEMS.register("snow_empress_spawn_egg",
                    () -> new SnowEmpressSpawnEgg(SNOW_EMPRESS, new Item.Properties()));

    // ------------------------------------------------------------------
    // Blocks
    // ------------------------------------------------------------------
    public static final RegistryObject<Block> PERMAFROST_BLOCK = BLOCKS.register("permafrost_block",
            () -> new Block(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .strength(3.0F, 6.0F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()));

    // ------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------
    public static final RegistryObject<Item> FROST_SHARD = ITEMS.register("frost_shard",
            () -> new Item(new Item.Properties()));

    public static final RegistryObject<Item> PERMAFROST_BLOCK_ITEM = ITEMS.register("permafrost_block",
            () -> new BlockItem(PERMAFROST_BLOCK.get(), new Item.Properties()));

    // ------------------------------------------------------------------
    // Creative tab
    // ------------------------------------------------------------------
    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + SnowEmpress.MOD_ID))
                    .icon(() -> new ItemStack(FROST_SHARD.get()))
                    .displayItems((params, output) -> {
                        output.accept(FROST_SHARD.get());
                        output.accept(PERMAFROST_BLOCK_ITEM.get());
                        output.accept(SNOW_EMPRESS_SPAWN_EGG.get());
                        output.accept(SILVER_BELL.get());
                    })
                    .build());

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
        SOUNDS.register(modBus);
        modBus.addListener(AddonContent::registerAttributes);
        ITEMS.register(modBus);
        BLOCKS.register(modBus);
        TABS.register(modBus);
    }

    private static void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(SUMMONED_ICE_CRYSTAL.get(), SummonedIceCrystal.attributes().build());
        event.put(SNOW_EMPRESS.get(), SnowEmpressEntity.attributes().build());
    }
}
