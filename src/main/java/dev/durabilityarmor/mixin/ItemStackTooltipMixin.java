package dev.durabilityarmor.mixin;

import dev.durabilityarmor.WornArmorTooltip;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TooltipDisplay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the red "wear" lines to an item's tooltip.
 *
 * <p>Vanilla builds the attribute lines in the private
 * {@code ItemStack#addAttributeTooltips(Consumer, TooltipDisplay, Player)}. Injecting at its
 * {@code TAIL} puts our lines directly below the piece's own {@code +N Armor} / {@code +N Armor
 * Toughness} lines, and — because that method returns early when
 * {@code TooltipDisplay#shows(DataComponents.ATTRIBUTE_MODIFIERS)} is false — the extra lines appear
 * exactly when vanilla shows the attribute lines and stay away when the player hid them.</p>
 *
 * <p>The method is not cancelled and nothing else in the tooltip pipeline is touched.</p>
 */
@Mixin(ItemStack.class)
public abstract class ItemStackTooltipMixin {

    @Inject(
            method = "addAttributeTooltips(Ljava/util/function/Consumer;Lnet/minecraft/world/item/component/TooltipDisplay;Lnet/minecraft/world/entity/player/Player;)V",
            at = @At("TAIL"))
    private void durabilityarmor$appendWearTooltip(
            Consumer<Component> sink, TooltipDisplay display, Player player, CallbackInfo ci) {
        WornArmorTooltip.appendTo((ItemStack) (Object) this, sink);
    }
}
