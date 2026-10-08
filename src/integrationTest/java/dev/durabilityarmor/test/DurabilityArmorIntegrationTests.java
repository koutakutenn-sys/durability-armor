package dev.durabilityarmor.test;

import dev.durabilityarmor.ArmorDurabilityScaling;
import dev.durabilityarmor.DurabilityArmor;
import dev.durabilityarmor.WornArmorTooltip;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.Unit;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.LevelBasedValue;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.apache.commons.lang3.function.TriConsumer;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Server-side integration suite for {@code durability_armor}.
 *
 * <p>Every check goes through {@link #require(boolean, String)}. Sections are isolated so one
 * broken expectation does not hide the others, but the result file starts with {@code PASS}
 * only when every single check passed; otherwise it starts with {@code FAIL} and the Gradle
 * task fails the build.
 *
 * <p>The suite only reads the frozen contract from {@code docs/DESIGN.md}: the formula
 * {@code 1 - (1 - r)^2}, {@code ArmorDurabilityScaling.multiplier/scalesAttribute/scale} and
 * the vanilla {@code ItemStack.forEachModifier -> AttributeInstance transient modifier} funnel.
 * Expected values are always derived from the item's own {@code ATTRIBUTE_MODIFIERS} component,
 * never hardcoded.
 */
public final class DurabilityArmorIntegrationTests {
    private static final double EPS = 1e-9;
    private static final String MOD_NAMESPACE = "durability_armor";
    /** Namespace for test-injected modifiers; deliberately not under the mod namespace. */
    private static final String TEST_NAMESPACE = "da_test";
    /** Compiled hook class, scanned with ASM (never loaded, see {@link #scanMixinClass}). */
    private static final String HOOK_MIXIN_CLASS = "dev/durabilityarmor/mixin/ItemStackMixin.class";
    /** Compiled rearm-shape probe class (G1). */
    private static final String PROBE_MIXIN_CLASS =
            "dev/durabilityarmor/test/mixin/RearmStyleReceiverProbeMixin.class";
    /**
     * ARMOR amount granted by {@code data/da_test/enchantment/armor_probe.json} (GAP-A). Kept here as
     * the single source of truth next to the datapack file it mirrors.
     */
    private static final double ENCHANT_ARMOR_AMOUNT = 1.5;

    private static int checks;
    private static final List<String> FAILURES = new ArrayList<>();
    private static final StringBuilder REPORT = new StringBuilder();

    private DurabilityArmorIntegrationTests() {
    }

    public static void run(MinecraftServer server) {
        try {
            section("A formula", DurabilityArmorIntegrationTests::formulaChecks);
            section("B live entity", () -> liveEntityChecks(server.overworld()));
            section("C anti double stacking", () -> antiDoubleStackingChecks(server.overworld()));
            section("D re-entry and desync", () -> reentryChecks(server.overworld()));
            section("R1 elytraslot recursion", DurabilityArmorIntegrationTests::recursionChecks);
            section("R2 equip lifecycle", () -> lifecycleChecks(server.overworld()));
            section("R3 hand slot untouched", () -> handSlotChecks(server.overworld()));
            section("R4 persistence", () -> persistenceChecks(server.overworld()));
            section("R5 edge cases", () -> edgeCaseChecks(server.overworld()));
            section("G1 rearm ModifyReceiver coexistence", DurabilityArmorIntegrationTests::rearmCoexistenceChecks);
            section("G2 group overload stays raw", DurabilityArmorIntegrationTests::groupOverloadChecks);
            section("G3 Holder.value fallback", DurabilityArmorIntegrationTests::holderFallbackChecks);
            section("G4 id based removal", () -> idRemovalChecks(server.overworld()));
            section("P1-2 operation aware scaling", () -> operationAwareScalingChecks(server.overworld()));
            section("GAP-A enchantment dispatch", () -> enchantmentDispatchChecks(server.overworld()));
            section("T1 tooltip wear lines", () -> tooltipChecks(server.overworld()));
        } catch (Throwable fatal) {
            FAILURES.add("fatal: " + fatal);
            REPORT.append("FAILED fatal: ").append(fatal).append('\n');
            fatal.printStackTrace();
        }
        try {
            if (FAILURES.isEmpty()) {
                Files.writeString(Path.of("test-result.txt"), "PASS: " + checks + " checks\n" + REPORT);
                DurabilityArmor.LOGGER.info("DURABILITY ARMOR TESTS PASSED: {} checks", checks);
            } else {
                Files.writeString(Path.of("test-result.txt"), "FAIL\n" + REPORT);
                DurabilityArmor.LOGGER.error("DURABILITY ARMOR TESTS FAILED: {} passed, {} failed",
                        checks, FAILURES.size());
                for (String failure : FAILURES) {
                    DurabilityArmor.LOGGER.error("  failed: {}", failure);
                }
            }
        } catch (Exception writeError) {
            DurabilityArmor.LOGGER.error("could not write test-result.txt", writeError);
        }
    }

    // ------------------------------------------------------------------ A formula

    private static void formulaChecks() {
        require(ArmorDurabilityScaling.multiplier(null) == 1.0,
                "A1 multiplier(null) == 1.0 (never throws)");
        require(ArmorDurabilityScaling.multiplier(ItemStack.EMPTY) == 1.0,
                "A2 multiplier(empty stack) == 1.0");
        require(ArmorDurabilityScaling.multiplier(new ItemStack(Items.STICK)) == 1.0,
                "A3 multiplier(no MAX_DAMAGE component) == 1.0");

        ItemStack pristine = new ItemStack(Items.DIAMOND_CHESTPLATE);
        int max = pristine.getMaxDamage();
        require(max > 0, "A4 fixture: diamond chestplate has a positive max damage (" + max + ")");
        require(pristine.get(DataComponents.ATTRIBUTE_MODIFIERS) != null,
                "A5 fixture: vanilla armor exposes an ATTRIBUTE_MODIFIERS component");
        require(ArmorDurabilityScaling.multiplier(pristine) == 1.0,
                "A6 undamaged stack multiplier == 1.0");
        require(near(ArmorDurabilityScaling.multiplier(pristine), closedForm(1.0)),
                "A7 undamaged matches the closed form 1-(1-r)^2 at r=1");

        ItemStack half = new ItemStack(Items.DIAMOND_CHESTPLATE);
        half.setDamageValue(max / 2);
        require(remainingRatio(half) == 0.5, "A8 fixture: r == 0.5 exactly");
        require(near(ArmorDurabilityScaling.multiplier(half), closedForm(0.5)),
                "A9 r=0.5 == closed form 1-(1-r)^2");
        require(near(ArmorDurabilityScaling.multiplier(half), 0.75),
                "A10 r=0.5 yields the documented 0.75");

        ItemStack quarter = new ItemStack(Items.DIAMOND_CHESTPLATE);
        quarter.setDamageValue(max - max / 4);
        require(remainingRatio(quarter) == 0.25, "A11 fixture: r == 0.25 exactly");
        require(near(ArmorDurabilityScaling.multiplier(quarter), closedForm(0.25)),
                "A12 r=0.25 == closed form 1-(1-r)^2");
        require(near(ArmorDurabilityScaling.multiplier(quarter), 0.4375),
                "A13 r=0.25 yields the documented 0.4375");

        ItemStack threeQuarter = new ItemStack(Items.DIAMOND_CHESTPLATE);
        threeQuarter.setDamageValue(max / 4);
        require(near(ArmorDurabilityScaling.multiplier(threeQuarter), closedForm(remainingRatio(threeQuarter))),
                "A14 r=0.75 == closed form (r=" + remainingRatio(threeQuarter) + ")");

        ItemStack tenth = new ItemStack(Items.DIAMOND_CHESTPLATE);
        tenth.setDamageValue((int) Math.round(max * 0.9));
        require(near(ArmorDurabilityScaling.multiplier(tenth), closedForm(remainingRatio(tenth))),
                "A15 r=0.1 == closed form (r=" + remainingRatio(tenth) + ")");

        ItemStack broken = new ItemStack(Items.DIAMOND_CHESTPLATE);
        broken.setDamageValue(max);
        require(broken.isBroken(), "A16 fixture: fully damaged stack is broken");
        require(ArmorDurabilityScaling.multiplier(broken) == 0.0,
                "A17 broken stack multiplier == 0.0");

        ItemStack overDamaged = new ItemStack(Items.DIAMOND_CHESTPLATE);
        overDamaged.setDamageValue(max + 100);
        require(overDamaged.getDamageValue() == max, "A18 fixture: over-damage is clamped by vanilla");
        require(ArmorDurabilityScaling.multiplier(overDamaged) == 0.0,
                "A19 over-damaged stack multiplier == 0.0");

        ItemStack negative = new ItemStack(Items.DIAMOND_CHESTPLATE);
        negative.setDamageValue(-5);
        // A20/A21 assert VANILLA behaviour, not one of our branches: ItemStack#setDamageValue writes
        // Mth.clamp(value, 0, maxDamage), so a negative write can never reach ArmorDurabilityScaling.
        // Our own end-point branches are covered by A17/A19 (remaining <= 0 -> 0.0), A24-A26
        // (maxDamage <= 0 -> 1.0) and R5b (broken). Kept as a regression pin on the vanilla clamp.
        require(negative.getDamageValue() == 0,
                "A20 vanilla clamps a negative DAMAGE write to 0 (ItemStack#setDamageValue -> Mth.clamp)");
        require(ArmorDurabilityScaling.multiplier(negative) == 1.0,
                "A21 the vanilla-clamped stack is undamaged, so multiplier == 1.0"
                        + " (our remaining <= 0 branch is covered by A17/A19/R5b)");

        ItemStack unbreakable = new ItemStack(Items.DIAMOND_CHESTPLATE);
        unbreakable.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        unbreakable.setDamageValue(max / 2);
        require(remainingRatio(unbreakable) == 0.5, "A22 fixture: unbreakable stack is at r=0.5");
        // CORRECTED for the frozen P1-1 semantics (UNBREAKABLE short-circuit removed). The requirement
        // is literally r = remaining durability / max durability with no exemption, so an unbreakable
        // stack that still carries a DAMAGE component must scale like any other. This replaces the
        // previous "unbreakable -> 1.0" assertion, which pinned exactly the behaviour Codex rejected
        // ("a passing test cannot prove the user requirement"). Deliberate correction, not a weakening.
        require(near(ArmorDurabilityScaling.multiplier(unbreakable), closedForm(0.5)),
                "A23 CORRECTED (P1-1): unbreakable + damage scales by the formula (r=0.5 -> 0.75), no exemption");

        ItemStack unbreakablePristine = new ItemStack(Items.DIAMOND_CHESTPLATE);
        unbreakablePristine.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        require(ArmorDurabilityScaling.multiplier(unbreakablePristine) == 1.0,
                "A23a unbreakable at damage 0 is still 1.0 (r=1; the removed short-circuit was redundant there)");

        ItemStack unbreakableBroken = new ItemStack(Items.DIAMOND_CHESTPLATE);
        unbreakableBroken.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        unbreakableBroken.setDamageValue(unbreakableBroken.getMaxDamage());
        require(ArmorDurabilityScaling.multiplier(unbreakableBroken) == 0.0,
                "A23b CORRECTED (P1-1): unbreakable with remaining durability 0 is 0.0"
                        + " (the removed short-circuit used to force 1.0 here)");

        ItemStack zeroMax = new ItemStack(Items.STICK);
        zeroMax.set(DataComponents.MAX_DAMAGE, 0);
        require(zeroMax.getMaxDamage() == 0, "A24 fixture: MAX_DAMAGE == 0");
        require(ArmorDurabilityScaling.multiplier(zeroMax) == 1.0,
                "A25 maxDamage == 0 yields 1.0 (no max-damage stack)");

        ItemStack zeroMaxArmor = new ItemStack(Items.DIAMOND_CHESTPLATE);
        zeroMaxArmor.set(DataComponents.MAX_DAMAGE, 0);
        require(ArmorDurabilityScaling.multiplier(zeroMaxArmor) == 1.0,
                "A26 armor with maxDamage == 0 yields 1.0");

        require(ArmorDurabilityScaling.scalesAttribute(Attributes.ARMOR),
                "A27 scalesAttribute(ARMOR) is true");
        require(ArmorDurabilityScaling.scalesAttribute(Attributes.ARMOR_TOUGHNESS),
                "A28 scalesAttribute(ARMOR_TOUGHNESS) is true");
        require(!ArmorDurabilityScaling.scalesAttribute(Attributes.KNOCKBACK_RESISTANCE),
                "A29 scalesAttribute(KNOCKBACK_RESISTANCE) is false");
        require(!ArmorDurabilityScaling.scalesAttribute(Attributes.ATTACK_DAMAGE),
                "A30 scalesAttribute(ATTACK_DAMAGE) is false");

        AttributeModifier armorModifier = new AttributeModifier(
                Identifier.fromNamespaceAndPath("minecraft", "armor.chestplate"),
                8.0, AttributeModifier.Operation.ADD_VALUE);
        AttributeModifier knockback = new AttributeModifier(
                Identifier.fromNamespaceAndPath("minecraft", "test.knockback_resistance"),
                0.25, AttributeModifier.Operation.ADD_VALUE);

        AttributeModifier scaled = ArmorDurabilityScaling.scale(half, Attributes.ARMOR, armorModifier);
        require(scaled.id().equals(armorModifier.id()) && scaled.operation() == armorModifier.operation(),
                "A31 scale keeps the modifier id and operation");
        require(near(scaled.amount(), 8.0 * 0.75),
                "A32 scale multiplies the ARMOR amount by 0.75 at r=0.5");

        AttributeModifier untouched = ArmorDurabilityScaling.scale(half, Attributes.KNOCKBACK_RESISTANCE, knockback);
        require(untouched.equals(knockback),
                "A33 scale leaves a KNOCKBACK_RESISTANCE modifier byte-identical");
        require(ArmorDurabilityScaling.scale(pristine, Attributes.ARMOR, armorModifier).equals(armorModifier),
                "A34 scale at full durability returns an equal modifier");
        require(ArmorDurabilityScaling.scale(broken, Attributes.ARMOR, armorModifier).amount() == 0.0,
                "A35 scale on a broken stack yields amount 0.0");
        AttributeModifier nullStack = ArmorDurabilityScaling.scale(null, Attributes.ARMOR, armorModifier);
        require(nullStack != null && nullStack.equals(armorModifier),
                "A36 scale(null stack) returns the input modifier (never throws)");

        int damageBefore = half.getDamageValue();
        ItemAttributeModifiers componentBefore = half.get(DataComponents.ATTRIBUTE_MODIFIERS);
        double first = ArmorDurabilityScaling.multiplier(half);
        double second = ArmorDurabilityScaling.multiplier(half);
        require(Double.compare(first, second) == 0,
                "A37 multiplier is deterministic across calls (bit-identical)");
        ItemStack halfCopy = half.copy();
        require(Double.compare(ArmorDurabilityScaling.multiplier(halfCopy), first) == 0,
                "A38 multiplier on a deep copy is bit-identical");
        require(Objects.equals(componentBefore, half.get(DataComponents.ATTRIBUTE_MODIFIERS)),
                "A39 multiplier does not modify ATTRIBUTE_MODIFIERS");
        require(half.getDamageValue() == damageBefore,
                "A40 multiplier does not modify the DAMAGE component");
        require(ItemStack.isSameItemSameComponents(half, halfCopy),
                "A41 multiplier leaves the stack content byte-identical");

        ItemStack noCache = new ItemStack(Items.DIAMOND_CHESTPLATE);
        double atFull = ArmorDurabilityScaling.multiplier(noCache);
        noCache.setDamageValue(noCache.getMaxDamage() / 2);
        double atHalf = ArmorDurabilityScaling.multiplier(noCache);
        noCache.setDamageValue(0);
        double backToFull = ArmorDurabilityScaling.multiplier(noCache);
        require(atFull == 1.0 && near(atHalf, 0.75) && backToFull == 1.0,
                "A42 multiplier is not cached: it follows the current damage of the same stack");

        Method multiplier = null;
        try {
            multiplier = ArmorDurabilityScaling.class.getMethod("multiplier", ItemStack.class);
        } catch (NoSuchMethodException ignored) {
            // reported by the check below
        }
        require(multiplier != null && Modifier.isStatic(multiplier.getModifiers())
                        && multiplier.getReturnType() == double.class,
                "A43 multiplier is a static ItemStack -> double function (pure, no entity/level context)");
        require(noNetworkMembers(ArmorDurabilityScaling.class),
                "A44 ArmorDurabilityScaling declares no network-typed members");
    }

    // ------------------------------------------------------------------ B live entity

    private static void liveEntityChecks(ServerLevel level) {
        Equipped pristineEquip = spawn(level, pristinePieces());
        TestArmorStand full = pristineEquip.stand();
        full.tick();

        require(full.getAttributeBaseValue(Attributes.ARMOR) == 0.0,
                "B1 fixture: entity ARMOR base value is 0");
        require(full.getAttributeBaseValue(Attributes.ARMOR_TOUGHNESS) == 0.0,
                "B2 fixture: entity ARMOR_TOUGHNESS base value is 0");

        List<Piece> pieces = pristineEquip.pieces();
        double rawArmor = sumRaw(pieces, Attributes.ARMOR);
        double rawToughness = sumRaw(pieces, Attributes.ARMOR_TOUGHNESS);
        require(rawArmor > 0.0, "B3 fixture: equipped armor has a raw ARMOR contribution (" + rawArmor + ")");
        require(rawToughness > 0.0, "B4 fixture: equipped armor has a raw toughness contribution (" + rawToughness + ")");
        require(allAddValue(pieces), "B5 all equipped armor attribute entries use ADD_VALUE");

        require(near(full.getAttributeValue(Attributes.ARMOR), rawArmor),
                "B6 undamaged ARMOR attribute == unscaled component baseline");
        require(near(full.getAttributeValue(Attributes.ARMOR_TOUGHNESS), rawToughness),
                "B7 undamaged ARMOR_TOUGHNESS attribute == unscaled component baseline");
        require(full.getArmorValue() == (int) Math.floor(full.getAttributeValue(Attributes.ARMOR)),
                "B8 armor bar == floor(ARMOR attribute)");
        require(full.getArmorValue() == (int) Math.floor(rawArmor),
                "B9 undamaged armor bar == floor(unscaled baseline)");

        // One damaged piece: the vanilla equipment-change detector must pick it up on the next tick.
        List<Piece> damagedPieces = pristinePieces();
        ItemStack helmet = damagedPieces.get(0).stack();
        helmet.setDamageValue(helmet.getMaxDamage() / 2);
        Equipped damagedEquip = spawn(level, damagedPieces);
        TestArmorStand worn = damagedEquip.stand();
        worn.tick();

        double helmetMultiplier = ArmorDurabilityScaling.multiplier(helmet);
        require(helmetMultiplier > 0.0 && helmetMultiplier < 1.0,
                "B10 fixture: damaged helmet multiplier in (0,1) (" + helmetMultiplier + ")");
        double expectedArmor = expected(worn, damagedPieces, Attributes.ARMOR);
        double expectedToughness = expected(worn, damagedPieces, Attributes.ARMOR_TOUGHNESS);
        require(near(worn.getAttributeValue(Attributes.ARMOR), expectedArmor),
                "B11 damaged ARMOR == sum over pieces of raw * multiplier");
        require(near(worn.getAttributeValue(Attributes.ARMOR_TOUGHNESS), expectedToughness),
                "B12 damaged ARMOR_TOUGHNESS == sum over pieces of raw * multiplier");
        require(worn.getArmorValue() == (int) Math.floor(worn.getAttributeValue(Attributes.ARMOR)),
                "B13 damaged armor bar == floor(scaled ARMOR attribute)");
        require(worn.getArmorValue() < full.getArmorValue(),
                "B14 damaged armor bar is strictly lower than the undamaged one");

        // The vanilla funnel itself must already emit the scaled amount.
        ItemStack funnelChest = damaged(Items.DIAMOND_CHESTPLATE, 0.5);
        double[] funnelArmor = {0.0};
        funnelChest.forEachModifier(EquipmentSlot.CHEST, (attribute, modifier) -> {
            if (sameAttribute(attribute, Attributes.ARMOR)
                    && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                funnelArmor[0] += modifier.amount();
            }
        });
        require(near(funnelArmor[0], raw(funnelChest, EquipmentSlot.CHEST, Attributes.ARMOR).addValue()
                        * ArmorDurabilityScaling.multiplier(funnelChest)),
                "B15 ItemStack.forEachModifier scales ARMOR on the vanilla funnel");
        double[] funnelToughness = {0.0};
        funnelChest.forEachModifier(EquipmentSlot.CHEST, (attribute, modifier) -> {
            if (sameAttribute(attribute, Attributes.ARMOR_TOUGHNESS)
                    && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                funnelToughness[0] += modifier.amount();
            }
        });
        require(near(funnelToughness[0], raw(funnelChest, EquipmentSlot.CHEST, Attributes.ARMOR_TOUGHNESS).addValue()
                        * ArmorDurabilityScaling.multiplier(funnelChest)),
                "B16 ItemStack.forEachModifier scales ARMOR_TOUGHNESS on the vanilla funnel");
        double[] funnelPristine = {0.0};
        pieces.get(1).stack().forEachModifier(EquipmentSlot.CHEST, (attribute, modifier) -> {
            if (sameAttribute(attribute, Attributes.ARMOR)
                    && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                funnelPristine[0] += modifier.amount();
            }
        });
        require(near(funnelPristine[0], raw(pieces.get(1).stack(), EquipmentSlot.CHEST, Attributes.ARMOR).addValue()),
                "B17 the funnel leaves undamaged armor at its raw amount");

        // Damage reduction must actually follow the scaled values.
        DamageSource source = level.damageSources().mobAttack(worn);
        float incoming = 10.0F;
        float fullResult = full.absorb(source, incoming);
        float wornResult = worn.absorb(source, incoming);
        require(wornResult > fullResult, "B18 worn armor absorbs strictly less damage than full armor");
        float fullExpected = CombatRules.getDamageAfterAbsorb(full, incoming, source, full.getArmorValue(),
                (float) full.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        require(Math.abs(fullResult - fullExpected) < 1e-6F,
                "B19 getDamageAfterArmorAbsorb matches CombatRules exactly (undamaged)");
        float wornExpected = CombatRules.getDamageAfterAbsorb(worn, incoming, source, worn.getArmorValue(),
                (float) worn.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        require(Math.abs(wornResult - wornExpected) < 1e-6F,
                "B20 getDamageAfterArmorAbsorb matches CombatRules exactly (worn)");
        require(fullResult < incoming && wornResult < incoming,
                "B21 both cases actually reduce the incoming damage");

        // Multi-piece weighted sum with different ratios per slot.
        List<Piece> mixed = new ArrayList<>();
        mixed.add(new Piece(EquipmentSlot.HEAD, damaged(Items.DIAMOND_HELMET, 0.25)));
        mixed.add(new Piece(EquipmentSlot.CHEST, damaged(Items.DIAMOND_CHESTPLATE, 0.5)));
        mixed.add(new Piece(EquipmentSlot.LEGS, damaged(Items.DIAMOND_LEGGINGS, 0.75)));
        mixed.add(new Piece(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS)));
        Equipped mixedEquip = spawn(level, mixed);
        mixedEquip.stand().tick();
        double mixedArmor = expected(mixedEquip.stand(), mixed, Attributes.ARMOR);
        double mixedToughness = expected(mixedEquip.stand(), mixed, Attributes.ARMOR_TOUGHNESS);
        require(allAddValue(mixed), "B22 multi-piece entries all use ADD_VALUE");
        require(near(mixedEquip.stand().getAttributeValue(Attributes.ARMOR), mixedArmor),
                "B23 multi-piece weighted ARMOR == sum over pieces of raw * multiplier");
        require(near(mixedEquip.stand().getAttributeValue(Attributes.ARMOR_TOUGHNESS), mixedToughness),
                "B24 multi-piece weighted toughness == sum over pieces of raw * multiplier");
        require(mixedEquip.stand().getArmorValue() == (int) Math.floor(mixedArmor),
                "B25 multi-piece armor bar == floor(weighted ARMOR)");
        require(!near(mixedArmor, rawArmor),
                "B26 the weighted sum really differs from the unscaled baseline");

        // A fully broken piece is skipped by vanilla and contributes nothing (= raw * 0).
        List<Piece> brokenSet = new ArrayList<>();
        brokenSet.add(new Piece(EquipmentSlot.HEAD, damaged(Items.DIAMOND_HELMET, 0.0)));
        brokenSet.add(new Piece(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE)));
        Equipped brokenEquip = spawn(level, brokenSet);
        brokenEquip.stand().tick();
        require(brokenSet.get(0).stack().isBroken(), "B27 fixture: the head piece is broken");
        require(near(brokenEquip.stand().getAttributeValue(Attributes.ARMOR),
                        expected(brokenEquip.stand(), brokenSet, Attributes.ARMOR)),
                "B28 a broken piece contributes exactly raw * 0");

        // Knockback resistance carried by the same damageable stack must not be scaled.
        ItemStack injected = new ItemStack(Items.DIAMOND_HELMET);
        ItemAttributeModifiers injectedBase = injected.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                ItemAttributeModifiers.EMPTY);
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
        injectedBase.modifiers().forEach(entry -> builder.add(entry.attribute(), entry.modifier(), entry.slot()));
        builder.add(Attributes.KNOCKBACK_RESISTANCE,
                new AttributeModifier(Identifier.fromNamespaceAndPath(TEST_NAMESPACE, "injected_knockback_resistance"),
                        0.25, AttributeModifier.Operation.ADD_VALUE),
                EquipmentSlotGroup.HEAD);
        injected.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());
        injected.setDamageValue(injected.getMaxDamage() / 2);

        List<Piece> injectedPieces = List.of(new Piece(EquipmentSlot.HEAD, injected));
        Equipped injectedEquip = spawn(level, injectedPieces);
        injectedEquip.stand().tick();
        require(ArmorDurabilityScaling.multiplier(injected) < 1.0,
                "B29 fixture: the dual-attribute stack is damaged");
        require(near(injectedEquip.stand().getAttributeValue(Attributes.ARMOR),
                        expected(injectedEquip.stand(), injectedPieces, Attributes.ARMOR)),
                "B30 ARMOR on a dual-attribute stack is still scaled");
        require(near(raw(injected, EquipmentSlot.HEAD, Attributes.KNOCKBACK_RESISTANCE).addValue(), 0.25),
                "B31 fixture: the stack carries a raw 0.25 knockback resistance modifier");
        require(near(injectedEquip.stand().getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0.25),
                "B32 KNOCKBACK_RESISTANCE is not scaled on a damaged stack");
        double[] funnelKnockback = {0.0};
        injected.forEachModifier(EquipmentSlot.HEAD, (attribute, modifier) -> {
            if (sameAttribute(attribute, Attributes.KNOCKBACK_RESISTANCE)) {
                funnelKnockback[0] += modifier.amount();
            }
        });
        require(near(funnelKnockback[0], 0.25),
                "B33 the vanilla funnel does not scale KNOCKBACK_RESISTANCE");
    }

    // ------------------------------------------------------------------ C anti double stacking

    private static void antiDoubleStackingChecks(ServerLevel level) {
        List<Piece> pieces = new ArrayList<>();
        pieces.add(new Piece(EquipmentSlot.HEAD, damaged(Items.DIAMOND_HELMET, 0.25)));
        pieces.add(new Piece(EquipmentSlot.CHEST, damaged(Items.DIAMOND_CHESTPLATE, 0.5)));
        pieces.add(new Piece(EquipmentSlot.LEGS, damaged(Items.DIAMOND_LEGGINGS, 0.75)));
        pieces.add(new Piece(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS)));
        Equipped equip = spawn(level, pieces);
        TestArmorStand stand = equip.stand();

        ItemStack chest = pieces.get(1).stack();
        ItemAttributeModifiers before = chest.get(DataComponents.ATTRIBUTE_MODIFIERS);
        int originalDamage = chest.getDamageValue();
        ItemStack pristineChest = chest.copy();

        stand.tick();
        chest.setDamageValue(Math.min(chest.getMaxDamage(), chest.getDamageValue() + 10));
        stand.tick();
        int afterDamage = chest.getDamageValue();
        require(afterDamage > originalDamage, "C1 fixture: the chest piece took further damage");

        require(Objects.equals(before, chest.get(DataComponents.ATTRIBUTE_MODIFIERS)),
                "C2 ATTRIBUTE_MODIFIERS is byte-identical after damage + ticks");
        ItemStack normalised = chest.copy();
        normalised.setDamageValue(originalDamage);
        require(ItemStack.isSameItemSameComponents(normalised, pristineChest),
                "C3 only the DAMAGE component changed: the whole rest of the stack is byte-identical");

        double expectedArmor = expected(stand, pieces, Attributes.ARMOR);
        double expectedToughness = expected(stand, pieces, Attributes.ARMOR_TOUGHNESS);
        require(near(stand.getAttributeValue(Attributes.ARMOR), expectedArmor),
                "C4 value right after the damage tick matches raw * multiplier");

        for (int i = 0; i < 5; i++) {
            stand.tick();
        }
        require(near(stand.getAttributeValue(Attributes.ARMOR), expectedArmor),
                "C5 five extra ticks do not accumulate ARMOR");
        require(near(stand.getAttributeValue(Attributes.ARMOR_TOUGHNESS), expectedToughness),
                "C6 five extra ticks do not accumulate ARMOR_TOUGHNESS");

        for (int i = 0; i < 3; i++) {
            for (Piece piece : pieces) {
                stand.setItemSlot(piece.slot(), piece.stack());
            }
            stand.tick();
        }
        require(near(stand.getAttributeValue(Attributes.ARMOR), expectedArmor),
                "C7 three re-equips do not accumulate ARMOR");

        for (int i = 0; i < 3; i++) {
            chest.setDamageValue(chest.getDamageValue());
            stand.tick();
        }
        require(near(stand.getAttributeValue(Attributes.ARMOR), expectedArmor),
                "C8 repeated identical damage + ticks do not accumulate ARMOR");

        AttributeInstance armorInstance = stand.getAttribute(Attributes.ARMOR);
        require(armorInstance.getPermanentModifiers().stream()
                        .noneMatch(modifier -> modifier.id().getNamespace().startsWith(MOD_NAMESPACE)),
                "C9 the ARMOR attribute has no permanent modifier from the mod namespace");
        require(armorInstance.getModifiers().stream()
                        .noneMatch(modifier -> modifier.id().getNamespace().startsWith(MOD_NAMESPACE)),
                "C10 the ARMOR attribute has no modifier at all from the mod namespace");

        Map<Identifier, Double> expectedById = new HashMap<>();
        for (Piece piece : pieces) {
            collectExpected(piece, Attributes.ARMOR, expectedById);
        }
        Set<Identifier> actualIds = new HashSet<>();
        for (AttributeModifier modifier : armorInstance.getModifiers()) {
            actualIds.add(modifier.id());
        }
        require(actualIds.equals(expectedById.keySet()),
                "C11 the only ARMOR modifiers are the equipped items' own ids"
                        + " (expected " + expectedById.keySet().size() + ", actual " + actualIds.size() + ")");
        boolean amountsMatch = true;
        StringBuilder mismatch = new StringBuilder();
        for (AttributeModifier modifier : armorInstance.getModifiers()) {
            Double want = expectedById.get(modifier.id());
            if (want == null || !near(modifier.amount(), want)) {
                amountsMatch = false;
                mismatch.append(modifier.id()).append("=").append(modifier.amount())
                        .append(" want ").append(want).append("; ");
            }
        }
        require(amountsMatch, "C12 every ARMOR modifier amount == raw * multiplier (no double stacking) " + mismatch);
        require(armorInstance.getModifiers().size() == 4,
                "C13 exactly the four equipped pieces contribute ARMOR modifiers");
    }

    // ------------------------------------------------------------------ D re-entry / desync

    private static void reentryChecks(ServerLevel level) {
        List<Piece> pieces = new ArrayList<>();
        pieces.add(new Piece(EquipmentSlot.HEAD, damaged(Items.DIAMOND_HELMET, 0.5)));
        pieces.add(new Piece(EquipmentSlot.CHEST, damaged(Items.DIAMOND_CHESTPLATE, 0.5)));
        pieces.add(new Piece(EquipmentSlot.LEGS, damaged(Items.DIAMOND_LEGGINGS, 0.5)));
        pieces.add(new Piece(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS)));
        Equipped first = spawn(level, pieces);
        first.stand().tick();
        double armorA = first.stand().getAttributeValue(Attributes.ARMOR);
        double toughnessA = first.stand().getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        int barA = first.stand().getArmorValue();

        // Simulate a world reload / rejoin: the same synced stack content on a brand new entity.
        List<Piece> rejoin = new ArrayList<>();
        for (Piece piece : pieces) {
            rejoin.add(new Piece(piece.slot(), piece.stack().copy()));
        }
        Equipped second = spawn(level, rejoin);
        second.stand().tick();
        require(Double.compare(second.stand().getAttributeValue(Attributes.ARMOR), armorA) == 0,
                "D1 a fresh entity with the same stack computes the bit-identical ARMOR value");
        require(Double.compare(second.stand().getAttributeValue(Attributes.ARMOR_TOUGHNESS), toughnessA) == 0,
                "D2 a fresh entity computes the bit-identical ARMOR_TOUGHNESS value");
        require(second.stand().getArmorValue() == barA,
                "D3 a fresh entity computes the same armor bar value");
        require(near(armorA, expected(first.stand(), pieces, Attributes.ARMOR)),
                "D4 the value is a pure function of the synced stack content");

        // Same computation on both sides by construction: no hidden per-entity state.
        ItemStack stack = pieces.get(1).stack();
        double once = ArmorDurabilityScaling.multiplier(stack);
        double twice = ArmorDurabilityScaling.multiplier(stack.copy());
        require(Double.compare(once, twice) == 0,
                "D5 equal stack content yields a bit-identical multiplier (no per-entity state)");
        ItemStack rebuilt = new ItemStack(Items.DIAMOND_CHESTPLATE);
        rebuilt.setDamageValue(stack.getDamageValue());
        require(Double.compare(ArmorDurabilityScaling.multiplier(rebuilt), once) == 0,
                "D6 an independently rebuilt equal stack yields the same multiplier");
        require(Double.compare(ArmorDurabilityScaling.multiplier(stack),
                        ArmorDurabilityScaling.multiplier(rejoin.get(1).stack())) == 0,
                "D7 the original stack and the copy used by the second entity give the same multiplier");

        require(noNetworkMembers(ArmorDurabilityScaling.class),
                "D8 the scaling API needs no mod-owned packets (no network-typed members)");
        require(noNetworkMembers(DurabilityArmor.class),
                "D9 the mod entrypoint declares no network-typed members");
        require(noClientMembers(ArmorDurabilityScaling.class),
                "D10 the scaling API declares no client-only members");
    }

    // ------------------------------------------------------------------ R1 elytraslot recursion (P1-1)

    /**
     * Reproduces the elytraslot 3.0.0 re-entry on the very method the mod hooks. See
     * {@code ElytraSlotRecursionProbeMixin}: a sibling {@code @Inject(HEAD, cancellable)} reads the
     * method's own consumer parameter, cancels for the BODY slot and re-enters
     * {@code self.forEachModifier(CHEST, forwardingLambda)}. The multiplier must be applied exactly
     * once; substituting the method parameter instead of the dispatch arguments squares it.
     */
    private static void recursionChecks() {
        ItemStack chest = damaged(Items.DIAMOND_CHESTPLATE, 0.5);
        double multiplier = ArmorDurabilityScaling.multiplier(chest);
        double rawArmor = raw(chest, EquipmentSlot.CHEST, Attributes.ARMOR).addValue();
        double rawToughness = raw(chest, EquipmentSlot.CHEST, Attributes.ARMOR_TOUGHNESS).addValue();
        double scaledArmor = rawArmor * multiplier;
        double squaredArmor = rawArmor * multiplier * multiplier;
        require(multiplier > 0.0 && multiplier < 1.0,
                "R1 fixture: damaged chestplate multiplier in (0,1) (" + multiplier + ")");
        require(rawArmor > 0.0 && rawToughness > 0.0,
                "R1 fixture: chestplate has raw ARMOR and toughness contributions");
        require(!near(scaledArmor, squaredArmor),
                "R1 fixture: raw*m and raw*m*m are distinguishable");

        // R1a - the faithful elytraslot shape, driven by a sibling @Inject(HEAD, cancellable).
        List<Double> reentrantArmor = new ArrayList<>();
        List<Double> reentrantToughness = new ArrayList<>();
        BiConsumer<Holder<Attribute>, AttributeModifier> collector = (attribute, modifier) -> {
            if (sameAttribute(attribute, Attributes.ARMOR)) {
                reentrantArmor.add(modifier.amount());
            } else if (sameAttribute(attribute, Attributes.ARMOR_TOUGHNESS)) {
                reentrantToughness.add(modifier.amount());
            }
        };
        RecursionProbe.arm(chest, collector);
        chest.forEachModifier(EquipmentSlot.BODY, collector);
        int redirects = RecursionProbe.redirects();
        boolean sawOriginalConsumer = RecursionProbe.sawOriginalConsumer();
        RecursionProbe.disarm();
        require(redirects == 1,
                "R1a the elytraslot-style sibling injector fired exactly once (got " + redirects + ")");
        require(sawOriginalConsumer,
                "R1a the sibling injector received the caller's ORIGINAL consumer, not a wrapper");
        require(reentrantArmor.size() == 1,
                "R1a exactly one re-entrant ARMOR delivery (got " + reentrantArmor.size() + ")");
        require(reentrantArmor.size() == 1 && near(reentrantArmor.get(0), scaledArmor),
                "R1a re-entrant ARMOR amount == raw * m (scaled exactly once)");
        require(reentrantArmor.size() == 1 && !near(reentrantArmor.get(0), squaredArmor),
                "R1a re-entrant ARMOR amount is NOT raw * m * m (no squared multiplier)");
        require(reentrantToughness.size() == 1 && near(reentrantToughness.get(0), rawToughness * multiplier),
                "R1a re-entrant ARMOR_TOUGHNESS amount == raw * m");
        require(reentrantToughness.size() == 1
                        && !near(reentrantToughness.get(0), rawToughness * multiplier * multiplier),
                "R1a re-entrant toughness is NOT raw * m * m");

        // R1b - the same forwarding shape built purely in Java, no injector involved.
        List<Double> forwarded = new ArrayList<>();
        chest.forEachModifier(EquipmentSlot.CHEST, (outerAttribute, outerModifier) -> {
            if (!sameAttribute(outerAttribute, Attributes.ARMOR)) {
                return;
            }
            chest.forEachModifier(EquipmentSlot.CHEST, (attribute, modifier) -> {
                if (sameAttribute(attribute, Attributes.ARMOR)) {
                    forwarded.add(modifier.amount());
                }
            });
        });
        require(forwarded.size() == 1,
                "R1b nested forwarding delivers ARMOR exactly once (got " + forwarded.size() + ")");
        require(forwarded.size() == 1 && near(forwarded.get(0), scaledArmor),
                "R1b nested forwarding yields raw * m");
        require(forwarded.size() == 1 && !near(forwarded.get(0), squaredArmor),
                "R1b nested forwarding is NOT raw * m * m");

        // R1c - plain, non-recursive control, with the probe armed for a DIFFERENT stack so the
        // "inert unless armed for this exact stack instance" gate is exercised for real.
        ItemStack decoy = new ItemStack(Items.DIAMOND_CHESTPLATE);
        RecursionProbe.arm(decoy, collector);
        List<Double> plain = new ArrayList<>();
        chest.forEachModifier(EquipmentSlot.CHEST, (attribute, modifier) -> {
            if (sameAttribute(attribute, Attributes.ARMOR)) {
                plain.add(modifier.amount());
            }
        });
        int decoyRedirects = RecursionProbe.redirects();
        RecursionProbe.disarm();
        require(plain.size() == 1 && near(plain.get(0), scaledArmor),
                "R1c plain (non-recursive) call still yields raw * m");
        require(plain.size() == 1 && !near(plain.get(0), squaredArmor),
                "R1c the plain call is not squared either");
        require(decoyRedirects == 0,
                "R1c the probe stays inert while armed for a different stack (got " + decoyRedirects + ")");

        // R1d/R1e - ordering-independent static guards on the compiled hook: it must not substitute
        // the forEachModifier parameter again, and every @ModifyArg it uses must point at the
        // BiConsumer argument of the dispatch call it annotates (that is the index/type semantics).
        HookShapeScan hookScan = scanHookShape();
        require(hookScan != null && !hookScan.replacesForEachModifierArgument,
                "R1d ItemStackMixin does not replace the forEachModifier parameter"
                        + " (no argsOnly @ModifyVariable targeting it)");
        boolean indexesCorrect = hookScan != null && !hookScan.modifyArgFindings.isEmpty();
        if (hookScan != null) {
            for (int[] pair : hookScan.modifyArgIndexChecks) {
                if (pair[0] != pair[1]) {
                    indexesCorrect = false;
                }
            }
        }
        require(indexesCorrect,
                "R1e every @ModifyArg inside forEachModifier points at the BiConsumer argument ("
                        + (hookScan == null ? "scan failed" : hookScan.modifyArgFindings.size() + " checked")
                        + "): " + (hookScan == null ? "-" : String.join(" | ", hookScan.modifyArgFindings)));
    }

    // ------------------------------------------------------------------ R2 equip/damage/unequip lifecycle

    private static void lifecycleChecks(ServerLevel level) {
        List<Piece> pieces = new ArrayList<>();
        pieces.add(new Piece(EquipmentSlot.HEAD, damaged(Items.DIAMOND_HELMET, 0.25)));
        ItemStack chest = damaged(Items.DIAMOND_CHESTPLATE, 0.5);
        pieces.add(new Piece(EquipmentSlot.CHEST, chest));
        pieces.add(new Piece(EquipmentSlot.LEGS, damaged(Items.DIAMOND_LEGGINGS, 0.75)));
        pieces.add(new Piece(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS)));
        Equipped equipped = spawn(level, pieces);
        TestArmorStand stand = equipped.stand();
        stand.tick();

        for (int cycle = 1; cycle <= 3; cycle++) {
            String tag = "R2 cycle " + cycle;
            try {
                chest.setDamageValue(Math.min(chest.getMaxDamage(), chest.getDamageValue() + 5));
                stand.tick();
                require(perIdMatches(stand, Attributes.ARMOR, expectedById(pieces, Attributes.ARMOR)),
                        tag + ": after damage + tick exactly one ARMOR modifier per id, amount == raw * m");
                require(perIdMatches(stand, Attributes.ARMOR_TOUGHNESS, expectedById(pieces, Attributes.ARMOR_TOUGHNESS)),
                        tag + ": after damage + tick exactly one toughness modifier per id, amount == raw * m");

                stand.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
                stand.tick();
                require(stand.getAttribute(Attributes.ARMOR).getModifiers().size() == 3,
                        tag + ": unequipping removes exactly the chest ARMOR modifier");
                require(stand.getAttribute(Attributes.ARMOR_TOUGHNESS).getModifiers().size() == 3,
                        tag + ": unequipping removes exactly the chest toughness modifier");

                stand.setItemSlot(EquipmentSlot.CHEST, chest);
                stand.tick();
                require(perIdMatches(stand, Attributes.ARMOR, expectedById(pieces, Attributes.ARMOR)),
                        tag + ": re-equipping restores one ARMOR modifier per id with amount raw * m");
                require(perIdMatches(stand, Attributes.ARMOR_TOUGHNESS, expectedById(pieces, Attributes.ARMOR_TOUGHNESS)),
                        tag + ": re-equipping restores one toughness modifier per id with amount raw * m");
            } catch (Throwable error) {
                require(false, tag + " threw " + error
                        + " (duplicate modifier id / accumulation / re-entry failure?)");
            }
        }
    }

    // ------------------------------------------------------------------ R3 main-hand items are untouched

    private static void handSlotChecks(ServerLevel level) {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.setDamageValue(sword.getMaxDamage() / 2);
        double swordMultiplier = ArmorDurabilityScaling.multiplier(sword);
        double rawAttack = raw(sword, EquipmentSlot.MAINHAND, Attributes.ATTACK_DAMAGE).addValue();
        require(rawAttack > 0.0, "R3 fixture: damaged diamond sword carries a raw ATTACK_DAMAGE modifier");
        require(swordMultiplier > 0.0 && swordMultiplier < 1.0, "R3 fixture: the sword is damaged");

        double[] funnelAttack = {0.0};
        sword.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (sameAttribute(attribute, Attributes.ATTACK_DAMAGE)) {
                funnelAttack[0] += modifier.amount();
            }
        });
        require(near(funnelAttack[0], rawAttack),
                "R3a the funnel leaves ATTACK_DAMAGE unscaled on a damaged main-hand sword");
        require(!near(funnelAttack[0], rawAttack * swordMultiplier),
                "R3a the ATTACK_DAMAGE check would notice scaling (raw != raw * m here)");

        // A Zombie carries both ATTACK_DAMAGE and ARMOR instances; an ArmorStand has no ATTACK_DAMAGE.
        ItemStack damagedHelmet = damaged(Items.DIAMOND_HELMET, 0.5);
        Zombie probe = new Zombie(level);
        probe.setNoGravity(true);
        probe.setPos(12.5, 100.0, 12.5);
        probe.setItemSlot(EquipmentSlot.MAINHAND, sword);
        probe.setItemSlot(EquipmentSlot.HEAD, damagedHelmet);
        require(level.addFreshEntity(probe), "R3 fixture: the zombie probe spawned");

        Zombie reference = new Zombie(level);
        reference.setNoGravity(true);
        reference.setPos(16.5, 100.0, 16.5);
        reference.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        require(level.addFreshEntity(reference), "R3 fixture: the reference zombie spawned");

        require(probe.getAttribute(Attributes.ATTACK_DAMAGE) != null,
                "R3 fixture: the probe has an ATTACK_DAMAGE instance");
        require(probe.getAttribute(Attributes.ARMOR) != null, "R3 fixture: the probe has an ARMOR instance");
        reference.tick();
        probe.tick();

        require(near(probe.getAttributeValue(Attributes.ATTACK_DAMAGE),
                        reference.getAttributeValue(Attributes.ATTACK_DAMAGE)),
                "R3b a damaged main-hand sword leaves ATTACK_DAMAGE unchanged after ticks");
        require(near(probe.getAttributeValue(Attributes.ATTACK_DAMAGE),
                        probe.getAttributeBaseValue(Attributes.ATTACK_DAMAGE) + rawAttack),
                "R3b ATTACK_DAMAGE equals base + raw (never scaled)");
        require(!near(probe.getAttributeValue(Attributes.ATTACK_DAMAGE),
                        probe.getAttributeBaseValue(Attributes.ATTACK_DAMAGE) + rawAttack * swordMultiplier),
                "R3b the ATTACK_DAMAGE assertion is not vacuous (raw * m would differ)");

        List<Piece> helmetPieces = List.of(new Piece(EquipmentSlot.HEAD, damagedHelmet));
        require(near(probe.getAttributeValue(Attributes.ARMOR), expected(probe, helmetPieces, Attributes.ARMOR)),
                "R3b armor on the same entity is still scaled");
        require(probe.getAttributeValue(Attributes.ARMOR) > probe.getAttributeBaseValue(Attributes.ARMOR),
                "R3b the scaled armor contribution is actually present");
    }

    // ------------------------------------------------------------------ R4 persistence / re-entry

    private static void persistenceChecks(ServerLevel level) {
        List<Piece> pieces = new ArrayList<>();
        pieces.add(new Piece(EquipmentSlot.HEAD, damaged(Items.DIAMOND_HELMET, 0.25)));
        pieces.add(new Piece(EquipmentSlot.CHEST, damaged(Items.DIAMOND_CHESTPLATE, 0.5)));
        pieces.add(new Piece(EquipmentSlot.LEGS, damaged(Items.DIAMOND_LEGGINGS, 0.75)));
        Equipped equipped = spawn(level, pieces);
        TestArmorStand stand = equipped.stand();
        stand.tick();
        double armorBefore = stand.getAttributeValue(Attributes.ARMOR);
        double toughnessBefore = stand.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        int barBefore = stand.getArmorValue();

        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        require(stand.save(output), "R4 fixture: Entity#save(ValueOutput) succeeded");
        CompoundTag saved = output.buildResult();
        String serialized = saved.toString();

        require(serialized.contains("minecraft:diamond_chestplate"),
                "R4 fixture: the equipped armor is present in the saved data");
        require(!serialized.contains(MOD_NAMESPACE),
                "R4 the saved data contains no id from the durability_armor namespace");
        require(!serialized.contains(TEST_NAMESPACE),
                "R4 the saved data contains no test-injected modifier id");

        TestArmorStand restored = new TestArmorStand(level);
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
        // The original stand is still registered in this same live level, so the persisted UUID is
        // already taken. The UUID plays no part in the scaling assertions; a real world reload would
        // bring the entity back under its saved UUID.
        restored.setUUID(java.util.UUID.randomUUID());
        require(level.addFreshEntity(restored), "R4 fixture: the restored entity spawned");
        restored.tick();

        require(Double.compare(restored.getAttributeValue(Attributes.ARMOR), armorBefore) == 0,
                "R4 the restored entity recomputes the bit-identical ARMOR value");
        require(Double.compare(restored.getAttributeValue(Attributes.ARMOR_TOUGHNESS), toughnessBefore) == 0,
                "R4 the restored entity recomputes the bit-identical ARMOR_TOUGHNESS value");
        require(restored.getArmorValue() == barBefore, "R4 the restored entity yields the same armor bar");
        require(restored.getAttribute(Attributes.ARMOR).getPermanentModifiers().isEmpty(),
                "R4 nothing scaled was persisted as a permanent modifier");
        require(restored.getAttribute(Attributes.ARMOR).getModifiers().stream()
                        .noneMatch(modifier -> modifier.id().getNamespace().startsWith(MOD_NAMESPACE)),
                "R4 the restored entity carries no modifier from the mod namespace");
        require(perIdMatches(restored, Attributes.ARMOR, expectedById(pieces, Attributes.ARMOR)),
                "R4 the restored entity rebuilds one modifier per id with amount raw * m");
    }

    // ------------------------------------------------------------------ R5 edge cases

    private static void edgeCaseChecks(ServerLevel level) {
        // (a) exactly one durability left
        ItemStack lastPoint = new ItemStack(Items.DIAMOND_HELMET);
        lastPoint.setDamageValue(lastPoint.getMaxDamage() - 1);
        double lastMultiplier = ArmorDurabilityScaling.multiplier(lastPoint);
        require(remainingRatio(lastPoint) > 0.0 && lastMultiplier > 0.0 && lastMultiplier < 0.01,
                "R5a fixture: one durability left yields a tiny positive multiplier (" + lastMultiplier + ")");
        double lastRaw = raw(lastPoint, EquipmentSlot.HEAD, Attributes.ARMOR).addValue();
        List<Piece> lastPieces = List.of(new Piece(EquipmentSlot.HEAD, lastPoint));
        Equipped lastEquip = spawn(level, lastPieces);
        lastEquip.stand().tick();
        double lastBase = lastEquip.stand().getAttributeBaseValue(Attributes.ARMOR);
        double lastValue = lastEquip.stand().getAttributeValue(Attributes.ARMOR);
        require(near(lastValue, lastBase + lastRaw * lastMultiplier),
                "R5a one durability left scales the contribution (not zeroed, not full)");
        require(!near(lastValue, lastBase + lastRaw),
                "R5a the one-durability contribution is not the full raw value");

        // (b) broken (damage == maxDamage): vanilla skips broken stacks
        ItemStack brokenHelmet = new ItemStack(Items.DIAMOND_HELMET);
        brokenHelmet.setDamageValue(brokenHelmet.getMaxDamage());
        require(brokenHelmet.isBroken(), "R5b fixture: the piece is broken");
        require(ArmorDurabilityScaling.multiplier(brokenHelmet) == 0.0, "R5b the broken multiplier is 0.0");
        ItemStack intactChest = new ItemStack(Items.DIAMOND_CHESTPLATE);
        List<Piece> brokenPieces = new ArrayList<>();
        brokenPieces.add(new Piece(EquipmentSlot.HEAD, brokenHelmet));
        brokenPieces.add(new Piece(EquipmentSlot.CHEST, intactChest));
        Equipped brokenEquip = spawn(level, brokenPieces);
        brokenEquip.stand().tick();
        double brokenRaw = raw(brokenHelmet, EquipmentSlot.HEAD, Attributes.ARMOR).addValue();
        double intactRaw = raw(intactChest, EquipmentSlot.CHEST, Attributes.ARMOR).addValue();
        double brokenBase = brokenEquip.stand().getAttributeBaseValue(Attributes.ARMOR);
        require(near(brokenEquip.stand().getAttributeValue(Attributes.ARMOR), brokenBase + intactRaw),
                "R5b the broken piece contributes nothing while the intact piece contributes raw");
        require(!near(brokenEquip.stand().getAttributeValue(Attributes.ARMOR), brokenBase + brokenRaw + intactRaw),
                "R5b the broken piece is really skipped");

        // (c) UNBREAKABLE with a damage component present - CORRECTED for the frozen P1-1 semantics:
        // the UNBREAKABLE short-circuit is gone, so there is no exemption and the contribution is
        // raw * multiplier. The old pair of assertions here pinned the rejected behaviour.
        ItemStack unbreakable = new ItemStack(Items.DIAMOND_HELMET);
        unbreakable.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
        unbreakable.setDamageValue(unbreakable.getMaxDamage() / 2);
        require(unbreakable.has(DataComponents.UNBREAKABLE) && unbreakable.getDamageValue() > 0,
                "R5c fixture: unbreakable stack carrying a damage component");
        double unbreakableMultiplier = ArmorDurabilityScaling.multiplier(unbreakable);
        require(near(unbreakableMultiplier, closedForm(remainingRatio(unbreakable))) && unbreakableMultiplier < 1.0,
                "R5c CORRECTED (P1-1): the damaged unbreakable multiplier follows the formula ("
                        + unbreakableMultiplier + " < 1)");
        double unbreakableRaw = raw(unbreakable, EquipmentSlot.HEAD, Attributes.ARMOR).addValue();
        List<Piece> unbreakablePieces = List.of(new Piece(EquipmentSlot.HEAD, unbreakable));
        Equipped unbreakableEquip = spawn(level, unbreakablePieces);
        unbreakableEquip.stand().tick();
        require(near(unbreakableEquip.stand().getAttributeValue(Attributes.ARMOR),
                        unbreakableEquip.stand().getAttributeBaseValue(Attributes.ARMOR)
                                + unbreakableRaw * unbreakableMultiplier),
                "R5c CORRECTED (P1-1): the damaged unbreakable piece contributes raw * multiplier");
        require(!near(unbreakableEquip.stand().getAttributeValue(Attributes.ARMOR),
                        unbreakableEquip.stand().getAttributeBaseValue(Attributes.ARMOR) + unbreakableRaw),
                "R5c CORRECTED (P1-1): the old 'full raw contribution' expectation no longer holds");

        // (d) no MAX_DAMAGE component at all
        ItemStack noMaxDamage = new ItemStack(Items.STICK);
        ItemAttributeModifiers.Builder noMaxBuilder = ItemAttributeModifiers.builder();
        noMaxBuilder.add(Attributes.ARMOR, new AttributeModifier(
                Identifier.fromNamespaceAndPath(TEST_NAMESPACE, "no_max_damage"),
                5.0, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.HEAD);
        noMaxDamage.set(DataComponents.ATTRIBUTE_MODIFIERS, noMaxBuilder.build());
        require(!noMaxDamage.has(DataComponents.MAX_DAMAGE), "R5d fixture: the stack has no MAX_DAMAGE component");
        require(ArmorDurabilityScaling.multiplier(noMaxDamage) == 1.0,
                "R5d the multiplier without MAX_DAMAGE is 1.0");
        List<Piece> noMaxPieces = List.of(new Piece(EquipmentSlot.HEAD, noMaxDamage));
        Equipped noMaxEquip = spawn(level, noMaxPieces);
        noMaxEquip.stand().tick();
        require(near(noMaxEquip.stand().getAttributeValue(Attributes.ARMOR),
                        noMaxEquip.stand().getAttributeBaseValue(Attributes.ARMOR) + 5.0),
                "R5d a stack without MAX_DAMAGE keeps its full raw contribution");

        // (e) ARMOR and KNOCKBACK_RESISTANCE on the same damaged item: KBR must stay bit-identical
        ItemStack dual = new ItemStack(Items.DIAMOND_HELMET);
        ItemAttributeModifiers dualBase = dual.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                ItemAttributeModifiers.EMPTY);
        ItemAttributeModifiers.Builder dualBuilder = ItemAttributeModifiers.builder();
        dualBase.modifiers().forEach(entry -> dualBuilder.add(entry.attribute(), entry.modifier(), entry.slot()));
        dualBuilder.add(Attributes.KNOCKBACK_RESISTANCE, new AttributeModifier(
                Identifier.fromNamespaceAndPath(TEST_NAMESPACE, "dual_knockback_resistance"),
                0.25, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.HEAD);
        dual.set(DataComponents.ATTRIBUTE_MODIFIERS, dualBuilder.build());
        dual.setDamageValue(dual.getMaxDamage() / 2);
        double dualMultiplier = ArmorDurabilityScaling.multiplier(dual);
        require(dualMultiplier > 0.0 && dualMultiplier < 1.0,
                "R5e fixture: the dual-attribute stack is damaged (" + dualMultiplier + ")");
        double dualRawArmor = raw(dual, EquipmentSlot.HEAD, Attributes.ARMOR).addValue();
        double[] dualFunnelArmor = {0.0};
        double[] dualFunnelKnockback = {0.0};
        dual.forEachModifier(EquipmentSlot.HEAD, (attribute, modifier) -> {
            if (sameAttribute(attribute, Attributes.ARMOR)) {
                dualFunnelArmor[0] += modifier.amount();
            }
            if (sameAttribute(attribute, Attributes.KNOCKBACK_RESISTANCE)) {
                dualFunnelKnockback[0] += modifier.amount();
            }
        });
        require(near(dualFunnelArmor[0], dualRawArmor * dualMultiplier),
                "R5e the ARMOR half is scaled on the vanilla funnel");
        require(Double.compare(dualFunnelKnockback[0], 0.25) == 0,
                "R5e the KNOCKBACK_RESISTANCE half is bit-identical on the vanilla funnel");

        List<Piece> dualPieces = List.of(new Piece(EquipmentSlot.HEAD, dual));
        Equipped dualEquip = spawn(level, dualPieces);
        dualEquip.stand().tick();
        require(near(dualEquip.stand().getAttributeValue(Attributes.ARMOR),
                        expected(dualEquip.stand(), dualPieces, Attributes.ARMOR)),
                "R5e the entity scales ARMOR on the dual-attribute stack");
        require(Double.compare(dualEquip.stand().getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0.25) == 0,
                "R5e the entity KNOCKBACK_RESISTANCE value is bit-identical");
    }

    // ------------------------------------------------------------------ G1 rearm @ModifyReceiver coexistence

    /**
     * Runtime proof that our two {@code @ModifyArg}s coexist with a MixinExtras {@code @ModifyReceiver}
     * on the SAME {@code ItemAttributeModifiers.forEach(EquipmentSlot, BiConsumer)} INVOKE (rearm
     * 2.5.6's shape, operand 0 vs operands 1/2). See
     * {@code RearmStyleReceiverProbeMixin} / {@link RearmProbe}.
     *
     * <p>Non-vacuity: the appended ids can only be produced by the probe's {@code @ModifyReceiver},
     * so their presence proves the receiver swap ran; their amounts can only be {@code amount * m}
     * if our {@code @ModifyArg} wrapped the very consumer that the swapped receiver's entries are
     * dispatched to. Neither injector alone can pass these checks.</p>
     */
    private static void rearmCoexistenceChecks() {
        ItemStack chest = damaged(Items.DIAMOND_CHESTPLATE, 0.5);
        double multiplier = ArmorDurabilityScaling.multiplier(chest);
        double rawArmor = raw(chest, EquipmentSlot.CHEST, Attributes.ARMOR).addValue();
        double rawToughness = raw(chest, EquipmentSlot.CHEST, Attributes.ARMOR_TOUGHNESS).addValue();
        require(multiplier > 0.0 && multiplier < 1.0,
                "G1 fixture: damaged chestplate multiplier in (0,1) (" + multiplier + ")");
        require(rawArmor > 0.0 && rawToughness > 0.0,
                "G1 fixture: the chestplate carries raw ARMOR and ARMOR_TOUGHNESS modifiers");

        Set<Identifier> componentArmor = expectedById(List.of(new Piece(EquipmentSlot.CHEST, chest)),
                Attributes.ARMOR).keySet();
        Set<Identifier> componentToughness = expectedById(List.of(new Piece(EquipmentSlot.CHEST, chest)),
                Attributes.ARMOR_TOUGHNESS).keySet();
        // Vanilla's ArmorMaterial.createAttributes reuses ONE Identifier for the ARMOR,
        // ARMOR_TOUGHNESS and KNOCKBACK_RESISTANCE entries of a piece (verified in bytecode), so a
        // collector keyed by id alone would sum those two attributes together. Everything below is
        // therefore collected PER ATTRIBUTE; this fixture fails loudly if a future version changes it.
        require(componentArmor.equals(componentToughness) && !componentArmor.isEmpty(),
                "G1 fixture: vanilla reuses one modifier id per armour piece across ARMOR and"
                        + " ARMOR_TOUGHNESS, so the oracle must be per-attribute (" + componentArmor + ")");

        // Disarmed control: nothing appended, no replacement.
        Map<Identifier, Double> controlToughness = amountsFor(chest, EquipmentSlot.CHEST,
                Attributes.ARMOR_TOUGHNESS);
        Map<Identifier, Double> controlAttack = amountsFor(chest, EquipmentSlot.CHEST, Attributes.ATTACK_DAMAGE);
        require(!controlToughness.containsKey(RearmProbe.TOUGHNESS_ID)
                        && !controlAttack.containsKey(RearmProbe.ATTACK_DAMAGE_ID),
                "G1 fixture: the receiver imitation is inert unless armed for this exact stack");
        require(RearmProbe.replacements() == 0,
                "G1 fixture: no receiver replacement happens while disarmed");

        RearmProbe.arm(chest);
        Map<Identifier, Double> armorById = amountsFor(chest, EquipmentSlot.CHEST, Attributes.ARMOR);
        Map<Identifier, Double> toughnessById = amountsFor(chest, EquipmentSlot.CHEST, Attributes.ARMOR_TOUGHNESS);
        Map<Identifier, Double> attackById = amountsFor(chest, EquipmentSlot.CHEST, Attributes.ATTACK_DAMAGE);
        int replacements = RearmProbe.replacements();
        RearmProbe.disarm();

        require(replacements >= 1,
                "G1 the MixinExtras @ModifyReceiver handler ran on the same INVOKE as our @ModifyArg"
                        + " (replacements=" + replacements + ", server started => no InvalidInjectionException/VerifyError)");
        require(toughnessById.containsKey(RearmProbe.TOUGHNESS_ID)
                        && attackById.containsKey(RearmProbe.ATTACK_DAMAGE_ID),
                "G1 the receiver's appended modifiers reached the consumer through the hooked overload");

        double observedArmor = sumOf(armorById, componentArmor);
        double observedToughness = sumOf(toughnessById, componentToughness);
        require(near(observedArmor, rawArmor * multiplier),
                "G1 the base component's ARMOR modifier is scaled exactly once (raw * m)");
        require(!near(observedArmor, rawArmor),
                "G1 the base component's ARMOR modifier is not left unscaled");
        require(!near(observedArmor, rawArmor * multiplier * multiplier),
                "G1 the base component's ARMOR modifier is not scaled twice (raw * m * m)");
        require(near(observedToughness, rawToughness * multiplier),
                "G1 the base component's ARMOR_TOUGHNESS modifier is scaled exactly once");

        Double appendedToughness = toughnessById.get(RearmProbe.TOUGHNESS_ID);
        require(appendedToughness != null
                        && near(appendedToughness, RearmProbe.TOUGHNESS_AMOUNT * multiplier),
                "G1 the ARMOR_TOUGHNESS modifier ADDED by the receiver is scaled exactly once"
                        + " (this is what rearm's real armours rely on)");
        require(appendedToughness != null && !near(appendedToughness, RearmProbe.TOUGHNESS_AMOUNT),
                "G1 the receiver-added toughness is not left unscaled");
        require(appendedToughness != null
                        && !near(appendedToughness, RearmProbe.TOUGHNESS_AMOUNT * multiplier * multiplier),
                "G1 the receiver-added toughness is not scaled twice");

        Double appendedAttack = attackById.get(RearmProbe.ATTACK_DAMAGE_ID);
        require(appendedAttack != null && near(appendedAttack, RearmProbe.ATTACK_DAMAGE_AMOUNT),
                "G1 a non-armour attribute pushed through the same path stays untouched");
        require(appendedAttack != null && !near(appendedAttack, RearmProbe.ATTACK_DAMAGE_AMOUNT * multiplier),
                "G1 the non-armour assertion would notice scaling");

        // Static companion: the compiled probe really declares the MixinExtras injector on that INVOKE.
        HookShapeScan probeScan = scanMixinClass(PROBE_MIXIN_CLASS);
        boolean receiverDeclared = probeScan != null
                && probeScan.modifyReceiverFindings.stream()
                        .anyMatch(finding -> finding.contains("ItemAttributeModifiers;forEach"));
        require(receiverDeclared,
                "G1 the compiled probe mixin declares @ModifyReceiver on the ItemAttributeModifiers.forEach INVOKE: "
                        + (probeScan == null ? "scan failed" : String.join(" | ", probeScan.modifyReceiverFindings)));
    }

    // ------------------------------------------------------------------ G2 group overload stays raw

    /**
     * Pins the documented "tooltips are not scaled" contract against a future "hook both overloads"
     * refactor. Non-vacuity: the fixture asserts {@code raw != raw * m} for this stack, and both
     * overloads must deliver exactly one ARMOR entry with different amounts.
     */
    private static void groupOverloadChecks() {
        ItemStack chest = damaged(Items.DIAMOND_CHESTPLATE, 0.5);
        double multiplier = ArmorDurabilityScaling.multiplier(chest);
        double rawArmor = raw(chest, EquipmentSlot.CHEST, Attributes.ARMOR).addValue();
        require(!near(rawArmor, rawArmor * multiplier),
                "G2 fixture: raw and raw * m differ for this stack (" + rawArmor + " vs " + rawArmor * multiplier + ")");

        List<Double> hooked = new ArrayList<>();
        chest.forEachModifier(EquipmentSlot.CHEST, (attribute, modifier) -> {
            if (sameAttribute(attribute, Attributes.ARMOR)) {
                hooked.add(modifier.amount());
            }
        });
        List<Double> group = new ArrayList<>();
        TriConsumer<Holder<Attribute>, AttributeModifier, ItemAttributeModifiers.Display> collector =
                (attribute, modifier, display) -> {
                    if (sameAttribute(attribute, Attributes.ARMOR)) {
                        group.add(modifier.amount());
                    }
                };
        chest.forEachModifier(EquipmentSlotGroup.CHEST, collector);

        require(hooked.size() == 1 && near(hooked.get(0), rawArmor * multiplier),
                "G2 the hooked (EquipmentSlot) overload reports raw * m on the damaged stack");
        require(group.size() == 1, "G2 fixture: the group overload delivers the ARMOR modifier exactly once");
        require(group.size() == 1 && near(group.get(0), rawArmor),
                "G2 the group overload (tooltip path) reports the RAW amount, never raw * m");
        require(group.size() == 1 && !near(group.get(0), rawArmor * multiplier),
                "G2 the group overload does not report raw * m");
        require(hooked.size() == 1 && group.size() == 1 && !near(hooked.get(0), group.get(0)),
                "G2 the two overloads genuinely return different amounts for the same damaged stack");
    }

    // ------------------------------------------------------------------ G3 Holder.value() fallback

    /**
     * Exercises the {@code .value()} fallback of {@code scalesAttribute}, which the canonical-holder
     * checks (A27-A30) never reach. Non-vacuity: the fixture proves {@link Holder#direct} is a
     * different Holder object, so the identity fast path cannot be what answers; without the
     * fallback both the {@code scalesAttribute} and the {@code scale} checks would fail.
     */
    private static void holderFallbackChecks() {
        ItemStack half = damaged(Items.DIAMOND_CHESTPLATE, 0.5);
        double multiplier = ArmorDurabilityScaling.multiplier(half);
        require(multiplier > 0.0 && multiplier < 1.0, "G3 fixture: damaged chestplate multiplier in (0,1)");

        Holder<Attribute> directArmor = Holder.direct(Attributes.ARMOR.value());
        Holder<Attribute> directToughness = Holder.direct(Attributes.ARMOR_TOUGHNESS.value());
        Holder<Attribute> directKnockback = Holder.direct(Attributes.KNOCKBACK_RESISTANCE.value());
        Holder<Attribute> directAttack = Holder.direct(Attributes.ATTACK_DAMAGE.value());
        require(directArmor != Attributes.ARMOR && directArmor.value() == Attributes.ARMOR.value(),
                "G3 fixture: Holder.direct is a different Holder wrapping the same Attribute"
                        + " (the identity fast path is missed)");

        require(ArmorDurabilityScaling.scalesAttribute(directArmor),
                "G3 scalesAttribute(direct ARMOR holder) is true via the .value() fallback");
        require(ArmorDurabilityScaling.scalesAttribute(directToughness),
                "G3 scalesAttribute(direct ARMOR_TOUGHNESS holder) is true via the .value() fallback");
        require(!ArmorDurabilityScaling.scalesAttribute(directKnockback),
                "G3 scalesAttribute(direct KNOCKBACK_RESISTANCE holder) is false");
        require(!ArmorDurabilityScaling.scalesAttribute(directAttack),
                "G3 scalesAttribute(direct ATTACK_DAMAGE holder) is false");

        AttributeModifier probe = new AttributeModifier(
                Identifier.fromNamespaceAndPath(TEST_NAMESPACE, "direct_holder_probe"),
                8.0, AttributeModifier.Operation.ADD_VALUE);
        AttributeModifier scaled = ArmorDurabilityScaling.scale(half, directArmor, probe);
        require(near(scaled.amount(), 8.0 * multiplier),
                "G3 scale() with a direct ARMOR holder multiplies by the multiplier (fallback exercised)");
        require(scaled.id().equals(probe.id()) && scaled.operation() == probe.operation(),
                "G3 the fallback-scaled modifier keeps its id and operation");
        require(ArmorDurabilityScaling.scale(half, directKnockback, probe).equals(probe),
                "G3 scale() with a direct KNOCKBACK_RESISTANCE holder returns the input unchanged");
        require(ArmorDurabilityScaling.scale(half, directAttack, probe).equals(probe),
                "G3 scale() with a direct ATTACK_DAMAGE holder returns the input unchanged");
    }

    // ------------------------------------------------------------------ G4 id based removal

    /**
     * Pins the invariant that keeps removal safe: vanilla removes equipment modifiers <b>by id</b>
     * ({@code LivingEntity.stopLocationBasedEffects} -> {@code removeModifier(id)}), so the scaled
     * amount, the operation and even the object identity must be irrelevant.
     *
     * <p>Non-vacuity (this is the Codex P2-2 fix): the old version removed the very same instance it
     * had installed, so it would also have passed if removal matched by value or identity. Now the
     * installed modifier is {@code (id, 6, ADD_VALUE)} and the modifier handed to
     * {@code removeModifier} is a <em>newly constructed</em> {@code (same id, 123,
     * ADD_MULTIPLIED_TOTAL)} instance - different amount, different operation, different object - and
     * the installed one must still disappear.</p>
     */
    private static void idRemovalChecks(ServerLevel level) {
        ItemStack chest = damaged(Items.DIAMOND_CHESTPLATE, 0.5);
        List<Piece> pieces = List.of(new Piece(EquipmentSlot.CHEST, chest));
        Equipped equipped = spawn(level, pieces);
        TestArmorStand stand = equipped.stand();
        stand.tick();

        AttributeInstance instance = stand.getAttribute(Attributes.ARMOR);
        Map<Identifier, Double> expected = expectedById(pieces, Attributes.ARMOR);
        require(expected.size() == 1, "G4 fixture: the chestplate contributes exactly one ARMOR modifier id");
        Identifier id = expected.keySet().iterator().next();
        AttributeModifier installed = instance.getModifier(id);
        require(installed != null, "G4 fixture: that id is present on the entity's ARMOR instance");
        require(near(installed.amount(), expected.get(id)), "G4 fixture: the installed amount is raw * m");
        require(instance.getModifiers().size() == 1, "G4 fixture: exactly one modifier before the conflicting instance");

        // Reproduce exactly the shape the mixin produces for this piece: (id, 6, ADD_VALUE).
        double installedAmount = expected.get(id);
        require(near(installedAmount, 6.0),
                "G4 fixture: the scenario is (id, 6, ADD_VALUE) as specified (" + installedAmount + ")");
        instance.addOrUpdateTransientModifier(new AttributeModifier(id, installedAmount,
                AttributeModifier.Operation.ADD_VALUE));
        require(instance.getModifiers().size() == 1,
                "G4 a same-id instance replaces rather than duplicates (one modifier per id)");
        AttributeModifier present = instance.getModifier(id);
        require(present != null && near(present.amount(), installedAmount)
                        && present.operation() == AttributeModifier.Operation.ADD_VALUE,
                "G4 fixture: the installed modifier really is (id, 6, ADD_VALUE)");

        // A NEW instance: same id, different amount AND different operation. Only id matching can
        // explain the installed modifier being removed by this call.
        AttributeModifier removalProbe = new AttributeModifier(id, 123.0,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        require(!removalProbe.equals(present),
                "G4 fixture: the removal probe differs from the installed modifier in amount and operation");
        instance.removeModifier(removalProbe);
        require(instance.getModifier(id) == null,
                "G4 removing a newly constructed same-id instance (different amount and operation) still"
                        + " deletes the installed modifier => removal is by id, not by value or identity");
        require(instance.getModifiers().isEmpty(), "G4 no orphan modifier is left behind");
        require(near(stand.getAttributeValue(Attributes.ARMOR), stand.getAttributeBaseValue(Attributes.ARMOR)),
                "G4 the ARMOR value returns to the base value after the id removal");

        chest.setDamageValue(chest.getDamageValue() + 5);
        stand.tick();
        double expectedAfter = raw(chest, EquipmentSlot.CHEST, Attributes.ARMOR).addValue()
                * ArmorDurabilityScaling.multiplier(chest);
        require(instance.getModifiers().size() == 1, "G4 the equipment-change tick restores exactly one modifier id");
        require(instance.getModifier(id) != null && near(instance.getModifier(id).amount(), expectedAfter),
                "G4 the restored modifier amount is raw * m again");
    }

    // ------------------------------------------------------------------ P1-2 operation aware scaling

    /**
     * Frozen P1-2 semantics: {@code scale()} leaves {@code ADD_MULTIPLIED_TOTAL} modifiers untouched
     * (same instance, same amount) and only scales {@code ADD_VALUE} / {@code ADD_MULTIPLIED_BASE}
     * amounts, so a piece's net contribution is still {@code originalValue * multiplier}.
     *
     * <p>Non-vacuity: the live entity value is asserted against the exact expected 9.0 <em>and</em>
     * against three distinct wrong outcomes - 8.25 (the old behaviour that also scaled the
     * multiplicative factor), 6.0 (the factor dropped) and 12.0 (the additive part left unscaled) -
     * and the {@code scale()} contract is pinned with reference identity, which a copy would fail.</p>
     */
    private static void operationAwareScalingChecks(ServerLevel level) {
        ItemStack chest = damaged(Items.DIAMOND_CHESTPLATE, 0.5);
        double multiplier = ArmorDurabilityScaling.multiplier(chest);
        require(multiplier > 0.0 && multiplier < 1.0, "P1-2 fixture: damaged chestplate multiplier in (0,1)");

        Identifier armorTotalId = Identifier.fromNamespaceAndPath(TEST_NAMESPACE, "armor_add_multiplied_total");
        Identifier toughnessTotalId = Identifier.fromNamespaceAndPath(TEST_NAMESPACE, "toughness_add_multiplied_total");
        AttributeModifier armorTotal = new AttributeModifier(armorTotalId, 0.5,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        AttributeModifier toughnessTotal = new AttributeModifier(toughnessTotalId, 0.5,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

        ItemAttributeModifiers base = chest.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                ItemAttributeModifiers.EMPTY);
        AttributeModifier componentArmor = null;
        for (ItemAttributeModifiers.Entry entry : base.modifiers()) {
            if (sameAttribute(entry.attribute(), Attributes.ARMOR)) {
                componentArmor = entry.modifier();
            }
        }
        require(componentArmor != null && near(componentArmor.amount(), 8.0)
                        && componentArmor.operation() == AttributeModifier.Operation.ADD_VALUE,
                "P1-2 fixture: the chestplate's own ARMOR entry is +8 ADD_VALUE");

        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.builder();
        base.modifiers().forEach(entry -> builder.add(entry.attribute(), entry.modifier(), entry.slot()));
        builder.add(Attributes.ARMOR, armorTotal, EquipmentSlotGroup.CHEST);
        builder.add(Attributes.ARMOR_TOUGHNESS, toughnessTotal, EquipmentSlotGroup.CHEST);
        chest.set(DataComponents.ATTRIBUTE_MODIFIERS, builder.build());

        double rawArmor = raw(chest, EquipmentSlot.CHEST, Attributes.ARMOR).addValue();
        double rawToughness = raw(chest, EquipmentSlot.CHEST, Attributes.ARMOR_TOUGHNESS).addValue();
        require(near(rawArmor, 8.0) && near(rawToughness, 2.0),
                "P1-2 fixture: +8 ARMOR and +2 ARMOR_TOUGHNESS are the additive parts");

        AttributeModifier scaledTotal = ArmorDurabilityScaling.scale(chest, Attributes.ARMOR, armorTotal);
        require(scaledTotal == armorTotal,
                "P1-2 scale() returns the ADD_MULTIPLIED_TOTAL modifier itself (same instance, untouched)");
        require(scaledTotal.amount() == 0.5
                        && scaledTotal.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                "P1-2 the ADD_MULTIPLIED_TOTAL amount and operation are unchanged");
        AttributeModifier scaledAdd = ArmorDurabilityScaling.scale(chest, Attributes.ARMOR, componentArmor);
        require(scaledAdd != componentArmor && near(scaledAdd.amount(), 8.0 * multiplier)
                        && scaledAdd.operation() == AttributeModifier.Operation.ADD_VALUE,
                "P1-2 the ADD_VALUE modifier is scaled into a new instance with the same operation");

        Map<Identifier, Double> funnel = amountsFor(chest, EquipmentSlot.CHEST, Attributes.ARMOR);
        require(funnel.containsKey(armorTotalId) && funnel.get(armorTotalId) == 0.5,
                "P1-2 the funnel hands the ADD_MULTIPLIED_TOTAL modifier through at full strength");
        require(near(funnel.getOrDefault(componentArmor.id(), Double.NaN), 8.0 * multiplier),
                "P1-2 the funnel hands the ADD_VALUE modifier through scaled");

        Equipped equipped = spawn(level, List.of(new Piece(EquipmentSlot.CHEST, chest)));
        TestArmorStand stand = equipped.stand();
        stand.tick();
        double observed = stand.getAttributeValue(Attributes.ARMOR);
        double expectedValue = rawArmor * multiplier * 1.5;
        require(near(observed, expectedValue),
                "P1-2 live ARMOR == " + expectedValue + " ((8 * 0.75) * 1.5)");
        require(near(observed, 9.0), "P1-2 live ARMOR is exactly 9.0");
        require(!near(observed, 8.25),
                "P1-2 live ARMOR is NOT 8.25 (the old double-scaled ADD_MULTIPLIED_TOTAL result)");
        require(!near(observed, 6.0),
                "P1-2 live ARMOR is NOT 6.0 (the multiplicative total must still apply)");
        require(!near(observed, 12.0), "P1-2 live ARMOR is NOT 12.0 (the ADD_VALUE part must be scaled)");
        require(stand.getArmorValue() == 9, "P1-2 the armor bar is floor(9.0) == 9");

        AttributeInstance instance = stand.getAttribute(Attributes.ARMOR);
        AttributeModifier installedTotal = instance.getModifier(armorTotalId);
        require(installedTotal != null && installedTotal.amount() == 0.5
                        && installedTotal.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
                "P1-2 the installed ADD_MULTIPLIED_TOTAL modifier is on the entity at full strength");
        AttributeModifier installedAdd = instance.getModifier(componentArmor.id());
        require(installedAdd != null && near(installedAdd.amount(), 8.0 * multiplier),
                "P1-2 the installed ADD_VALUE modifier is scaled");

        double observedToughness = stand.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        require(near(observedToughness, rawToughness * multiplier * 1.5) && near(observedToughness, 2.25),
                "P1-2 live ARMOR_TOUGHNESS == 2.25 ((2 * 0.75) * 1.5)");
        require(!near(observedToughness, 2.0 * multiplier * 1.375),
                "P1-2 ARMOR_TOUGHNESS is NOT 2.0625 (double-scaled multiplicative factor)");
        require(!near(observedToughness, 2.0 * multiplier),
                "P1-2 ARMOR_TOUGHNESS is NOT 1.5 (multiplicative factor dropped)");
    }

    // ------------------------------------------------------------------ GAP-A enchantment dispatch

    /**
     * Closes the Codex P2-1 gap: disabling only the {@code EnchantmentHelper} {@code @ModifyArg} used
     * to leave the whole suite green, because no test produced a non-empty enchantment attribute
     * contribution.
     *
     * <p>The probe enchantment grants {@code minecraft:armor +1.5 add_value}. It is taken from the
     * mod datapack ({@code data/da_test/enchantment/armor_probe.json}) when that entry is registered;
     * this dev harness does not load mod datapacks into the ENCHANTMENT registry, so the same
     * enchantment is otherwise built in code and wrapped in {@code Holder.direct} - the funnel path
     * under test is identical either way
     * ({@code ItemStack.forEachModifier -> EnchantmentHelper.forEachModifier -> the enchantment's
     * ATTRIBUTES effect}), and the check name records which source was used.</p>
     *
     * <p>The raw amount is read from an undamaged twin of the same stack (multiplier 1, so the funnel
     * path is identity) and is additionally pinned to the datapack constant, and the enchantment
     * modifier id is discovered by diffing the funnel id sets rather than guessed.</p>
     */
    private static void enchantmentDispatchChecks(ServerLevel level) {
        Holder.Reference<Enchantment> registered = level.registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .get(ResourceKey.create(Registries.ENCHANTMENT,
                        Identifier.fromNamespaceAndPath(TEST_NAMESPACE, "armor_probe")))
                .orElse(null);
        Holder<Enchantment> probe = registered != null ? registered : syntheticArmorEnchantment();
        String source = registered != null
                ? "registered mod-datapack enchantment da_test:armor_probe"
                : "code-built Holder.direct(Enchantment) (mod datapack not loaded in this harness)";
        require(probe != null && probe.value() != null
                        && !probe.value().getEffects(EnchantmentEffectComponents.ATTRIBUTES).isEmpty(),
                "GAP-A fixture: the probe enchantment declares a non-empty ATTRIBUTES effect, source = " + source);
        if (probe == null || probe.value() == null) {
            return;
        }

        ItemStack enchanted = damaged(Items.DIAMOND_CHESTPLATE, 0.5);
        ItemStack pristineEnchanted = new ItemStack(Items.DIAMOND_CHESTPLATE);
        for (ItemStack stack : List.of(enchanted, pristineEnchanted)) {
            ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
            mutable.set(probe, 1);
            stack.set(DataComponents.ENCHANTMENTS, mutable.toImmutable());
        }
        require(!enchanted.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty(),
                "GAP-A fixture: the stack really carries a non-empty ENCHANTMENTS component");
        double multiplier = ArmorDurabilityScaling.multiplier(enchanted);
        require(multiplier > 0.0 && multiplier < 1.0,
                "GAP-A fixture: damaged enchanted chestplate multiplier in (0,1)");

        Set<Identifier> plainIds = amountsFor(new ItemStack(Items.DIAMOND_CHESTPLATE), EquipmentSlot.CHEST,
                Attributes.ARMOR).keySet();
        Map<Identifier, Double> enchantedDamaged = amountsFor(enchanted, EquipmentSlot.CHEST, Attributes.ARMOR);
        Set<Identifier> extra = new HashSet<>(enchantedDamaged.keySet());
        extra.removeAll(plainIds);
        require(extra.size() == 1,
                "GAP-A fixture: the enchantment adds exactly one ARMOR modifier id (" + extra + ")");
        if (extra.size() != 1) {
            return;
        }
        Identifier enchantmentId = extra.iterator().next();

        double rawEnchantment = amountsFor(pristineEnchanted, EquipmentSlot.CHEST, Attributes.ARMOR)
                .getOrDefault(enchantmentId, Double.NaN);
        require(near(rawEnchantment, ENCHANT_ARMOR_AMOUNT),
                "GAP-A fixture: at multiplier 1 the enchantment delivers its raw " + ENCHANT_ARMOR_AMOUNT
                        + " (the funnel path is identity, so this is a live raw oracle)");
        double scaledEnchantment = enchantedDamaged.getOrDefault(enchantmentId, Double.NaN);
        require(near(scaledEnchantment, rawEnchantment * multiplier),
                "GAP-A the ENCHANTMENT ARMOR modifier from EnchantmentHelper.forEachModifier is scaled"
                        + " exactly once (raw * m)");
        require(!near(scaledEnchantment, rawEnchantment),
                "GAP-A the enchantment ARMOR modifier is not left unscaled (this is the P2-1 gap)");
        require(!near(scaledEnchantment, rawEnchantment * multiplier * multiplier),
                "GAP-A the enchantment ARMOR modifier is not scaled twice");

        double rawComponent = raw(enchanted, EquipmentSlot.CHEST, Attributes.ARMOR).addValue();
        Equipped equipped = spawn(level, List.of(new Piece(EquipmentSlot.CHEST, enchanted)));
        TestArmorStand stand = equipped.stand();
        try {
            stand.tick();
            require(near(stand.getAttributeValue(Attributes.ARMOR),
                            rawComponent * multiplier + rawEnchantment * multiplier),
                    "GAP-A the enchantment's scaled ARMOR reaches the live entity attribute"
                            + " (component + enchantment, each raw * m)");
        } finally {
            // The probe enchantment is a Holder.direct value that is absent from the ENCHANTMENT
            // registry, so a stack carrying it cannot be serialized. Strip the gear and drop the stand
            // before the server saves chunks, otherwise shutdown logs an element-encoding error that
            // has nothing to do with the mod under test.
            stand.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            stand.discard();
        }
    }

    /**
     * Code-built twin of {@code data/da_test/enchantment/armor_probe.json}: same attribute, amount and
     * operation, so the enchantment dispatch is covered even when the harness does not load the mod
     * datapack into the dynamic ENCHANTMENT registry.
     */
    private static Holder<Enchantment> syntheticArmorEnchantment() {
        DataComponentMap effects = DataComponentMap.builder()
                .set(EnchantmentEffectComponents.ATTRIBUTES, List.of(new EnchantmentAttributeEffect(
                        Identifier.fromNamespaceAndPath(TEST_NAMESPACE, "enchantment.armor_probe"),
                        Attributes.ARMOR,
                        new LevelBasedValue.Constant((float) ENCHANT_ARMOR_AMOUNT),
                        AttributeModifier.Operation.ADD_VALUE)))
                .build();
        Enchantment enchantment = new Enchantment(
                Component.literal("da_test armor probe"),
                Enchantment.definition(HolderSet.empty(), 1, 1,
                        Enchantment.constantCost(1), Enchantment.constantCost(1), 1, EquipmentSlotGroup.ARMOR),
                HolderSet.empty(),
                effects);
        return Holder.direct(enchantment);
    }

    // ------------------------------------------------------------------ T1 tooltip wear lines

    /**
     * The player-visible contract for the wear tooltip, checked through the real vanilla pipeline
     * ({@code ItemStack#getTooltipLines} → {@code addDetailsToTooltip} → {@code addAttributeTooltips},
     * which is where the mixin injects).
     *
     * <p>Assertions are language independent on purpose: a dedicated server does not resolve a mod's
     * own lang entries, so the checks compare translation keys and structured components (which carry
     * the vanilla red style) rather than rendered text. The shipped lang files are validated
     * separately, including their resource path.</p>
     */
    private static void tooltipChecks(ServerLevel level) {
        // --- pristine: nothing lost, so nothing extra on the tooltip ------------------------------
        ItemStack pristine = new ItemStack(Items.DIAMOND_CHESTPLATE);
        double[] pristineLoss = WornArmorTooltip.losses(pristine);
        require(pristineLoss[WornArmorTooltip.LOSS_ARMOR] == 0.0
                        && pristineLoss[WornArmorTooltip.LOSS_TOUGHNESS] == 0.0
                        && pristineLoss[WornArmorTooltip.LOSS_KNOCKBACK_RESISTANCE] == 0.0,
                "T1 pristine chestplate reports no wear loss at all");
        require(!containsWearLine(tooltipLines(level, pristine)),
                "T1 pristine chestplate adds no wear line to the rendered tooltip");

        // --- worn: the reported loss must equal raw * (1 - multiplier) ----------------------------
        ItemStack worn = damaged(Items.DIAMOND_CHESTPLATE, 0.5);
        double multiplier = ArmorDurabilityScaling.multiplier(worn);
        require(multiplier < 1.0, "T1 fixture: the worn chestplate is damaged");
        double rawArmor = raw(worn, EquipmentSlot.CHEST, Attributes.ARMOR).addValue();
        double rawToughness = raw(worn, EquipmentSlot.CHEST, Attributes.ARMOR_TOUGHNESS).addValue();
        require(rawArmor > 0.0 && rawToughness > 0.0,
                "T1 fixture: a diamond chestplate grants both armor and armor toughness");

        double[] lost = WornArmorTooltip.losses(worn);
        require(near(lost[WornArmorTooltip.LOSS_ARMOR], rawArmor * (1.0 - multiplier)),
                "T1 reported lost armor == raw armor * (1 - multiplier)");
        require(near(lost[WornArmorTooltip.LOSS_TOUGHNESS], rawToughness * (1.0 - multiplier)),
                "T1 reported lost armor toughness == raw toughness * (1 - multiplier)");
        require(lost[WornArmorTooltip.LOSS_ARMOR] > 0.0 && lost[WornArmorTooltip.LOSS_TOUGHNESS] > 0.0,
                "T1 fixture: both losses are non-zero, so the two lines are really distinguishable");

        List<Component> wornLines = tooltipLines(level, worn);
        Component expectedArmor =
                WornArmorTooltip.line(WornArmorTooltip.WORN_ARMOR_KEY, lost[WornArmorTooltip.LOSS_ARMOR]);
        Component expectedToughness =
                WornArmorTooltip.line(WornArmorTooltip.WORN_TOUGHNESS_KEY, lost[WornArmorTooltip.LOSS_TOUGHNESS]);
        require(wornLines.contains(expectedArmor),
                "T1 the rendered tooltip carries the armor wear line (same key and red style)");
        require(wornLines.contains(expectedToughness),
                "T1 the rendered tooltip carries the armor toughness wear line");
        require(WornArmorTooltip.WORN_ARMOR_KEY.equals(translationKeyOf(expectedArmor))
                        && !WornArmorTooltip.WORN_ARMOR_KEY.startsWith("attribute.modifier"),
                "T1 the wear line is its own line, not vanilla's +N attribute line");

        int firstAttributeLine = -1;
        for (int i = 0; i < wornLines.size(); i++) {
            String key = translationKeyOf(wornLines.get(i));
            if (key != null && key.startsWith("attribute.modifier")) {
                firstAttributeLine = i;
                break;
            }
        }
        int armorWearIndex = wornLines.indexOf(expectedArmor);
        int toughnessWearIndex = wornLines.indexOf(expectedToughness);
        require(firstAttributeLine >= 0, "T1 fixture: the vanilla attribute line is present in the tooltip");
        require(armorWearIndex > firstAttributeLine && toughnessWearIndex > firstAttributeLine,
                "T1 both wear lines sit below the vanilla attribute lines");
        require(toughnessWearIndex > armorWearIndex,
                "T1 the toughness wear line follows the armor wear line");

        // --- knockback resistance is deliberately not scaled (requirement 2) -> no line -----------
        ItemStack knockback = damaged(Items.DIAMOND_CHESTPLATE, 0.5);
        ItemAttributeModifiers withKnockback = knockback
                .getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY)
                .withModifierAdded(Attributes.KNOCKBACK_RESISTANCE,
                        new AttributeModifier(Identifier.fromNamespaceAndPath(TEST_NAMESPACE, "kbr_probe"),
                                0.25, AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.ANY);
        knockback.set(DataComponents.ATTRIBUTE_MODIFIERS, withKnockback);
        require(raw(knockback, EquipmentSlot.CHEST, Attributes.KNOCKBACK_RESISTANCE).addValue() == 0.25,
                "T1 fixture: the knockback resistance contribution is really 0.25");
        double[] knockbackLoss = WornArmorTooltip.losses(knockback);
        require(knockbackLoss[WornArmorTooltip.LOSS_KNOCKBACK_RESISTANCE] == 0.0,
                "T1 knockback resistance is not scaled, so it reports no loss (requirement 2)");
        require(!tooltipLines(level, knockback).contains(
                        WornArmorTooltip.line(WornArmorTooltip.WORN_KNOCKBACK_KEY, 0.25)),
                "T1 no knockback wear line is printed while knockback resistance stays unscaled");

        // --- a damaged non-armor item must stay quiet ---------------------------------------------
        ItemStack sword = damaged(Items.DIAMOND_SWORD, 0.5);
        double[] swordLoss = WornArmorTooltip.losses(sword);
        require(swordLoss[WornArmorTooltip.LOSS_ARMOR] == 0.0
                        && swordLoss[WornArmorTooltip.LOSS_TOUGHNESS] == 0.0
                        && swordLoss[WornArmorTooltip.LOSS_KNOCKBACK_RESISTANCE] == 0.0,
                "T1 a damaged non-armor item reports no wear loss");
        require(!containsWearLine(tooltipLines(level, sword)),
                "T1 a damaged non-armor item adds no wear line");

        // --- hiding the vanilla attribute lines must hide ours too ---------------------------------
        ItemStack hidden = damaged(Items.DIAMOND_CHESTPLATE, 0.5);
        hidden.set(DataComponents.TOOLTIP_DISPLAY,
                TooltipDisplay.DEFAULT.withHidden(DataComponents.ATTRIBUTE_MODIFIERS, true));
        require(!containsWearLine(tooltipLines(level, hidden)),
                "T1 hiding the attribute lines in TooltipDisplay also hides the wear line");

        // --- the shipped lang files must sit on the path the client loads --------------------------
        String en = langFile("en_us.json");
        String zh = langFile("zh_cn.json");
        for (String key : List.of(WornArmorTooltip.WORN_ARMOR_KEY, WornArmorTooltip.WORN_TOUGHNESS_KEY,
                WornArmorTooltip.WORN_KNOCKBACK_KEY)) {
            require(en.contains("\"" + key + "\"") && en.contains("-%s"),
                    "T1 en_us.json declares " + key + " with a -%s placeholder");
            require(zh.contains("\"" + key + "\""),
                    "T1 zh_cn.json declares " + key);
        }
        require(zh.contains("耐久损耗：-%s 护甲值"),
                "T1 zh_cn armor line reads 耐久损耗：-<n> 护甲值");
        require(zh.contains("耐久损耗：-%s 盔甲韧性"),
                "T1 zh_cn toughness line uses the vanilla term 盔甲韧性");
        require(zh.contains("耐久损耗：-%s 击退抗性"),
                "T1 zh_cn knockback line uses the vanilla term 击退抗性");
        require(en.contains("Wear: -%s Armor"),
                "T1 en_us armor line reads Wear: -<n> Armor");
    }

    /** The tooltip a player would see, rendered through the real vanilla chain. */
    private static List<Component> tooltipLines(ServerLevel level, ItemStack stack) {
        return stack.getTooltipLines(Item.TooltipContext.of(level), null, TooltipFlag.NORMAL);
    }

    private static boolean containsWearLine(List<Component> lines) {
        return lines.stream().anyMatch(line -> {
            String key = translationKeyOf(line);
            return key != null && key.startsWith("tooltip.durability_armor.");
        });
    }

    private static String translationKeyOf(Component component) {
        if (component == null) {
            return null;
        }
        return component.getContents() instanceof TranslatableContents translatable ? translatable.getKey() : null;
    }

    private static String langFile(String name) {
        String path = "assets/durability_armor/lang/" + name;
        try (InputStream in = DurabilityArmorIntegrationTests.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("could not read " + path, error);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void section(String name, Runnable body) {
        REPORT.append("-- ").append(name).append('\n');
        try {
            body.run();
        } catch (Throwable error) {
            FAILURES.add(name + " threw " + error);
            REPORT.append("FAILED ").append(name).append(" threw ").append(error).append('\n');
            error.printStackTrace();
        }
    }

    private static void require(boolean condition, String name) {
        if (condition) {
            checks++;
            REPORT.append("OK ").append(name).append('\n');
        } else {
            FAILURES.add(name);
            REPORT.append("FAILED ").append(name).append('\n');
        }
    }

    private static boolean near(double actual, double expected) {
        return Math.abs(actual - expected) <= EPS;
    }

    private static double remainingRatio(ItemStack stack) {
        int max = stack.getMaxDamage();
        return (max - stack.getDamageValue()) / (double) max;
    }

    private static double closedForm(double r) {
        return 1.0 - (1.0 - r) * (1.0 - r);
    }

    private static boolean sameAttribute(Holder<Attribute> left, Holder<Attribute> right) {
        return left.value() == right.value();
    }

    private static List<Piece> pristinePieces() {
        List<Piece> pieces = new ArrayList<>();
        pieces.add(new Piece(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET)));
        pieces.add(new Piece(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE)));
        pieces.add(new Piece(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS)));
        pieces.add(new Piece(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS)));
        return pieces;
    }

    private static ItemStack damaged(ItemLike item, double remainingRatio) {
        ItemStack stack = new ItemStack(item);
        int max = stack.getMaxDamage();
        stack.setDamageValue(max - (int) Math.round(max * remainingRatio));
        return stack;
    }

    private static Equipped spawn(ServerLevel level, List<Piece> pieces) {
        TestArmorStand stand = new TestArmorStand(level);
        for (Piece piece : pieces) {
            stand.setItemSlot(piece.slot(), piece.stack());
        }
        if (!level.addFreshEntity(stand)) {
            throw new IllegalStateException("could not spawn the test armor stand in " + level.dimension());
        }
        return new Equipped(stand, pieces);
    }

    /** Raw ADD_VALUE contribution of a piece, read from the item's own component. */
    private static Raw raw(ItemStack stack, EquipmentSlot slot, Holder<Attribute> attribute) {
        ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                ItemAttributeModifiers.EMPTY);
        double[] addValue = {0.0};
        int[] entries = {0};
        int[] otherOperations = {0};
        modifiers.forEach(slot, (entryAttribute, modifier) -> {
            if (sameAttribute(entryAttribute, attribute)) {
                entries[0]++;
                if (modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                    addValue[0] += modifier.amount();
                } else {
                    otherOperations[0]++;
                }
            }
        });
        return new Raw(addValue[0], entries[0], otherOperations[0]);
    }

    private static double sumRaw(List<Piece> pieces, Holder<Attribute> attribute) {
        double total = 0.0;
        for (Piece piece : pieces) {
            total += raw(piece.stack(), piece.slot(), attribute).addValue();
        }
        return total;
    }

    private static boolean allAddValue(List<Piece> pieces) {
        for (Piece piece : pieces) {
            if (raw(piece.stack(), piece.slot(), Attributes.ARMOR).otherOperations() != 0
                    || raw(piece.stack(), piece.slot(), Attributes.ARMOR_TOUGHNESS).otherOperations() != 0) {
                return false;
            }
        }
        return true;
    }

    /** Expected attribute value: entity base + sum over pieces of raw * multiplier(stack). */
    private static double expected(LivingEntity entity, List<Piece> pieces, Holder<Attribute> attribute) {
        double total = entity.getAttributeBaseValue(attribute);
        for (Piece piece : pieces) {
            total += raw(piece.stack(), piece.slot(), attribute).addValue()
                    * ArmorDurabilityScaling.multiplier(piece.stack());
        }
        return total;
    }

    /** Expected modifier amounts by id: raw * multiplier per piece, for the given attribute. */
    private static Map<Identifier, Double> expectedById(List<Piece> pieces, Holder<Attribute> attribute) {
        Map<Identifier, Double> expected = new HashMap<>();
        for (Piece piece : pieces) {
            collectExpected(piece, attribute, expected);
        }
        return expected;
    }

    /**
     * True when the entity's attribute instance carries exactly one modifier per expected id (no
     * duplicates, no extras, no missing) and every amount is raw * multiplier.
     */
    private static boolean perIdMatches(LivingEntity entity, Holder<Attribute> attribute,
            Map<Identifier, Double> want) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            return false;
        }
        Set<Identifier> seen = new HashSet<>();
        for (AttributeModifier modifier : instance.getModifiers()) {
            if (!seen.add(modifier.id())) {
                return false;
            }
            Double expectedAmount = want.get(modifier.id());
            if (expectedAmount == null || !near(modifier.amount(), expectedAmount)) {
                return false;
            }
        }
        return seen.size() == instance.getModifiers().size() && seen.equals(want.keySet());
    }

    /**
     * Modifier amounts by id observed through the hooked overload, for ONE attribute.
     *
     * <p>Per attribute on purpose: vanilla reuses the same modifier id across a piece's ARMOR and
     * ARMOR_TOUGHNESS entries, so an id-only collector would merge two different attributes.</p>
     */
    private static Map<Identifier, Double> amountsFor(ItemStack stack, EquipmentSlot slot,
            Holder<Attribute> attribute) {
        Map<Identifier, Double> amounts = new HashMap<>();
        stack.forEachModifier(slot, (entryAttribute, modifier) -> {
            if (sameAttribute(entryAttribute, attribute)) {
                amounts.merge(modifier.id(), modifier.amount(), Double::sum);
            }
        });
        return amounts;
    }

    /** Sums the observed amounts for the given ids; {@code NaN} when one is missing (fails near()). */
    private static double sumOf(Map<Identifier, Double> amounts, Set<Identifier> ids) {
        double total = 0.0;
        for (Identifier id : ids) {
            Double amount = amounts.get(id);
            if (amount == null) {
                return Double.NaN;
            }
            total += amount;
        }
        return total;
    }

    /**
     * Scans the compiled hook class (byte code, no class loading) and returns what it found, or
     * {@code null} with a diagnostic in the report when the class cannot be read.
     *
     * <p>Loading a {@code @Mixin} class through the normal class loader makes Mixin transform it and
     * throws {@code "Mixin transformation of ... failed"}, so ASM is used instead. This keeps the
     * P1-1 guards independent of Mixin's injector ordering: whatever the ordering, the hook must not
     * substitute the {@code forEachModifier} parameter, and a {@code @ModifyArg} index must point at
     * the {@code BiConsumer} argument.</p>
     */
    private static HookShapeScan scanHookShape() {
        return scanMixinClass(HOOK_MIXIN_CLASS);
    }

    /**
     * Scans a compiled mixin class (byte code, no class loading) and returns what it found, or
     * {@code null} with a diagnostic in the report when the class cannot be read.
     *
     * <p>Loading a {@code @Mixin} class through the normal class loader makes Mixin transform it and
     * throws {@code "Mixin transformation of ... failed"}, so ASM is used instead. This keeps the
     * P1-1/G1 guards independent of Mixin's injector ordering: whatever the ordering, the hook must
     * not substitute the {@code forEachModifier} parameter, a {@code @ModifyArg} index must point at
     * the {@code BiConsumer} argument, and the rearm probe must declare its {@code @ModifyReceiver} on
     * the expected INVOKE.</p>
     */
    private static HookShapeScan scanMixinClass(String resource) {
        try (InputStream stream = DurabilityArmor.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                REPORT.append("   (").append(resource).append(" not found on the classpath)\n");
                return null;
            }
            HookShapeScan scan = new HookShapeScan();
            new ClassReader(stream).accept(scan,
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (scan.replacesForEachModifierArgument) {
                REPORT.append("   (").append(resource).append(" still replaces the forEachModifier argument)\n");
            }
            return scan;
        } catch (Throwable error) {
            REPORT.append("   (could not scan ").append(resource).append(": ").append(error).append(")\n");
            return null;
        }
    }

    /** Index of {@code Ljava/util/function/BiConsumer;} among the parameters of a method descriptor. */
    private static int biConsumerParameterIndex(String descriptor) {
        int open = descriptor.indexOf('(');
        if (open < 0) {
            return -1;
        }
        int index = 0;
        int cursor = open + 1;
        while (cursor < descriptor.length() && descriptor.charAt(cursor) != ')') {
            if (descriptor.charAt(cursor) == 'L') {
                int end = descriptor.indexOf(';', cursor);
                if (end < 0) {
                    return -1;
                }
                if ("Ljava/util/function/BiConsumer;".equals(descriptor.substring(cursor, end + 1))) {
                    return index;
                }
                cursor = end + 1;
            } else {
                cursor++;
            }
            index++;
        }
        return -1;
    }

    /** ASM scan: collects the hook's {@code @ModifyArg} targets/indices, any argument-replacing @ModifyVariable and any @ModifyReceiver. */
    private static final class HookShapeScan extends ClassVisitor {
        private final List<String> modifyArgFindings = new ArrayList<>();
        private final List<int[]> modifyArgIndexChecks = new ArrayList<>();
        private final List<String> modifyReceiverFindings = new ArrayList<>();
        private boolean replacesForEachModifierArgument;

        HookShapeScan() {
            super(Opcodes.ASM9);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            boolean enclosingIsForEachModifier = "forEachModifier".equals(name);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    if ("Lorg/spongepowered/asm/mixin/injection/ModifyArg;".equals(annotationDescriptor)) {
                        return new ModifyArgAnnotation(enclosingIsForEachModifier);
                    }
                    if ("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;".equals(annotationDescriptor)) {
                        return new ModifyVariableAnnotation(enclosingIsForEachModifier);
                    }
                    if ("Lcom/llamalad7/mixinextras/injector/ModifyReceiver;".equals(annotationDescriptor)) {
                        return new ModifyReceiverAnnotation(enclosingIsForEachModifier);
                    }
                    return null;
                }
            };
        }

        /** MixinExtras {@code @ModifyReceiver}: record the INVOKE it replaces the receiver of. */
        private final class ModifyReceiverAnnotation extends AnnotationVisitor {
            private boolean onForEachModifier;
            private String target;

            ModifyReceiverAnnotation(boolean enclosingIsForEachModifier) {
                super(Opcodes.ASM9);
                this.onForEachModifier = enclosingIsForEachModifier;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String annotationName, String annotationDescriptor) {
                if (!"at".equals(annotationName)) {
                    return null;
                }
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String elementName, Object value) {
                        if ("target".equals(elementName) && value instanceof String text) {
                            target = text;
                        }
                    }
                };
            }

            @Override
            public AnnotationVisitor visitArray(String arrayName) {
                if ("method".equals(arrayName)) {
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String ignored, Object value) {
                            if (value instanceof String text && text.contains("forEachModifier")) {
                                onForEachModifier = true;
                            }
                        }
                    };
                }
                if ("at".equals(arrayName)) {
                    // At[] is written as a single-element array here; accept both encodings.
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String atName, String atDescriptor) {
                            return atTargetVisitor();
                        }
                    };
                }
                return null;
            }

            private AnnotationVisitor atTargetVisitor() {
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String elementName, Object value) {
                        if ("target".equals(elementName) && value instanceof String text) {
                            target = text;
                        }
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (onForEachModifier && target != null) {
                    modifyReceiverFindings.add("@ModifyReceiver -> " + target);
                }
            }
        }

        private final class ModifyArgAnnotation extends AnnotationVisitor {
            private boolean onForEachModifier;
            private String target;
            private int index = Integer.MIN_VALUE;

            ModifyArgAnnotation(boolean enclosingIsForEachModifier) {
                super(Opcodes.ASM9);
                this.onForEachModifier = enclosingIsForEachModifier;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String annotationName, String annotationDescriptor) {
                // @At is a single nested annotation here even though @ModifyArg declares it as At[].
                if (!"at".equals(annotationName)) {
                    return null;
                }
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String elementName, Object value) {
                        if ("target".equals(elementName) && value instanceof String text) {
                            target = text;
                        }
                    }
                };
            }

            @Override
            public AnnotationVisitor visitArray(String arrayName) {
                if ("method".equals(arrayName)) {
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String ignored, Object value) {
                            if (value instanceof String text && text.contains("forEachModifier")) {
                                onForEachModifier = true;
                            }
                        }
                    };
                }
                if ("at".equals(arrayName)) {
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String atName, String atDescriptor) {
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String annotationName, Object value) {
                                    if ("target".equals(annotationName) && value instanceof String text) {
                                        target = text;
                                    }
                                }
                            };
                        }
                    };
                }
                return null;
            }

            @Override
            public void visit(String annotationName, Object value) {
                if ("index".equals(annotationName) && value instanceof Integer declared) {
                    index = declared;
                }
            }

            @Override
            public void visitEnd() {
                if (!onForEachModifier || target == null) {
                    return;
                }
                modifyArgIndexChecks.add(new int[]{index, biConsumerParameterIndex(target)});
                modifyArgFindings.add("@ModifyArg index=" + index + " -> " + target);
            }
        }

        private final class ModifyVariableAnnotation extends AnnotationVisitor {
            private boolean argsOnly;
            private boolean onForEachModifier;

            ModifyVariableAnnotation(boolean enclosingIsForEachModifier) {
                super(Opcodes.ASM9);
                this.onForEachModifier = enclosingIsForEachModifier;
            }

            @Override
            public AnnotationVisitor visitArray(String arrayName) {
                if (!"method".equals(arrayName)) {
                    return null;
                }
                return new AnnotationVisitor(Opcodes.ASM9) {
                    @Override
                    public void visit(String ignored, Object value) {
                        if (value instanceof String text && text.contains("forEachModifier")) {
                            onForEachModifier = true;
                        }
                    }
                };
            }

            @Override
            public void visit(String annotationName, Object value) {
                if ("argsOnly".equals(annotationName) && Boolean.TRUE.equals(value)) {
                    argsOnly = true;
                }
            }

            @Override
            public void visitEnd() {
                if (argsOnly && onForEachModifier) {
                    replacesForEachModifierArgument = true;
                }
            }
        }
    }

    private static void collectExpected(Piece piece, Holder<Attribute> attribute, Map<Identifier, Double> out) {
        ItemAttributeModifiers modifiers = piece.stack().getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                ItemAttributeModifiers.EMPTY);
        double multiplier = ArmorDurabilityScaling.multiplier(piece.stack());
        modifiers.forEach(piece.slot(), (entryAttribute, modifier) -> {
            if (sameAttribute(entryAttribute, attribute)
                    && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
                out.merge(modifier.id(), modifier.amount() * multiplier, Double::sum);
            }
        });
    }

    private static boolean noNetworkMembers(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getReturnType().getName().startsWith("net.minecraft.network")) return false;
            for (Class<?> parameter : method.getParameterTypes()) {
                if (parameter.getName().startsWith("net.minecraft.network")) return false;
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (field.getType().getName().startsWith("net.minecraft.network")) return false;
        }
        return true;
    }

    private static boolean noClientMembers(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getReturnType().getName().startsWith("net.minecraft.client")) return false;
            for (Class<?> parameter : method.getParameterTypes()) {
                if (parameter.getName().startsWith("net.minecraft.client")) return false;
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (field.getType().getName().startsWith("net.minecraft.client")) return false;
        }
        return true;
    }

    private record Piece(EquipmentSlot slot, ItemStack stack) {
    }

    private record Equipped(TestArmorStand stand, List<Piece> pieces) {
    }

    private record Raw(double addValue, int entries, int otherOperations) {
    }

    /** Probe entity: an ArmorStand that exposes {@code getDamageAfterArmorAbsorb}. */
    private static final class TestArmorStand extends ArmorStand {
        TestArmorStand(ServerLevel level) {
            super(level, 10.5, 100.0, 10.5);
            setNoGravity(true);
        }

        float absorb(DamageSource source, float damage) {
            return getDamageAfterArmorAbsorb(source, damage);
        }
    }
}
