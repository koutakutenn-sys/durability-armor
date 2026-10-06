package dev.durabilityarmor.test.mixin;

import com.llamalad7.mixinextras.injector.ModifyReceiver;
import dev.durabilityarmor.test.RearmProbe;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.util.function.BiConsumer;

/**
 * Runtime stand-in for rearm 2.5.6's use of MixinExtras on the same INVOKE {@code durability_armor}
 * hooks.
 *
 * <p>rearm replaces <em>operand 0</em> (the {@code ItemAttributeModifiers} receiver) of
 * {@code ItemAttributeModifiers.forEach(EquipmentSlot, BiConsumer)}; our hook modifies operands 1/2
 * (the {@code BiConsumer} argument). Both injectors therefore target the same instruction, and the
 * reviewer flagged the coexistence as the single biggest unverified assumption. This probe mirrors
 * rearm's shape - and appends an ARMOR_TOUGHNESS modifier plus a non-armour ATTACK_DAMAGE modifier -
 * so the suite can assert at runtime that the base component's ARMOR value is still scaled exactly
 * once, that the receiver's appended armour toughness is scaled exactly once (what rearm's real
 * armours rely on), and that the appended non-armour attribute is left alone.</p>
 *
 * <p>Inert unless {@link RearmProbe} is armed for one exact stack instance.</p>
 */
@Mixin(ItemStack.class)
public abstract class RearmStyleReceiverProbeMixin {

    @ModifyReceiver(
            method = "forEachModifier(Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/component/ItemAttributeModifiers;forEach(Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V"))
    private ItemAttributeModifiers da$replaceReceiverLikeRearm(
            ItemAttributeModifiers receiver,
            EquipmentSlot slot,
            BiConsumer<Holder<Attribute>, AttributeModifier> consumer) {
        if (!RearmProbe.shouldReplace((ItemStack) (Object) this)) {
            return receiver;
        }
        return receiver
                .withModifierAdded(Attributes.ARMOR_TOUGHNESS, RearmProbe.toughnessModifier(), EquipmentSlotGroup.CHEST)
                .withModifierAdded(Attributes.ATTACK_DAMAGE, RearmProbe.attackDamageModifier(), EquipmentSlotGroup.CHEST);
    }
}
