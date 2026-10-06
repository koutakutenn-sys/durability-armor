# Durability Armor — frozen design contract (v1.0.0+mc26.2)

> Owner: Team Lead. Treat every name/decision below as frozen unless the Lead edits this file.
> All agents: read this before touching code. Report conflicts to the Lead instead of changing it.

## 1. Goal

Armor protection must decrease as armor durability decreases.

For every piece of equipped armor, with

```
r          = remaining durability / max durability
multiplier = 1 - (1 - r)^2
```

the piece's contribution must become

```
current armor value     = original armor value     * multiplier
current armor toughness = original armor toughness * multiplier
```

Knockback resistance must **not** be affected.

Hard requirements: dynamic (no permanent change to item base attributes/components), works for
vanilla armor and for armor added by other mods, and it must not double-stack attributes, behave
wrongly after re-entering a world, or desync client and server.

## 2. Verified vanilla facts (Minecraft 26.2, Fabric loader 0.19.5)

Evidence was read with `javap -p -c` against the mapped jar
`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar`.
Re-verify with javap if something looks off; do not guess.

1. `net.minecraft.world.entity.LivingEntity.collectEquipmentChanges(Map)` iterates
   `EquipmentSlot.VALUES`, compares against `lastEquipmentItems` via `equipmentHasChanged`, and for
   every changed, non-empty, non-broken stack calls
   `ItemStack.forEachModifier(EquipmentSlot, BiConsumer<Holder<Attribute>, AttributeModifier>)`.
2. The `BiConsumer` it passes is `lambda$collectEquipmentChanges$0`, which does
   `attrInstance.removeModifier(modifier.id())` then `attrInstance.addTransientModifier(modifier)`.
   => equipment attribute bonuses are **transient** modifiers keyed by the modifier's own id.
3. `ItemStack.forEachModifier(EquipmentSlot, BiConsumer)` is the **single funnel**:
   it calls `ItemAttributeModifiers.forEach(EquipmentSlot, BiConsumer)` and then
   `EnchantmentHelper.forEachModifier(ItemStack, EquipmentSlot, BiConsumer)` with the same consumer.
4. `LivingEntity.equipmentHasChanged(a, b)` == `!ItemStack.matches(a, b)`, and
   `ItemStack.matches` == same count + `isSameItemSameComponents`. Damage is the
   `DataComponents.DAMAGE` component, so **changing durability counts as an equipment change** and
   vanilla re-applies (remove+add, idempotent) the modifiers on the following tick.
5. `LivingEntity.tick()` calls the private `detectEquipmentUpdates()` (constant pool ref at
   `LivingEntity.tick`+125).
6. `LivingEntity.getArmorValue()` == `Mth.floor(getAttributeValue(Attributes.ARMOR))`.
7. `LivingEntity.getDamageAfterArmorAbsorb(source, damage)` calls `hurtArmor(...)` and then
   `CombatRules.getDamageAfterAbsorb(this, damage, source, getArmorValue(), (float) getAttributeValue(Attributes.ARMOR_TOUGHNESS))`.
8. `AttributeModifier` is an immutable record `(Identifier id, double amount, Operation operation)`.
   `ItemAttributeModifiers` is an immutable final record holding `List<Entry>`; `Entry` holds
   `(Holder<Attribute> attribute, AttributeModifier modifier, EquipmentSlotGroup slot, Display display)`.

## 3. Frozen architecture

**Single hook:** mixin `net.minecraft.world.item.ItemStack#forEachModifier(EquipmentSlot, BiConsumer)`
and scale the modifier amount for `Attributes.ARMOR` and `Attributes.ARMOR_TOUGHNESS` by the
multiplier of the stack (`this`).

Why this is the right funnel (and why the alternatives were rejected):

* It is exactly the code path vanilla uses to push equipment attributes into the entity's
  `AttributeMap`, so the scaled value flows into damage reduction (`CombatRules`), the armor bar
  (`getArmorValue()`), and any other mod that reads `getAttributeValue(Attributes.ARMOR)`.
* It has the `ItemStack` in scope, so per-piece durability is available without extra state.
* It touches no item component and no "base attribute": `AttributeModifier`s are immutable and we
  only build scaled copies for the transient modifier application. Nothing persists.
