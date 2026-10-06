package dev.durabilityarmor;

import java.util.function.BiConsumer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

/**
 * Pure, side-effect-free math for scaling armor attributes by the remaining durability of the
 * equipped stack.
 *
 * <p>Contract (frozen in {@code docs/DESIGN.md}):</p>
 *
 * <pre>
 *   r          = remaining durability / max durability
 *   multiplier = 1 - (1 - r)^2
 * </pre>
 *
 * <p>Everything here is a pure function of its arguments: no static mutable state, no caching and no
 * modification of item components. {@link AttributeModifier} is an immutable record, so a scaled
 * value is always a <em>new</em> instance with the original id and operation – vanilla removes and
 * re-adds modifiers by id, so nothing can double-stack and nothing is ever persisted.</p>
 */
public final class ArmorDurabilityScaling {

    private ArmorDurabilityScaling() {
    }

    /**
     * Durability factor for a single stack: {@code 1 - (1 - r)^2} with
     * {@code r = (maxDamage - damage) / maxDamage}.
     *
     * <p>Returns {@code 1.0} (i.e. "no change") for {@code null}, empty, undamageable, unbreakable or
     * {@code maxDamage <= 0} stacks, and for stacks that are at full (or above full) durability.
     * Returns {@code 0.0} for a broken stack (remaining durability {@code <= 0}). Never throws.</p>
     */
    public static double multiplier(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 1.0;
        }
        // No MAX_DAMAGE component => the stack cannot wear out, so it is never scaled down.
        if (!stack.has(DataComponents.MAX_DAMAGE)) {
            return 1.0;
        }
        // Unbreakable stacks never lose durability, so their protection must never drop.
        if (stack.has(DataComponents.UNBREAKABLE)) {
            return 1.0;
        }
        int maxDamage = stack.getMaxDamage();
        if (maxDamage <= 0) {
            return 1.0;
        }
        int remaining = maxDamage - stack.getDamageValue();
        if (remaining <= 0) {
            return 0.0;
        }
        if (remaining >= maxDamage) {
            // Full durability, or (defensively) a negative DAMAGE component: clamp r to its valid range.
            return 1.0;
        }
        double r = (double) remaining / (double) maxDamage;
        double deficit = 1.0 - r;
        return 1.0 - deficit * deficit;
    }

    /**
     * True only for {@link Attributes#ARMOR} and {@link Attributes#ARMOR_TOUGHNESS}. Knockback
     * resistance (and every other attribute) is deliberately left untouched.
     */
    public static boolean scalesAttribute(Holder<Attribute> attribute) {
        if (attribute == null) {
            return false;
        }
        // Fast path: the canonical registry holders are what item attribute components carry.
        if (attribute == Attributes.ARMOR || attribute == Attributes.ARMOR_TOUGHNESS) {
            return true;
        }
        // Robust path: any other Holder wrapping the very same Attribute instance still counts.
        // Wrapped defensively so a Holder that cannot resolve its value can never throw out of here.
        try {
            Attribute value = attribute.value();
            return value == Attributes.ARMOR.value() || value == Attributes.ARMOR_TOUGHNESS.value();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Returns {@code modifier} unchanged when the attribute is not scaled or the stack is at full
     * durability; otherwise returns a new {@link AttributeModifier} with the same id and operation
     * and {@code amount * multiplier}.
     */
    public static AttributeModifier scale(ItemStack stack, Holder<Attribute> attribute, AttributeModifier modifier) {
        if (modifier == null || !scalesAttribute(attribute)) {
            return modifier;
        }
        double multiplier = multiplier(stack);
        if (multiplier == 1.0) {
            // Identity matters: callers rely on the untouched instance for the no-change case.
            return modifier;
        }
        return new AttributeModifier(modifier.id(), modifier.amount() * multiplier, modifier.operation());
    }

    /**
     * Wraps a {@code (attribute, modifier)} consumer so that every modifier it receives is first
     * passed through {@link #scale} for the given stack. Used by both injection points of
     * {@code ItemStackMixin} (the {@code ItemAttributeModifiers} dispatch and the
     * {@code EnchantmentHelper} dispatch).
     *
     * <p>Pure and stateless: the returned consumer holds nothing but {@code stack} and
     * {@code consumer}. When the stack needs no scaling ({@code null}, empty, undamageable,
     * unbreakable, full durability – i.e. {@code multiplier(stack) == 1.0}) the <em>original</em>
     * consumer is returned untouched, so the common case allocates nothing and behaves exactly like
     * vanilla.</p>
     *
     * <p>Note that this wrapper is intentionally not idempotent-detecting: it scales whatever it is
     * handed. Idempotence comes from the injector being attached to the two dispatch calls inside the
     * method body rather than to the method parameter, so a re-entrant call into
     * {@code forEachModifier} is wrapped exactly once, by its own body. See
     * {@code ItemStackMixin} for the full reasoning.</p>
     *
     * @return {@code consumer} itself when no scaling can happen, otherwise a wrapper around it
     */
    public static BiConsumer<Holder<Attribute>, AttributeModifier> wrap(
            ItemStack stack, BiConsumer<Holder<Attribute>, AttributeModifier> consumer) {
        if (consumer == null) {
            return null;
        }
        if (multiplier(stack) == 1.0) {
            // Nothing to scale: hand the caller the untouched consumer (vanilla behaviour, no allocation).
            return consumer;
        }
        return (attribute, modifier) -> consumer.accept(attribute, scale(stack, attribute, modifier));
    }
}
