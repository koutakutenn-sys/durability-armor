package dev.durabilityarmor;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/**
 * Builds the red "wear" tooltip lines that tell the player how much protection a piece has already
 * lost because of its missing durability.
 *
 * <p>Vanilla prints the piece's own attribute lines (for example {@code +8 Armor}) from the item's
 * attribute component, which this mod never edits. These extra red lines sit directly below them and
 * report the part that is being lost at runtime:</p>
 *
 * <pre>
 *   +8 Armor
 *   +2 Armor Toughness
 *   Wear: -2 Armor              (red, added by this mod)
 *   Wear: -0.5 Armor Toughness  (red, added by this mod)
 * </pre>
 *
 * <h2>How the reported number is derived</h2>
 * <p>The lost amount is not recomputed with a second formula. For every attribute entry of the stack
 * it is measured directly against the runtime scaling:</p>
 *
 * <pre>
 *   lost = modifier.amount() - ArmorDurabilityScaling.scale(stack, attribute, modifier).amount()
 * </pre>
 *
 * <p>That makes the tooltip and the live attribute value impossible to disagree: an entry the
 * scaling leaves alone contributes exactly {@code 0} lost, and therefore prints no line. This is why
 * knockback resistance — deliberately not scaled by this mod — normally reports nothing, while the
 * mechanism already supports it should that ever change.</p>
 *
 * <p>A line is emitted only when its loss is strictly positive, so a pristine piece shows nothing
 * extra. The numbers use vanilla's own {@code ATTRIBUTE_MODIFIER_FORMAT} and the vanilla red style, so
 * they line up with the blue attribute lines above them.</p>
 */
public final class WornArmorTooltip {

    /** Translation keys; see {@code assets/durability_armor/lang/*.json}. */
    public static final String WORN_ARMOR_KEY = "tooltip.durability_armor.worn_armor";

    public static final String WORN_TOUGHNESS_KEY = "tooltip.durability_armor.worn_toughness";

    public static final String WORN_KNOCKBACK_KEY = "tooltip.durability_armor.worn_knockback_resistance";

    /** Indexes into the {@link #losses(ItemStack)} result. */
    public static final int LOSS_ARMOR = 0;

    public static final int LOSS_TOUGHNESS = 1;

    public static final int LOSS_KNOCKBACK_RESISTANCE = 2;

    private WornArmorTooltip() {
    }

    /**
     * Appends the wear lines for {@code stack} to {@code sink}. Emits nothing for a pristine stack,
     * an empty stack or a stack that carries no scaled contribution. Never throws.
     */
    public static void appendTo(ItemStack stack, Consumer<Component> sink) {
        if (stack == null || sink == null || stack.isEmpty()) {
            return;
        }
        double multiplier = ArmorDurabilityScaling.multiplier(stack);
        if (multiplier >= 1.0) {
            // Pristine (or not damageable): nothing has been lost yet.
            return;
        }
        double[] lost = losses(stack);
        if (lost[LOSS_ARMOR] > 0.0) {
            sink.accept(line(WORN_ARMOR_KEY, lost[LOSS_ARMOR]));
        }
        if (lost[LOSS_TOUGHNESS] > 0.0) {
            sink.accept(line(WORN_TOUGHNESS_KEY, lost[LOSS_TOUGHNESS]));
        }
        if (lost[LOSS_KNOCKBACK_RESISTANCE] > 0.0) {
            sink.accept(line(WORN_KNOCKBACK_KEY, lost[LOSS_KNOCKBACK_RESISTANCE]));
        }
    }

    /**
     * How much armor, armor toughness and knockback resistance this stack loses to its missing
     * durability, measured against {@link ArmorDurabilityScaling#scale} so the reported numbers are by
     * construction the ones the runtime removes.
     *
     * @return {@code {lost armor, lost armor toughness, lost knockback resistance}}
     */
    public static double[] losses(ItemStack stack) {
        double[] lost = new double[3];
        if (stack == null || stack.isEmpty()) {
            return lost;
        }
        if (ArmorDurabilityScaling.multiplier(stack) >= 1.0) {
            return lost;
        }
        ItemAttributeModifiers modifiers =
                stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        for (ItemAttributeModifiers.Entry entry : modifiers.modifiers()) {
            AttributeModifier modifier = entry.modifier();
            AttributeModifier scaled =
                    ArmorDurabilityScaling.scale(stack, entry.attribute(), modifier);
            if (scaled == null) {
                continue;
            }
            double loss = modifier.amount() - scaled.amount();
            if (loss <= 0.0) {
                continue;
            }
            Holder<Attribute> attribute = entry.attribute();
            if (ArmorDurabilityScaling.isArmor(attribute)) {
                lost[LOSS_ARMOR] += loss;
            } else if (ArmorDurabilityScaling.isArmorToughness(attribute)) {
                lost[LOSS_TOUGHNESS] += loss;
            } else if (ArmorDurabilityScaling.sameAttribute(attribute, Attributes.KNOCKBACK_RESISTANCE)) {
                lost[LOSS_KNOCKBACK_RESISTANCE] += loss;
            }
        }
        return lost;
    }

    /**
     * The exact text of one wear line, using the same number format as vanilla's own attribute lines
     * and the vanilla red style.
     */
    public static Component line(String translationKey, double lost) {
        return Component.translatable(translationKey, ItemAttributeModifiers.ATTRIBUTE_MODIFIER_FORMAT.format(lost))
                .withStyle(ChatFormatting.RED);
    }
}
