package com.snowempress;

import net.minecraft.world.item.ItemStack;
import twilightforest.TwilightForestMod;
import twilightforest.init.TFItems;

/**
 * Small helpers that touch Twilight Forest classes directly.
 *
 * <p>This class exists to prove that the TF dependency is wired up correctly:
 * if {@code compileJava} accepts these references, the jar in {@code libs/} was
 * remapped properly by {@code fg.deobf()} and TF symbols autocomplete in the IDE.
 */
public final class TFCompat {
    /** Twilight Forest's mod id ("twilightforest"). */
    public static final String TF_ID = TwilightForestMod.ID;

    private TFCompat() {
    }

    /** True when the stack comes from the Twilight Forest namespace. */
    public static boolean isTwilightItem(ItemStack stack) {
        return stack.getItemHolder().unwrapKey()
                .map(key -> key.location().getNamespace().equals(TF_ID))
                .orElse(false);
    }

    /** Example of reading a TF registry object: the Naga Scale item. */
    public static ItemStack nagaScale() {
        return new ItemStack(TFItems.NAGA_SCALE.get());
    }

    /** Example of reading a TF material item: the Ironwood Ingot. */
    public static ItemStack ironwoodIngot() {
        return new ItemStack(TFItems.IRONWOOD_INGOT.get());
    }
}
