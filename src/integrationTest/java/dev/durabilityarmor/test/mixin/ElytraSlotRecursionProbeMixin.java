package dev.durabilityarmor.test.mixin;

import dev.durabilityarmor.test.RecursionProbe;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.BiConsumer;

/**
 * Deterministic, in-harness stand-in for the elytraslot 3.0.0 P1-1 interop defect.
 *
 * <p>elytraslot 3.0.0 injects {@code @Inject(HEAD, cancellable)} into the same method
 * {@code durability_armor} hooks ({@code ItemStack#forEachModifier(EquipmentSlot, BiConsumer)}),
 * and for a glider in the {@code BODY} slot it cancels and re-enters
 * {@code self.forEachModifier(CHEST, freshLambda)} where {@code freshLambda} forwards into the
 * consumer it received <em>as the method parameter</em>.</p>
 *
 * <p>That is the exact shape reproduced here, gated by {@link RecursionProbe#shouldRedirect} so it
 * is inert unless the suite arms it for one specific stack. If the durability hook substitutes the
 * method parameter (instead of the arguments of the two dispatch calls inside the body), the
 * forwarded consumer is already wrapped and the nested call wraps it again – multiplier squared.</p>
 */
@Mixin(ItemStack.class)
public abstract class ElytraSlotRecursionProbeMixin {

    @Inject(
            method = "forEachModifier(Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void da$reenterLikeElytraSlot(
            EquipmentSlot slot,
            BiConsumer<Holder<Attribute>, AttributeModifier> consumer,
            CallbackInfo ci) {
        ItemStack self = (ItemStack) (Object) this;
        if (!RecursionProbe.shouldRedirect(slot, self, consumer)) {
            return;
        }
        ci.cancel();
        // elytraslot's forwarding lambda: a fresh consumer that re-enters with the consumer it saw.
        self.forEachModifier(EquipmentSlot.CHEST, (attribute, modifier) -> consumer.accept(attribute, modifier));
    }
}
