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
     * <p>Returns {@code 1.0} (i.e. "no change") for {@code null}, empty, stacks without a
     * {@code MAX_DAMAGE} component, stacks with {@code maxDamage <= 0}, and stacks at full (or above
     * full) durability. Returns {@code 0.0} for a broken stack (remaining durability {@code <= 0}).
     * Never throws.</p>
     *
     * <p>There is deliberately <em>no</em> {@code UNBREAKABLE} exemption. The requirement is literally
     * {@code r = remaining durability / max durability}, and an unbreakable stack still carries its
     * clamped {@code DAMAGE} component – {@code getDamageValue()} keeps returning it – so an item that
     * was damaged before or after being made unbreakable must lose protection accordingly. The removed
     * short-circuit only hid that case: ordinary unbreakable gear has {@code damage == 0}, which gives
     * {@code remaining >= maxDamage} and therefore {@code 1.0} here anyway, so nothing changes for it.</p>
     */
    public static double multiplier(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 1.0;
        }
        // No MAX_DAMAGE component => the stack cannot wear out, so it is never scaled down.
        if (!stack.has(DataComponents.MAX_DAMAGE)) {
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

    /** True only for {@link Attributes#ARMOR}. */
    public static boolean isArmor(Holder<Attribute> attribute) {
        return sameAttribute(attribute, Attributes.ARMOR);
    }

    /** True only for {@link Attributes#ARMOR_TOUGHNESS}. */
    public static boolean isArmorToughness(Holder<Attribute> attribute) {
        return sameAttribute(attribute, Attributes.ARMOR_TOUGHNESS);
    }

    /**
     * True only for {@link Attributes#ARMOR} and {@link Attributes#ARMOR_TOUGHNESS}. Knockback
     * resistance (and every other attribute) is deliberately left untouched.
     */
    public static boolean scalesAttribute(Holder<Attribute> attribute) {
        return isArmor(attribute) || isArmorToughness(attribute);
    }

    /**
     * Identity comparison that also accepts a different {@link Holder} wrapping the very same
     * {@link Attribute} instance. The resolving path is wrapped defensively so a holder that cannot
     * resolve its value can never throw out of here.
     */
    public static boolean sameAttribute(Holder<Attribute> attribute, Holder<Attribute> expected) {
        if (attribute == null || expected == null) {
            return false;
        }
        // Fast path: the canonical registry holders are what item attribute components carry.
        if (attribute == expected) {
            return true;
        }
        // Robust path: any other Holder wrapping the same Attribute instance still counts.
        try {
            return attribute.value() == expected.value();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Scales a modifier amount for the stack's remaining durability.
     *
     * <p>Returns {@code modifier} unchanged when the attribute is not scaled, when the stack needs no
     * scaling, or when the operation is
     * {@link AttributeModifier.Operation#ADD_MULTIPLIED_TOTAL}. Otherwise returns a new
     * {@link AttributeModifier} with the same id and operation and {@code amount * multiplier}.</p>
     *
     * <h2>Why {@code ADD_MULTIPLIED_TOTAL} is left untouched</h2>
     * <p>Vanilla {@code AttributeInstance} calculates
     * {@code value = (base + Σ ADD_VALUE + base·Σ ADD_MULTIPLIED_BASE) · (1 + Σ ADD_MULTIPLIED_TOTAL)},
     * and {@code base} is 0 for {@link Attributes#ARMOR}/{@link Attributes#ARMOR_TOUGHNESS} on vanilla
     * entities. Scaling only the additive contributions while leaving the multiplicative total at full
     * strength therefore produces exactly {@code originalValue * multiplier} for a single piece: with
     * {@code +8 ADD_VALUE} and {@code +0.5 ADD_MULTIPLIED_TOTAL} at {@code multiplier = 0.75},
     * {@code (8 · 0.75) · 1.5 = 9.0 = 12 · 0.75}; scaling the total factor as well would wrongly give
     * {@code 6 · 1.375 = 8.25}.</p>
     *
     * <p>Residual limitation: an {@code ADD_MULTIPLIED_TOTAL} contribution is a global factor over the
     * whole attribute – it is not owned by one piece and multiplies every other source's contribution
     * too – so it is deliberately kept at full strength. Likewise an attribute's own base value is
     * never scaled by this mod.</p>
     */
    public static AttributeModifier scale(ItemStack stack, Holder<Attribute> attribute, AttributeModifier modifier) {
        if (modifier == null || !scalesAttribute(attribute)) {
            return modifier;
        }
        if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) {
            // A global multiplicative factor: keep it whole, so the net value is
            // (scaled additive contribution) * factor == originalValue * multiplier.
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
     * {@code consumer}. When the stack needs no scaling ({@code null}, empty, no {@code MAX_DAMAGE},
     * {@code maxDamage <= 0}, or full/above-full durability – i.e. {@code multiplier(stack) == 1.0})
     * the <em>original</em> consumer is returned untouched, so the common case allocates nothing and
     * behaves exactly like vanilla.</p>
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
