package com.snowempress;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import twilightforest.TwilightForestMod;

/**
 * Entry point of the addon.
 *
 * <p>Twilight Forest does not ship a public API package, so this addon works the
 * same way most TF addons do: it compiles against TF's own classes (items, blocks,
 * entities, worldgen helpers) and uses Mixin when a TF method has to be changed.
 * The dependency is wired up in {@code build.gradle} through
 * {@code fg.deobf("local:twilightforest:...")}.
 *
 * <p>Note: on Forge 1.20.1 the mod constructor must be no-argument
 * ({@code FMLModContainer} calls {@code getDeclaredConstructor()}); the event bus
 * comes from {@link FMLJavaModLoadingContext}. Constructor injection is a
 * 1.20.2+/NeoForge feature.
 */
@Mod(SnowEmpress.MOD_ID)
public final class SnowEmpress {
    public static final String MOD_ID = "snowempress";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SnowEmpress() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        AddonContent.register(modBus);
        CarryFlightInput.register();

        modBus.addListener(this::commonSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            if (ModList.get().isLoaded(TwilightForestMod.ID)) {
                LOGGER.info("[{}] Twilight Forest {} detected, addon content enabled.",
                        MOD_ID, ModList.get().getModContainerById(TwilightForestMod.ID)
                                .map(c -> c.getModInfo().getVersion().toString())
                                .orElse("?"));
            }
        });
    }
}
