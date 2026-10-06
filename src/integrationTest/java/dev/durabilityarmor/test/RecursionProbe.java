package dev.durabilityarmor.test;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;

import java.util.function.BiConsumer;

/**
 * State for the elytraslot P1-1 recursion probe.
 *
 * <p>{@code ElytraSlotRecursionProbeMixin} reproduces elytraslot 3.0.0's behaviour on the very method
 * {@code durability_armor} hooks: an {@code @Inject(HEAD, cancellable)} sibling that reads the
 * method's own {@code BiConsumer} parameter, cancels for the {@code BODY} slot and re-enters
 * {@code self.forEachModifier(CHEST, lambda-forwarding-that-consumer)}.</p>
 *
 * <p>The probe is inert unless explicitly armed with a specific {@link ItemStack} instance, so it
 * cannot perturb any other check in the suite. Kept out of the mixin class on purpose: static fields
 * in a {@code @Mixin} class belong to the target class.</p>
 */
public final class RecursionProbe {
    private static ItemStack armedStack;
    private static BiConsumer<Holder<Attribute>, AttributeModifier> armedConsumer;
    private static int redirects;
    private static boolean sawOriginalConsumer;

    private RecursionProbe() {
    }

    /** Arms the probe for exactly one stack instance and one expected consumer identity. */
    public static void arm(ItemStack stack, BiConsumer<Holder<Attribute>, AttributeModifier> consumer) {
        armedStack = stack;
        armedConsumer = consumer;
        redirects = 0;
        sawOriginalConsumer = false;
    }

    public static void disarm() {
        armedStack = null;
        armedConsumer = null;
    }

    /** Number of redirects observed since {@link #arm}. */
    public static int redirects() {
        return redirects;
    }

    /** True when the sibling injector received the caller's original consumer (not a wrapper). */
    public static boolean sawOriginalConsumer() {
        return sawOriginalConsumer;
    }

    /**
     * Called from the probe mixin's {@code @Inject(HEAD)} handler with the method's own consumer
     * parameter, before any body instruction runs.
     */
    public static boolean shouldRedirect(
            EquipmentSlot slot,
            ItemStack stack,
            BiConsumer<Holder<Attribute>, AttributeModifier> consumer) {
        if (armedStack == null || slot != EquipmentSlot.BODY || stack != armedStack) {
            return false;
        }
        redirects++;
        if (consumer == armedConsumer) {
            sawOriginalConsumer = true;
        }
        return true;
    }
}