* The id is unchanged, and vanilla removes-then-adds by id, so double stacking is impossible.
* The value is computed **on the server only** (vanilla gates `detectEquipmentUpdates()` behind
  `!level().isClientSide()` inside `LivingEntity.tick`), and it reaches the client through vanilla's
  own `ClientboundUpdateAttributesPacket`: `AttributeInstance.setDirty` → `AttributeMap.onAttributeModified`
  → `ServerEntity.sendDirtyEntityData` → packet. The snapshot is built from
  `AttributeInstance.getModifiers()` — i.e. it carries our scaled *transient* equipment modifiers —
  and the client handles it as a full replacement (`setBaseValue` + `removeModifiers()` + re-add), so
  it can neither diverge nor accumulate. The mod therefore needs no packets of its own, and there is
  no desync by construction. (Earlier revisions of this document wrongly said "client and server
  compute identical values"; the client does not run the funnel at all.)
* Rejected: adding our own permanent/transient modifier (state to remove on unequip, NBT and
  re-entry anomalies, double stacking risk); hooking `getArmorValue()`/`getAttributeValue()`
  (misses other mods and `getAttribute(..).getValue()`); data-driven component editing (permanent).

**Cadence:** vanilla already re-runs the funnel when the damage component changes, so the scaled
value refreshes on the tick after a piece takes damage. Document the ≤1 tick lag; do not add extra
refresh machinery without Lead approval (it would change behaviour and risk).

## 4. Frozen contract (names other agents may rely on)

| Item | Value |
| --- | --- |
| mod id | `durability_armor` |
| group / packages | `dev.durabilityarmor`, `dev.durabilityarmor.mixin`, `dev.durabilityarmor.test`, `dev.durabilityarmor.test.mixin` |
| archive name | `durability-armor-<version>.jar` |
| main entrypoint | `dev.durabilityarmor.DurabilityArmor` (already written) |
| mixin config | `src/main/resources/durability_armor.mixins.json`, package `dev.durabilityarmor.mixin`, mixins `["ItemStackMixin"]`, compatibilityLevel `JAVA_25` |
| **required mixin class** | `dev.durabilityarmor.mixin.ItemStackMixin` |
| **required API class** | `dev.durabilityarmor.ArmorDurabilityScaling` |
| version | `gradle.properties` → `mod_version=1.0.0+mc26.2` |

`ArmorDurabilityScaling` public API (used by the mixin and by the integration tests):

```java
/** 1 - (1 - r)^2 with r = remaining/max durability; 1.0 for stacks without durability. */
public static double multiplier(ItemStack stack);

/** True for Attributes.ARMOR and Attributes.ARMOR_TOUGHNESS only. Knockback resistance is unaffected. */
public static boolean scalesAttribute(Holder<Attribute> attribute);

/** Convenience: scales a modifier amount when the stack wears the attribute, else returns the input. */
public static AttributeModifier scale(ItemStack stack, Holder<Attribute> attribute, AttributeModifier modifier);

/**
 * Wraps a (attribute, modifier) consumer so every modifier it receives is first passed through
 * scale(stack, ...). Returns the ORIGINAL consumer when multiplier(stack) == 1.0, and null for a
 * null consumer. Pure and stateless; no idempotency marker (idempotence comes from injecting into
 * the two dispatch calls, see §3 and the ItemStackMixin javadoc).
 */
public static BiConsumer<Holder<Attribute>, AttributeModifier> wrap(
        ItemStack stack, BiConsumer<Holder<Attribute>, AttributeModifier> consumer);
```

Revision note (post-P1-1): the mixin no longer replaces the method parameter. It now uses two
`@ModifyArg` handlers on the dispatch calls inside the method body — `ItemAttributeModifiers.forEach`
(index 1) and `EnchantmentHelper.forEachModifier` (index 2; Mixin's index is descriptor-based and does
not count the receiver). This makes elytraslot 3.0.0's HEAD-cancel + re-entry scale exactly once in
either injector order. See `docs/compat-audit.md` addendum A for the independent verification.

`multiplier` must be a pure function (no caching, no side effects) and must return `1.0` when the
stack is empty, has no `MAX_DAMAGE` component, or has `maxDamage <= 0`.
A broken stack (remaining `<= 0`) yields `0.0`.
`UNBREAKABLE` is **not** an exemption: `ItemStack.getDamageValue()` still reports the DAMAGE
component of an unbreakable stack, so the multiplier is computed from MAX_DAMAGE + DAMAGE exactly as
for any other stack (ordinary unbreakable gear has damage 0 and therefore still yields `1.0`).
This was a Codex final-acceptance finding — the requirement `r = remaining/max durability` has no
unbreakable exemption.

Invalid input must never throw: `multiplier(null)` returns `1.0`.

## 5. File ownership (no two writers on one path)

| Path | Writer |
| --- | --- |
| `build.gradle`, `settings.gradle`, `gradle.properties`, `.gitignore`, `LICENSE`, `test-server.properties`, `gradle/wrapper/**` | Lead (frozen) |
| `src/main/resources/**`, `src/main/java/dev/durabilityarmor/DurabilityArmor.java` | Lead (frozen) |
| `src/main/java/dev/durabilityarmor/ArmorDurabilityScaling.java`, `src/main/java/dev/durabilityarmor/mixin/ItemStackMixin.java` | `impl-core` |
| `src/integrationTest/**` | `impl-tests` |
| `docs/compat-audit.md` | `compat-audit` |
| `docs/code-review.md` | `reviewer` |
| `docs/DESIGN.md`, `README.md`, `changelog.log` | Lead |

## 6. Build & test commands

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
cd /Users/administrator/CodexWorkspace/durability-armor
./gradlew build
./gradlew runIntegrationTest -PacceptMinecraftEula=true   # writes run-test/test-result.txt
```

The `runIntegrationTest` accept flag only re-uses the EULA state the user already accepted for this
machine's existing server runs; do not create a new EULA acceptance elsewhere.

**Gradle serialisation:** only one agent may run Gradle in this project at a time. The Lead owns the
authoritative build; teammates must coordinate (see the shared task board) before invoking
`./gradlew`.

## 7. Known limitations to document honestly

* Tooltips still print the item's own attribute component values (unchanged by design: the item's
  base attributes are never modified). The HUD armor bar and damage reduction use the scaled value.
* A durability change is picked up on the next tick (vanilla equipment-change detection cadence).
* Armor points/toughness granted by a source that does not go through
  `ItemStack.forEachModifier` (e.g. a mod that injects straight into an `AttributeInstance`) are not
  scaled. Nothing else in the standard pipeline is missed.
* `scale` keeps `ADD_MULTIPLIED_TOTAL` modifiers **unchanged** and only scales the `amount` of
  `ADD_VALUE` / `ADD_MULTIPLIED_BASE` modifiers. Vanilla `AttributeInstance.calculateValue` computes
  `value = (base + Σ ADD_VALUE + base·Σ ADD_MULTIPLIED_BASE) · (1 + Σ ADD_MULTIPLIED_TOTAL)` and
  ARMOR/ARMOR_TOUGHNESS have base 0 on vanilla entities, so preserving the multiplicative total while
  scaling the additive contribution is exactly what makes the final value equal
  `original value × multiplier` for a piece (Codex finding P1-2: `+8 ADD_VALUE` and
  `+0.5 ADD_MULTIPLIED_TOTAL` at m=0.75 must give `(8·0.75)·1.5 = 9.0`, not `8.25`).
  Residual limitation: an `ADD_MULTIPLIED_TOTAL` contribution is a global factor rather than a
  contribution owned by one piece, so it stays at full strength; attribute **base** values are never
  scaled either (armor points injected by mods directly into an `AttributeInstance` are out of scope).
* `multiplier` uses an exact `== 1.0` comparison. `1 - (1-r)^2` rounds to exactly `1.0` when
  `(1-r)^2 < 2^-53`, i.e. `maxDamage > ~9.5e7` with a single point of damage; the skipped correction
  is < 1.2e-16 relative. Irrelevant in practice, deliberately not special-cased.
* `compatibilityLevel` is `JAVA_25` (house style in this workspace; the reference mod ships the same).
  Mixin 0.8.7 clamps it to its own maximum and logs
  `Compatibility level JAVA_25 ... higher than the maximum level supported by this version of mixin (JAVA_13)`.
  The mod loads and applies correctly; the warning is expected in every log.
