package com.snowempress.mixin;

import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Placeholder mixin that proves the Mixin pipeline works end to end
 * (annotation processor -> refmap -> MANIFEST MixinConfigs -> runtime apply).
 *
 * <p>It injects a no-op at the head of a hot vanilla method; delete it as soon as
 * a real mixin exists. Mixins against Twilight Forest itself follow the same
 * shape, e.g. {@code @Mixin(TFPortalBlock.class)} with
 * {@code method = "m_6227_"}-free official names, because this project compiles
 * against official mappings and FG generates the SRG refmap for you.
 */
@Mixin(ItemStack.class)
public class ItemStackMixin {
    @Inject(method = "getMaxStackSize", at = @At("HEAD"))
    private void snowempress$exampleHook(CallbackInfoReturnable<Integer> cir) {
        // Intentionally empty: the injection point itself is the smoke test.
    }
}
