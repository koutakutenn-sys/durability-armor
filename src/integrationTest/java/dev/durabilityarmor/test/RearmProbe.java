package dev.durabilityarmor.test;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;

/**
 * State for the rearm 2.5.6 coexistence probe (gap G1 of the review).
 *
 * <p>rearm uses a MixinExtras {@code @ModifyReceiver} on the very INVOKE our two {@code @ModifyArg}s
 * target ({@code ItemAttributeModifiers.forEach(EquipmentSlot, BiConsumer)}, operand 0 vs operands
 * 1/2). {@code RearmStyleReceiverProbeMixin} mirrors that shape by returning a receiver with extra
 * attribute modifiers. The probe is inert unless armed for one exact {@link ItemStack} instance, so
 * it cannot perturb the other checks.</p>
 */
public final class RearmProbe {
    /** Id of the ARMOR_TOUGHNESS modifier the imitation appends (namespace is not the mod's). */
    public static final Identifier TOUGHNESS_ID =
            Identifier.fromNamespaceAndPath("da_test", "rearm_style_armor_toughness");
    /** Id of the non-armour attribute the imitation appends, to prove it stays untouched. */
    public static final Identifier ATTACK_DAMAGE_ID =
            Identifier.fromNamespaceAndPath("da_test", "rearm_style_attack_damage");

    public static final double TOUGHNESS_AMOUNT = 4.0;
    public static final double ATTACK_DAMAGE_AMOUNT = 2.0;

    private static ItemStack armedStack;
    private static int replacements;

    private RearmProbe() {
    }

    /** Arms the probe for exactly one stack instance. */
    public static void arm(ItemStack stack) {
        armedStack = stack;
        replacements = 0;
    }

    public static void disarm() {
        armedStack = null;
    }

    /** Number of receiver replacements observed since {@link #arm}. */
    public static int replacements() {
        return replacements;
    }

    /** Called from the probe mixin with the receiver the INVOKE would have used. */
    public static boolean shouldReplace(ItemStack stack) {
        if (armedStack == null || stack != armedStack) {
            return false;
        }
        replacements++;
        return true;
    }

    public static AttributeModifier toughnessModifier() {
        return new AttributeModifier(TOUGHNESS_ID, TOUGHNESS_AMOUNT, AttributeModifier.Operation.ADD_VALUE);
    }

    public static AttributeModifier attackDamageModifier() {
        return new AttributeModifier(ATTACK_DAMAGE_ID, ATTACK_DAMAGE_AMOUNT, AttributeModifier.Operation.ADD_VALUE);
    }
}
