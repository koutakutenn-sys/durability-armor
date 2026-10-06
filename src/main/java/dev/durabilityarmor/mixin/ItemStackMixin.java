package dev.durabilityarmor.mixin;

import dev.durabilityarmor.ArmorDurabilityScaling;
import java.util.function.BiConsumer;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Single hook of the mod: inside {@code ItemStack#forEachModifier(EquipmentSlot, BiConsumer)} the
 * consumer argument of the two dispatch calls is replaced by {@link ArmorDurabilityScaling#wrap}, so
 * every {@code (attribute, modifier)} pair that reaches the caller is scaled by this stack's
 * remaining durability.
 *
 * <p>The two dispatch calls in the vanilla body are:</p>
 * <ul>
 *   <li>{@code ItemAttributeModifiers.forEach(EquipmentSlot, BiConsumer)} – declared parameters
 *       {@code [0]=EquipmentSlot}, so the consumer is {@code index = 1};</li>
 *   <li>{@code EnchantmentHelper.forEachModifier(ItemStack, EquipmentSlot, BiConsumer)} – declared
 *       parameters {@code [0]=ItemStack}, {@code [1]=EquipmentSlot}, so the consumer is
 *       {@code index = 2}.</li>
 * </ul>
 *
 * <h2>Why the dispatch arguments, and not the method parameter</h2>
 * <p>Replacing <em>the method's own {@code BiConsumer} parameter</em> (e.g.
 * {@code @ModifyVariable(argsOnly = true, at = HEAD)}) is not safe against other mods that re-enter
 * this method. Real example: elytraslot 3.0.0
 * ({@code com.warwa.elytraslot.mixin.ItemStackModifierMixin}) injects at {@code HEAD} of the very
 * same method and, for a glider in the {@code BODY} slot, {@code ci.cancel()}s and then calls
 * {@code self.forEachModifier(EquipmentSlot.CHEST, (attr, mod) -> consumer.accept(attr, new AttributeModifier(...)))}.
 * The inner lambda is a <em>fresh</em> object capturing the consumer it received, so an
 * "am I already wrapped?" marker or {@code instanceof} check on the consumer cannot detect that
 * nesting either. Had our wrapper been substituted into the parameter, the inner call would receive
 * it and wrap it a second time – {@code multiplier²} for ARMOR/ARMOR_TOUGHNESS.</p>
 *
 * <p>Attaching to the two dispatch calls instead makes the wrapper follow <em>method-body
 * execution</em>, so exactly one wrapping happens per executed body:</p>
 * <ul>
 *   <li><b>elytraslot path (outer call cancelled):</b> the foreign {@code HEAD} inject cancels before
 *       any body instruction executes, so neither {@code @ModifyArg} of the outer invocation runs.
 *       The inner {@code forEachModifier(CHEST, freshLambda)} then executes its own body, and each of
 *       its two dispatch calls is wrapped once. Total: scaled once.</li>
 *   <li><b>normal path (nothing cancels):</b> the body executes and both dispatch calls receive the
 *       wrapped consumer. The method parameter is never modified and no caller is affected. Total:
 *       scaled once.</li>
 * </ul>
 * <p>These are the only two orders in which the two mixins can interact, and in both the amount is
 * multiplied exactly once. No marker, no static state and no cancel is needed.</p>
 *
 * <p>Nothing else is touched: the method is not cancelled, the
 * {@code forEachModifier(EquipmentSlotGroup, TriConsumer)} overload is untouched, no item component is
 * written, no extra modifier is added, and {@link ArmorDurabilityScaling#scale} keeps
 * {@code modifier.id()} unchanged – the critical invariant, because vanilla adds and removes
 * equipment modifiers by id ({@code LivingEntity.collectEquipmentChanges},
 * {@code stopLocationBasedEffects}) through this same funnel.</p>
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {

    /** {@code ItemAttributeModifiers.forEach(EquipmentSlot, BiConsumer)} – consumer is argument 1. */
    @ModifyArg(
            method = "forEachModifier(Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/component/ItemAttributeModifiers;forEach(Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V"),
            index = 1)
    private BiConsumer<Holder<Attribute>, AttributeModifier> durabilityarmor$scaleItemAttributeModifiers(
            BiConsumer<Holder<Attribute>, AttributeModifier> consumer) {
        return ArmorDurabilityScaling.wrap((ItemStack) (Object) this, consumer);
    }

    /** {@code EnchantmentHelper.forEachModifier(ItemStack, EquipmentSlot, BiConsumer)} – consumer is argument 2. */
    @ModifyArg(
            method = "forEachModifier(Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;forEachModifier(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V"),
            index = 2)
    private BiConsumer<Holder<Attribute>, AttributeModifier> durabilityarmor$scaleEnchantmentModifiers(
            BiConsumer<Holder<Attribute>, AttributeModifier> consumer) {
        return ArmorDurabilityScaling.wrap((ItemStack) (Object) this, consumer);
    }
}
