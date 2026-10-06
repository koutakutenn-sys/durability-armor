# Compatibility audit — `durability_armor` (MC 26.2 / Fabric loader 0.19.5)

| | |
| --- | --- |
| Task | `task-3` — independent compatibility audit (vanilla call sites, mods, sync) |
| Author | teammate `compat-audit` (read-only for code; only this file is written) |
| Date | 2026-10-06 ~14:56 +0800 |
| Frozen design | `docs/DESIGN.md` v1.0.0+mc26.2 |
| Vanilla artifact | `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar`, sha256 `a549fd7c…c756a5` |
| Method | `javap -p -c/-v` on the mapped jar + all mod jars; Vineflower 1.12.0 (`~/.gradle/caches/modules-2/.../vineflower-1.12.0.jar`) decompile of a targeted class subset, cross-checked against bytecode. Mod/vanilla jars were only unzipped read-only into `/tmp`. No Gradle was run. |

> **T5 update (2026-10-06, later revision):** the hook was reworked from one `@ModifyVariable(argsOnly)` to two `@ModifyArg`
> injections on the dispatch calls. The revision pinned below is the *originally audited* one and is kept for history; the
> reworked revision is audited in **Addendum A** (§A.0 for the new hashes, §A.4 for the re-verdict — P1-1 is resolved there).
> Nothing in §0–§9 below was deleted; only this note and the `[resolved …]` markers were added.

**Implementation revision this audit was written against** (files are owned by `impl-core`; if they change, the mixin-level
conclusions must be re-checked):

* `src/main/java/dev/durabilityarmor/mixin/ItemStackMixin.java` sha256 `da78964d…ce4ca1` — `@ModifyVariable(method = "forEachModifier(Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V", at = @At("HEAD"), argsOnly = true)` wrapping the `BiConsumer`.
* `src/main/java/dev/durabilityarmor/ArmorDurabilityScaling.java` sha256 `1652f13d…de98453` — `scale()` returns `new AttributeModifier(modifier.id(), modifier.amount() * multiplier, modifier.operation())` (id and operation preserved) and the identity instance when `multiplier == 1.0`.
* `src/main/resources/durability_armor.mixins.json` sha256 `e534461a…51649c` — mixin listed under `mixins` (not `client`), `fabric.mod.json` has `"environment": "*"` → the hook is present on an integrated server and on a dedicated server.

---

## 0. Verdict on the three stated acceptance criteria

| Criterion (任务书原文) | Verdict | Basis |
| --- | --- | --- |
| 避免属性重复叠加 (no attribute double-stacking) | **PASS for vanilla and for the audited implementation**; one concrete conditional exception with mods → **P1-1** **[resolved by the reworked hook — see §A.4]** | `LivingEntity.lambda$collectEquipmentChanges$0` does `removeModifier(id)` then `addTransientModifier(modifier)`; `addTransientModifier` → `addModifier` throws if the id is already present, so a same-id remove-then-add cannot stack. The mixin preserves the id. Exception: elytraslot 3.0.0 re-enters the same method with the already-wrapped consumer (§5.1). |
| 重进世界异常 (no anomaly after re-entering a world) | **PASS** | Scaled modifiers are transient; only `AttributeInstance.permanentModifiers` is saved (`AttributeMap.pack` → `AttributeInstance.pack`), so nothing scaled can be persisted. On load/respawn the value is recomputed from the (persisted) item damage on the first equipment-change detection. §4 |
| 客户端/服务端不同步 (no client/server desync) | **PASS by construction** | The client never runs the equipment-attribute funnel (`LivingEntity.tick` gates `detectEquipmentUpdates()` behind `!level().isClientSide()`), and its ARMOR/ARMOR_TOUGHNESS instance is written only by `ClientboundUpdateAttributesPacket` handling, which does `setBaseValue` + `removeModifiers()` + re-add (full replace of the server-computed, already-scaled modifier set). §3 |

No **P0** finding. P1/P2 findings: §6. Test requests for `impl-tests`: §7. Unknowns: §9.

---

## 1. The funnel: every vanilla call site of `ItemStack.forEachModifier`

### 1.1 Complete caller enumeration (not "spot checks")

I scanned the constant pool of **all 10 952 classes** of the mapped merged jar for the method-name strings, then
disassembled every class that references the `AttributeModifiers` component (`32` classes).

* Classes whose constant pool contains `forEachModifier`: **only 3** —
  `net/minecraft/world/item/ItemStack` (defines both overloads),
  `net/minecraft/world/item/enchantment/EnchantmentHelper` (defines its own two overloads),
  `net/minecraft/world/entity/LivingEntity` (the only caller).
* Bytecode call sites of `ItemStack.forEachModifier(EquipmentSlot, BiConsumer)` in the whole jar: **2, both in `LivingEntity`**
  * `LivingEntity.collectEquipmentChanges` — `LivingEntity.txt:8059` (`197: invokevirtual #3357`), decompiled `LivingEntity.java:2964`
  * `LivingEntity.stopLocationBasedEffects(ItemStack, EquipmentSlot, AttributeMap)` — `LivingEntity.txt:10558` (`8: invokevirtual #3357`), decompiled `LivingEntity.java:3826`
* `ItemStack.forEachModifier(EquipmentSlotGroup, TriConsumer)` (the **other**, un-hooked overload) is called once in the whole jar:
  `ItemStack.addAttributeTooltips` — `ItemStack.txt:2134` (`62: invokevirtual #1083`), decompiled `ItemStack.java` (`addAttributeTooltips`). Used for item tooltips only.
* `ItemAttributeModifiers.forEach(…)` (the component-level path the hooked method feeds) is called **only from `ItemStack`** (both overloads, `ItemStack.txt:2254` and `:2273`) — no other vanilla class consumes it.
* `ItemAttributeModifiers.compute(Holder, double, EquipmentSlot)` (the component-level path that bypasses the funnel) is called **only from `Mob.getApproximateAttributeWith`** (`Mob.txt` at the `DataComponents.ATTRIBUTE_MODIFIERS` read), decompiled `Mob.java:652-658`.

Because both call sites live inside the same method pair (`collectEquipmentChanges` = apply, `stopLocationBasedEffects` = remove),
the frozen "single funnel" claim in DESIGN §2.3 holds, with the important refinement that the funnel is used for **both
application and removal**:

* apply: `current.forEachModifier(slot, (attr, mod) -> { instance.removeModifier(mod.id()); instance.addTransientModifier(mod); })`
* remove: `previous.forEachModifier(slot, (attr, mod) -> instance.removeModifier(mod))` (`removeModifier(AttributeModifier)` → `removeModifier(id())`)

**Consequence for the audit of our hook:** the mixin's wrapper is executed on the removal path too. That is harmless *only because
the implementation keeps the original `modifier.id()`* — removal is id-based and the scaled amount is ignored. If a future change
ever renamed the id (e.g. a `/durability` suffix), the removal path would no longer delete the modifier and every equipment change
would leak/double-stack. This is the single most load-bearing implementation invariant; it currently holds
(`ArmorDurabilityScaling.java:104`).

### 1.2 What each call site does with ARMOR / ARMOR_TOUGHNESS, and impact of scaling there

| # | Call site (class#method, evidence) | What it does with the consumer | Impact of scaling ARMOR / ARMOR_TOUGHNESS there |
| --- | --- | --- | --- |
| 1 | `LivingEntity#collectEquipmentChanges` (`LivingEntity.txt:8059`) | remove-by-id + `addTransientModifier` on the entity's `AttributeMap` for every changed, non-empty, non-broken equipment slot | **Intended.** This is the value used by damage reduction, the armor bar, Jade, firstaid, scoreboard `minecraft.armor`, `/attribute … get`. |
| 2 | `LivingEntity#stopLocationBasedEffects` (`LivingEntity.txt:10558`) | `removeModifier(modifier)` (by id) for the previous stack, then `EnchantmentHelper.stopLocationBasedEffects(previous, this, slot)` for the enchantment location effects | **No impact.** Removal is by id; the scaled amount is discarded. Verified current impl preserves the id. |
| 3 | `ItemStack#addAttributeTooltips` → group overload (`ItemStack.txt:2134`) | renders tooltip lines from the item's own component | **No impact / by design** — the mixin descriptor only matches the `(EquipmentSlot, BiConsumer)` overload, so tooltips keep printing the unscaled component values (DESIGN §7). |
| 4 | `Mob#getApproximateAttributeWith` → `ItemAttributeModifiers.compute` (`Mob.java:652`) | AI gear-choice heuristic (`compareArmor`, `compareWeapons`) | **Unscaled by design**, see **P2-1** — mob AI compares armor pieces by their *undamaged* value. No attribute value is corrupted. |
| 5 | `EnchantmentHelper.forEachModifier(ItemStack, EquipmentSlot, BiConsumer)` (`EnchantmentHelper.java:432`) | invoked *inside* `ItemStack.forEachModifier`; forwards `EnchantmentEffectComponents.ATTRIBUTES` effects | **Scaled too (intended).** In vanilla 26.2 no enchantment targets ARMOR/ARMOR_TOUGHNESS: I extracted all 43 vanilla enchantment JSONs from `minecraft-extracted_server.jar` — attributes used are only `burning_time, explosion_knockback_resistance, mining_efficiency, movement_efficiency, movement_speed, oxygen_bonus, sneaking_speed, submerged_mining_speed, sweeping_damage_ratio, water_movement_efficiency`; none is `minecraft:armor`/`armor_toughness`. So this path is a no-op in vanilla and only matters for modded armor enchantments. |
| 6 | `ItemAttributeModifiers.compute` via any other reader | none found | **n/a** (single caller, #4). |
| 7 | `/attribute` command (`AttributeCommand`) | reads `AttributeInstance.getModifiers()` for suggestions / `modifier value get`; `base set` writes the base value; `modifier add` writes a **permanent** modifier | **Intended/neutral.** `modifier value get` will report the scaled amount for a damaged piece (correct: it is the applied modifier). `base set`/`modifier add` are independent additive contributions (P2-4). |

Neighbouring/attribute-adjacent classes checked with **no** callback into the funnel: `CombatRules` (only reads the two floats),
`Hud.extractArmor/renderArmor` (`player.getArmorValue()`), `ServerPlayer` (`getArmorValue()` → `ObjectiveCriteria.ARMOR`),
`Mob`, `Attributes`, `ArmorMaterial`, `MobEffect`, `AttributeMap`, `AttributeInstance`, `AttributeSupplier`,
`EnchantmentAttributeEffect`, `SetAttributesFunction`, `Item$Properties`, `AttributeModifiersPredicate`,
`DataComponentPredicates`, `PotionContents`, `TridentItem`, `MaceItem`, `ToolMaterial`, `Items`, `ClientboundRespawnPacket`,
`Waypoint`, `ItemStackComponentizationFix`, `AttributesRenameLegacy`, `ItemStackUUIDFix`.

---

## 2. Every path that can influence ARMOR / ARMOR_TOUGHNESS in vanilla 26.2

`Attributes.ARMOR = RangedAttribute("attribute.name.armor", 0.0, 0.0, 30.0).setSyncable(true)`,
`Attributes.ARMOR_TOUGHNESS = RangedAttribute(…, 0.0, 0.0, 20.0).setSyncable(true)` (`Attributes.java:14-17`).
`AttributeInstance.calculateValue()` sums ADD_VALUE → ADD_MULTIPLIED_BASE → ADD_MULTIPLIED_TOTAL and finally applies
`Attribute.sanitizeValue`, i.e. `RangedAttribute.sanitizeValue` = `Mth.clamp(value, min, max)` (NaN → min), verified with javap.
So the **sum** is clamped, after our per-modifier scaling.

| # | Path | Mechanism (evidence) | Through the funnel? | Scaled? | Severity |
| --- | --- | --- | --- | --- | --- |
| A | Equipment modifiers (items in MAINHAND/OFFHAND/FEET/LEGS/CHEST/HEAD/BODY/SADDLE) | `collectEquipmentChanges` → `forEachModifier` (`LivingEntity.java:2964`) | **yes** | **yes** | intended |
| B | Enchantment `minecraft:attributes` effects | same call (`EnchantmentHelper.forEachModifier`, `EnchantmentHelper.java:432`) | **yes** | **yes** | intended |
| C | `AttributeSupplier` per-entity-type base values | `AttributeMap.getValue` falls back to the supplier when no instance exists (`AttributeMap.java:133-150`) | no | no | expected (durability-independent) |
| D | Base value: NBT `attributes` list (base) | `LivingEntity.readAdditionalSaveData` (`LivingEntity.txt:1721`, server-only guard) → `AttributeMap.apply` → `AttributeInstance.apply(Packed)` | no | no | expected |
| E | `/attribute <t> <a> base set` | `AttributeCommand.setAttributeBase` → `setBaseValue` | no | no | expected |
| F | `/attribute <t> <a> modifier add …` | `AttributeCommand` → `AttributeInstance.addPermanentModifier` (`AttributeCommand.java:355`) | no | no | expected, already in DESIGN §7 |
| G | Mob-effect attribute templates | `MobEffect.addAttributeModifiers` → `removeModifier` + **`addPermanentModifier`** (`MobEffect.java:170-178`); only called from server-side `LivingEntity.onEffectAdded/onEffectUpdated` (`LivingEntity.java:1076-1098`, guarded by `!level().isClientSide()`) | no | no | expected |
| H | Mob-specific modifiers (`EnderMan`, `Witch`, `Piglin`, `Zombie`, `ZombifiedPiglin`, `Shulker`, `Rabbit`, `Strider`, `SulfurCube`) | their own `addTransientModifier`/`addPermanentModifier` calls — none targets ARMOR/ARMOR_TOUGHNESS in 26.2 (`Attributes.ARMOR` is referenced by only 5 classes jar-wide: `CombatRules`, `LivingEntity`, `Mob`, `Attributes`, `ArmorMaterial`) | no | no | expected |
| I | Enchantment **location-changed** attribute effects | `EnchantmentAttributeEffect.onChangedBlock` → `living.getAttributes().addTransientAttributeModifiers(...)` (`EnchantmentAttributeEffect.java:44-55`), i.e. **straight into `AttributeMap`, unscaled**; removal symmetric via `onDeactivated` | **no** | **no** | **P2-2** (vanilla `soul_speed` uses it for movement attributes only; no ARMOR user in this instance) |
| J | Component-level readers | `Mob.getApproximateAttributeWith` → `ItemAttributeModifiers.compute` (**P2-1**); tooltips → group overload (**P2-5**) | no | no | **P2-1**, **P2-5** |
| K | Item creation / loot | `Item$Properties`, `ArmorMaterial.createAttributes`, `SetAttributesFunction` build the `ATTRIBUTE_MODIFIERS` component. The resulting modifier then takes path A/B when equipped | n/a (writes the component, not the attribute) | yes once equipped | intended |
| L | Respawn / dimension change | server: `ServerPlayer.restoreFrom` → `assignBaseValues` + (`restoreAll`) `assignPermanentModifiers` (`ServerPlayer.java:1472-1479`); client: `ClientPacketListener.handleRespawn` → `assignAllValues(old)` when `ClientboundRespawnPacket.shouldKeep(1)` (=`KEEP_ATTRIBUTE_MODIFIERS`) else `assignBaseValues` (`ClientPacketListener.java:1271-1275`) | copy only | copies whatever exists (transient too, on the client) | see §3.2/§4 |
| M | `removeModifiers()` / `replaceFrom` | `ClientPacketListener.handleUpdateAttributes`, `AttributeInstance.replaceFrom` | n/a | n/a | see §3.2 |

**The gap is I/J — nothing else.** Both are reads/contributions that do not depend on the item's durability (I) or are pure
AI/UI heuristics (J). Path B is covered by the same hook (it is inside the hooked method, not a bypass).

---

## 3. Client / server analysis (acceptance criterion: no desync)

### 3.1 Server side (authoritative)

1. `LivingEntity.tick()` → `if (!level().isClientSide()) { … detectEquipmentUpdates(); … }` (`LivingEntity.java:2746-2775`; bytecode `LivingEntity.txt:7460-7500`). So **`collectEquipmentChanges` / `stopLocationBasedEffects` run only on the server.**
2. `handleEquipmentChanges` (`LivingEntity.java:3000-3008`) sends `ClientboundSetEquipmentPacket` with a **copy** of the new stacks (including the `DAMAGE` component) to tracking players.
3. `ServerEntity.sendChanges()` → `sendDirtyEntityData()` (`ServerEntity.java:337-358`) sends `ClientboundUpdateAttributesPacket(id, attributesToSync)` to `sendToTrackingPlayersAndSelf`, i.e. **including the player themselves**, whenever an attribute was marked dirty via `AttributeMap.onAttributeModified`.
4. `ClientboundUpdateAttributesPacket`'s constructor serialises `instance.getBaseValue()` **and `instance.getModifiers()`** — the *full* modifier map including transient equipment modifiers (`UpdateAttrsPacket.txt:9-43`, `getModifiers` at offset 63/38). So the server's **scaled** modifier is what crosses the wire, with the original id.
5. Newly tracked entities get the full set via `ServerEntity.sendPairingData` → `getSyncableAttributes()` (`ServerEntity.java:286-289`) in the same `ClientboundBundlePacket` as the equipment packet.

### 3.2 Client side (no independent recomputation, no double apply)

* `ClientPacketListener.handleUpdateAttributes` (`ClientPacketListener.txt:6157+`): per snapshot → `AttributeInstance.setBaseValue(base)` then **`removeModifiers()`** then re-add every transmitted modifier with `addTransientModifier`. A snapshot is therefore a **complete replacement**, so a second application of the same id can never stack on the client, and a stale local value cannot survive an update.
* The client never runs the equipment funnel (§3.1), so it does not compute a second, potentially divergent value. `MobEffect.addAttributeModifiers` is server-only too (`LivingEntity.java:1076-1098`), and the only class apart from `AttributeMap` itself that references `AttributeInstance$Packed` is `LivingEntity` (the save/load path), whose attribute load is server-only (`LivingEntity.txt:1707-1730`). `AttributeInstance.apply(Packed)` is therefore only reached from that path.
* Result: client ARMOR/ARMOR_TOUGHNESS = server ARMOR/ARMOR_TOUGHNESS. The only divergence window is ordinary packet latency, and it resolves in the server's favour on every attribute update.

### 3.3 Ordering

The equipment packet is produced from `LivingEntity.tick` → `detectEquipmentUpdates` → `handleEquipmentChanges`, and the
attribute packet from `ChunkMap.tick` → `ServerEntity.sendChanges` → `sendDirtyEntityData()` (`ChunkMap.txt` — the
`sendChanges` call is inside `ChunkMap#tick`, bytecode line 2889; `ServerLevel.tick` calls `chunkSource.tick(...)` at offset 342
and ticks entities later in the same method). I did **not** verify which of the two runs first inside one server tick; if the
attribute flush runs first, the dirty flag set by the funnel is flushed on the next tick. Either way the client is consistent,
because it never recomputes from the item (§3.2) and every attribute packet is a full replacement, so the worst case is a
one-tick lag (the same lag DESIGN §7 already documents).

### 3.4 Verdict

**No client/server desync source introduced by the hook.** No packet of our own is needed; the scaled value rides the vanilla
attribute snapshot. The one thing to keep in mind (documented in DESIGN §7) is the ≤1 tick lag between a durability change and
the refreshed attribute, which is vanilla's own equipment-change cadence (`ItemStack.matches` → `isSameItemSameComponents` →
component equality, and `DAMAGE` is a component; `ItemStack.java:562`, `:589`).

---

## 4. Persistence / world re-entry

| Step | Evidence | Contains scaled equipment modifier? |
| --- | --- | --- |
| Save (`LivingEntity.addAdditionalSaveData`) | stores `getAttributes().pack()` under key `"attributes"` with `AttributeInstance.Packed.LIST_CODEC` (`LivingEntity.txt:1560-1600`, `#1065 AttributeMap.pack`) | — |
| `AttributeMap.pack()` | `for each instance: instance.pack()` (`AttributeMap.txt:266-295`) | — |
| `AttributeInstance.pack()` | returns `Packed(attribute, baseValue, List.copyOf(this.permanentModifiers.values()))` (`AttributeInstance.txt:445-458`) | **no — only `permanentModifiers`** |
| which map does the equipment path write? | `addTransientModifier` → `addModifier` → only `modifierById` + `modifiersByOperation` (`AttributeInstance.txt:128-156`, `:185-190`); `permanentModifiers` is written exclusively by `addPermanentModifier` / `addOrReplacePermanentModifier` / `apply(Packed)` / `assignPermanentModifiers` | so the scaled modifier is **never** in the saved list |
| Load | `readAdditionalSaveData` `"attributes"` → `AttributeMap.apply(List<Packed>)` → `AttributeInstance.apply(Packed)` (`LivingEntity.txt:1707-1730`; `AttributeMap.txt:297-320`; `AttributeInstance.txt:460-503`) — `apply` copies packed entries into `modifierById`, `modifiersByOperation` **and `permanentModifiers`**; server-only (`level != null && !isClientSide`) | n/a, equipment modifiers are not in the file |
| Player respawn (server) | `ServerPlayer.restoreFrom` → `assignBaseValues` (+ `assignPermanentModifiers` when `restoreAll`) (`ServerPlayer.java:1472-1479`) — transient modifiers are **not** copied | no |
| Respawn / dimension change (client) | `handleRespawn` → `assignAllValues(old)` if `KEEP_ATTRIBUTE_MODIFIERS`, else `assignBaseValues` (`ClientPacketListener.java:1271-1275`); `assignAllValues` → `replaceFrom` copies all modifiers incl. transient (`AttributeInstance.txt:411-443`) | copies the **current, correct** value; then the server re-syncs (pairing data / dirty attributes) |
| First tick after load / first tracking | `lastEquipmentItems` is empty on a fresh entity, so every non-empty slot counts as changed → the scaled modifier is (re)applied; `sendPairingData` ships the full attribute set | recomputed from persisted `DAMAGE` |
| Chunk unload → reload | identical to save/load (entity NBT round-trip) | no |
| Item in inventory / dropped / chest | the mixin only alters the modifier objects handed to the consumer; the item component is untouched (verified: the impl only constructs a new immutable `AttributeModifier`) | no |

**Verdict: no scaled value can be persisted anywhere.** The only persisted attribute state is `permanentModifiers`, which the
funnel never writes (it calls `addTransientModifier`, unchanged by the hook — the hook replaces the `BiConsumer` argument, not the
consumer body in `LivingEntity`). After a reload the value is recomputed from the item's `DAMAGE` component, so no "sticky"
scaled value can survive a world re-entry.

---

## 5. Installed mod interactions (instance `versions/26.2-Fabric/mods`)

Scan method: all `.class` entries of all 60 jars ending in `.jar` unzipped read-only to `/tmp/modscan` (90 MB; the directory
also holds 14 `.jar.disable`/`.jar.disabled` files which are not loaded) and searched for
`forEachModifier`, `ItemAttributeModifiers`, `ARMOR_TOUGHNESS`, `getArmorValue`, `collectEquipmentChanges`,
`stopLocationBasedEffects`, `addTransientModifier`/`addPermanentModifier`, then `javap -v` on every mixin class that references
`LivingEntity`, `ItemStack`, `AttributeInstance`, `AttributeMap`, `CombatRules` or `Hud`. All mod datapacks were scanned for
enchantment/item data touching armor attributes. Config files of the mods behind the findings were read read-only.

### 5.1 elytraslot 3.0.0 — **P1-1: injector co-location + recursive re-entry (double scaling)**

Jar sha256 `09db9eee…887b96`. `com/warwa/elytraslot/mixin/ItemStackModifierMixin` is declared in `elytraslot-common.mixins.json`
(common section) and injects into **exactly the method our mixin modifies**:

```
@Inject(method = "forEachModifier(Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V",
        at = @At("HEAD"), cancellable = true)
private void elytraslot$bodyGliderKeepsChestModifiers(EquipmentSlot slot, BiConsumer<Holder<Attribute>, AttributeModifier> consumer, CallbackInfo ci)
```
(`javap -v` annotation dump; bytecode `elytra_ItemStackModifierMixin.txt:170-227`). Body: return unless
`slot == EquipmentSlot.BODY`; `ItemStack self = this; if (!Gliders.isChestGlider(self)) return;` then
**`ci.cancel()`** and **`self.forEachModifier(EquipmentSlot.CHEST, (attr, mod) -> consumer.accept(attr, new AttributeModifier(mod.id().withSuffix("/elytraslot_body"), mod.amount(), mod.operation())))`**
(`lambda$…$0` at `:229-248`).

`Gliders.isChestGlider` = stack has `DataComponents.GLIDER` and `Equippable.slot() == CHEST` (javap of
`com.warwa.elytraslot.Gliders`), i.e. a vanilla elytra.

**Why this matters.** elytraslot re-enters `ItemStack.forEachModifier` *with the very `BiConsumer` object it received*. If our
`@ModifyVariable` at HEAD has already replaced the argument, that object is our wrapper, and the nested invocation wraps it a
second time → the amount is scaled **twice** (`multiplier²`) for ARMOR/ARMOR_TOUGHNESS on that stack. If Mixin applies
elytraslot's `@Inject` first, the callback forwards the original consumer and the nested call wraps exactly once.
Both injectors have default order: `InjectionInfo.parseOrder` reads the annotation member `order` and defaults to `1000`
(`InjectionInfo.txt` bytecode, `sipush 1000`); `MixinTargetContext.applyInjections(int)` applies injectors grouped by that order,
and within one group in the order the mixins were applied — which depends on mixin-config/mod load order. I could not resolve
that order statically (see §9).

**Reachability in this instance is real**: `config/rearm/config.toml` has `armorRebalance = true`,
`armorMultiplier = 2.0` and `enchantmentBasedToughness = true` with `protection = 0.3`, and rearm's
`ItemStackMixin.rearm$addToughness` adds an **ARMOR_TOUGHNESS** modifier (id `minecraft:armor.<helmet|chestplate|leggings|boots|body>`,
ADD_VALUE) to any equippable stack that carries one of the listed enchantments — an enchanted elytra qualifies and, in the BODY
slot, its modifiers are re-emitted by elytraslot through the CHEST call, which is exactly the nested invocation.

**Recommendation (concrete):** make the wrapper idempotent, e.g. a private marker `interface DurabilityScaled { BiConsumer<…> delegate; }`
and `if (consumer instanceof DurabilityScaled) return consumer;` before wrapping. That keeps the frozen single-hook design and
removes the order dependency. (Test request T1/T2 below.)

Not affected: elytraslot's other mixins (`EnchantmentHelperMixin` → `EnchantmentHelper.runIterationOnEquipment`,
`LivingEntityEquipMixin` → `onEquipItem`/`isEquippableInSlot`/`canEquipWithDispenser`, `EquippableSwapMixin` →
`Equippable.swapWithEquipmentSlot`) do not touch the attribute funnel.

### 5.2 lithium 0.25.3 — no impact, one thing to keep in mind

Jar sha256 `fdde92e2…4ac563`. `lithium.mixin.entity.equipment_tracking.equipment_changes.LivingEntityMixin` injects
`@Inject(method = "collectEquipmentChanges(Ljava/util/Map;)Ljava/util/Map;", at = @At("HEAD"), cancellable)` and returns `null`
early when `equipment.lithium$hasUnsentEquipmentChanges()` is false (`lithium_equipment_LivingEntityMixin.txt:153-157`), plus
`@Inject(method = "detectEquipmentUpdates()V", at = @At(value = "INVOKE", target = "…handleHandSwap…"))` to reset the flag.
`resetEquipmentChanged` deliberately skips `Player` instances, so **players always take the full vanilla comparison**, and mobs
take a change-tracked fast path driven by `EntityEquipment.set` (`EntityEquipmentMixin.updateOnSet` / `CountChangeSubscriber`).
When the method *does* run, our hook applies exactly as in vanilla; when it is skipped there was no change to apply.
`lithium.mixin.collections.attributes.AttributeMapMixin` only swaps the dirty-tracking sets for `fastutil ReferenceOpenHashSet`
(identity semantics) — no effect on remove-then-add-by-id. No impact found; see test T3 for the mob/durability case.

### 5.3 firstaid 1.3.1 (`…-soundfix-tinnitus-fix.jar`) — positive interaction, worth a regression test

Jar sha256 `4d5530b0…a68528`. `ichttt.mods.firstaid.common.util.ArmorUtils.getValueFromAttributes(Holder<Attribute>, EquipmentSlot, ItemStack)`
computes per-piece ARMOR / ARMOR_TOUGHNESS by calling **`stack.forEachModifier(slot, consumer)`** itself (bytecode at the
`forEachModifier` invoke) and then applies its own additive/multiplicative aggregation (`getArmor`, `getArmorToughness`,
`applyArmorModifier`, `applyArmorToughnessModifier`, `getGlobalRestAttribute`). Because it uses the same funnel, firstaid's
per-piece armor reads automatically become durability-scaled — the desired behaviour — with no double application (one callback
per invocation). firstaid has no mixin on `collectEquipmentChanges`/`AttributeMap`; its `LivingEntityHealthMixin` targets
`hurtServer`, `actuallyHurt`, `setHealth`, and its `Attributes.ARMOR` use is confined to `ArmorUtils`. Regression test T4.

### 5.4 rearm 2.5.6 — interacts with both the modifier list and the ARMOR cap

Jar sha256 `9c929ed7…68d54d`; `config/rearm/config.toml` shows the rebalance active.

* `ItemStackMixin` uses MixinExtras `@ModifyReceiver` on `ItemAttributeModifiers.forEach(EquipmentSlot, BiConsumer)` (and the
  group variant) **inside both `forEachModifier` overloads** (`rearm_ItemStackMixin.txt:323-360`) to substitute
  `instance.withModifierAdded(Attributes.ARMOR_TOUGHNESS, new AttributeModifier(id("armor." + slotName), toughness, ADD_VALUE), slotGroup)`.
  That extra modifier reaches the consumer we wrapped, so it **is** scaled by durability — consistent with the design.
  Note the deliberate id overlap with `ArmorMaterial`'s own `armor.<armorType>` toughness id (`ArmorMaterial.txt:72-98`): if both
  target the same slot, the id-keyed remove-then-add keeps only the last one — vanilla semantics, unchanged by us.
* `ArmorMaterialMixin` rewrites the `defense` map of built-in armor materials from `totalArmorOverrides`
  (netherite 40, diamond 32, iron/chainmail 24 …) — so the armor *amounts* we scale are rearm's, not vanilla's.
* `AttributeMixin.increaseArmorCap` `@ModifyConstant(method = "<clinit>")` on `Attributes` sets the ARMOR cap to
  `20.0 * armorMultiplier` = **40.0** while the rebalance is on.
* `client.HudMixin.scaleArmorBar` `@ModifyReturnValue`-style injection on `Player.getArmorValue()` inside
  `Hud.extractArmor/renderArmor` divides by `armorMultiplier` for display only.
* `CombatRulesMixin` rewrites the clamp in `CombatRules.getDamageAfterAbsorb` and the protection divisor — downstream of our
  scaled inputs, no double counting.

**P2-3 (cap clamp):** because `RangedAttribute.sanitizeValue` clamps the **sum** after our per-modifier scaling, an armor total
above the cap hides the first part of the durability loss (e.g. netherite 40 = cap 40 is fine, but any stacked source pushing
the sum over 40 will keep the effective value pinned at 40 until the scaled sum drops below it). Worth a test with the pack's
"over-cap" setups (itemalchemy armor is listed in `config/rearm/armor_materials.txt`).

### 5.5 Other mods checked (no interaction with the funnel or with ARMOR/ARMOR_TOUGHNESS)

| Mod | What it does | Verdict |
| --- | --- | --- |
| Jade 26.2.11 | `EntityHealthAndArmorProvider$Client` reads `LivingEntity.getArmorValue()` (bytecode) | shows the **scaled** value in the overlay — intended |
| TACZ-Refabricated 1.1.8 | `MeleeWeaponIndex`, `PotionTooltipUtil` reference `ItemAttributeModifiers` (attack damage/tooltips), `LivingEntitySpeedModifier` adds MOVEMENT_SPEED | no ARMOR/ARMOR_TOUGHNESS involvement; the mixin's `scalesAttribute` gate leaves ATTACK_DAMAGE untouched (important: `EquipmentSlot.VALUES` includes the hand slots) |
| mcpitanlib 4.0.7 | `AttributeModifiersComponentBuilder`, `CompatAttributeModifiersComponent`, `CompatEntityAttributeInstance`, `CompatEntityAttributes` — API/builders, not a mixin on our path | no interaction |
| lithium (also) `ItemStackMixin`, `EntityEquipmentMixin` | component/durability tracking for inventory change listeners | no attribute-modifier path |
| Iris 1.11.4 | `MixinHud`, `MixinClientPacketListener`, `IrisExclusiveUniforms` (reads armor value for a shader uniform) | read-only consumer |
| elytra-tuning, ElytraCancel, elytra-fluid-flight | LivingEntity mixins for gliding/ticking; no attribute funnel | no interaction |
| exprepair, UncraftingTable, Item Alchemy(+expansion), CarryOn, InventoryProfilesNext, MouseTweaks, REI, modmenu, sodium, continuity, lambdynamiclights, camerapture, ThirstWasTaken2, overpowered-again, appleskin, villagers-savior, villagers-helper, diggus-maximus, too-cheap, trade-cycling, justenoughbackups, screenshotstudio, speedometermod, pantry(+api), stringduperfix, gravity-duplication-return, removeenchantmentincompatibility, CraftableTotemOfUndying, DurabilityViewer, ExplorersCompass, NaturesCompass, xaero*, do_a_barrel_roll(.disabled), bettercombat(.disabled) | none references `forEachModifier`, `collectEquipmentChanges`, `stopLocationBasedEffects`, or injects into `AttributeInstance`/`AttributeMap` | no interaction |
| fzzy_config, cloth-config, architectury, cicada-lib, ForgeConfigAPIPort, mixson, PlayerAnimationLib, fzzy/iris deps, fabric-api, fabric-language-kotlin | libraries; no attribute funnel use | no interaction |

Whole-jar checks that back the table: only **lithium** references `collectEquipmentChanges`/`stopLocationBasedEffects`/
`detectEquipmentUpdates`; only **elytraslot**, **rearm** and **firstaid** reference `forEachModifier`; only **mcpitanlib**,
**firstaid**, **TACZ** reference `addTransientModifier`/`addPermanentModifier` and none of them for ARMOR/ARMOR_TOUGHNESS.
All 6 mod-provided enchantment JSONs and all 43 vanilla enchantment JSONs were checked: none grants ARMOR/ARMOR_TOUGHNESS.

---

## 6. Findings, ranked

### P0
None.

### P1
1. **P1-1 — elytraslot 3.0.0 can double-scale ARMOR/ARMOR_TOUGHNESS.** **[RESOLVED in the reworked hook — see §A.4; kept as
   the historical finding and rationale for the rework.]** elytraslot's `@Inject(HEAD, cancellable)` on the *same*
   `ItemStack.forEachModifier(EquipmentSlot, BiConsumer)` recursively calls the same method with the consumer it received
   (`ci.cancel()` + `self.forEachModifier(CHEST, consumer)`); if our `@ModifyVariable(HEAD)` ran first, the wrapper is wrapped
   again → `multiplier²`. Only the `EquipmentSlot.BODY` chest-glider path is affected, but it is reachable with the pack's
   active `rearm` config (enchanted elytra → rearm adds an ARMOR_TOUGHNESS modifier).
   **Action:** make the wrapper idempotent (marker interface/`instanceof` guard) — smallest change that removes the order
   dependency while keeping the frozen single-hook design. **Test T1/T2.**

### P2
1. **P2-1 — mob gear AI compares unscaled armor.** `Mob.getApproximateAttributeWith` → `ItemAttributeModifiers.compute`
   (`Mob.java:652-658`) reads the item component directly. A mob wearing worn armor will still judge a piece by its full value.
   **Action:** document as an accepted limitation (no correctness impact); no code change without Lead approval.
2. **P2-2 — enchantment *location-changed* attribute effects bypass the funnel.** `EnchantmentAttributeEffect.onChangedBlock`
   → `living.getAttributes().addTransientAttributeModifiers(...)` applies unscaled, and `onDeactivated` removes by id
   (`EnchantmentAttributeEffect.java:44-61`). Vanilla `soul_speed` only uses movement attributes and no installed mod adds an
   armor enchantment, so this is latent. **Action:** document; if a mod ever adds `attributes`-type ARMOR under
   `minecraft:location_changed`, its bonus will not scale (and it can fight a same-id scaled modifier).
3. **P2-3 — armour sum is clamped after per-modifier scaling.** `RangedAttribute.sanitizeValue` = `Mth.clamp`; with rearm's
   rebalance the cap is 40 (`AttributeMixin.increaseArmorCap`, confirmed in §A.3 to target the `doubleValue=30.0` ARMOR max) and
   `totalArmorOverrides` can reach it, so over-cap setups show a delayed armor loss. **Action:** document; test T5.
4. **P2-4 — base value / permanent modifiers / `/attribute` remain unscaled.** By design (DESIGN §7). Note `/attribute … <armor>
   modifier value get <id>` will now report the scaled amount for a damaged piece (correct, but surprising in an admin context).
5. **P2-5 — tooltips show unscaled values, and so does anything using the group overload.** `ItemStack.addAttributeTooltips`
   uses `forEachModifier(EquipmentSlotGroup, TriConsumer)`, which the mixin does not touch (deliberate, DESIGN §7).
6. **P2-6 — verify lithium's change-tracking for mobs with damaged armor.** **[CHECKED in §A.1: not a defect — a pure DAMAGE
   change still sets lithium's `hasUnsentEquipmentChanges` flag, and players never clear it; kept only as a regression test
   (T3).]** Lithium replaces the "did the equipment change" test
   for non-players with its own flag; if that flag ever misses a pure `DAMAGE` change, the scaled value (and vanilla's unscaled
   value) would be stale for that mob. **Test T3.**

---

## 7. Test requests for `impl-tests` (do not implement here; sent to the Lead)

Plain unit/integration tests: T1, T6, T9, T12. Tests that need the real client and (where stated) the real mod set:
T2, T3, T4, T5, T7, T8, T10, T11.

1. **T1 (mixin idempotence, no game):** call `ItemStack.forEachModifier(slot, consumer)` on a damaged armor stack whose consumer
   itself re-enters `stack.forEachModifier(slot, forwardedConsumer)` (a faithful model of elytraslot's mixin), and assert the
   resulting `AttributeInstance` value equals `amount × multiplier`, **not** `amount × multiplier²`. This is the deterministic
   proxy for P1-1.
2. **T2 (in-game elytraslot, manual/mixin harness):** with elytraslot + rearm (`armorRebalance`, `enchantmentBasedToughness`
   on, as configured), enchant an elytra with Protection, wear it in the elytraslot slot, damage it, and assert
   `player.getAttributeValue(Attributes.ARMOR_TOUGHNESS)` is not below the single-multiplier value (i.e. no `mult²`).
3. **T3 (lithium + mobs):** mob (zombie with armor) takes damage that wears its armor; assert via `/attribute @e[…]` or a
   command block that its ARMOR value drops within 1–2 ticks; repeat with lithium disabled to compare.
4. **T4 (firstaid):** damaged armor + firstaid; assert per-piece `ArmorUtils.getArmor(stack, slot)` follows `1-(1-r)²` and that
   firstaid's damage reduction uses the same number as vanilla's `CombatRules` path (no double counting).
5. **T5 (cap/cumulative):** rearm rebalance active; damage a full set and record `getArmorValue()` per damaged piece; assert it
   equals `min(cap, Σ amount_i × multiplier_i)` (documents P2-3, catches unexpected clamping).
6. **T6 (no double-stack regression):** equip → damage → unequip → re-equip → damage a piece repeatedly; assert
   `AttributeInstance.getModifiers()` contains exactly one modifier per id at every step (id set identical to vanilla without
   the mod) and that no `IllegalArgumentException("Modifier is already applied on this attribute!")` appears in the log.
7. **T7 (client/server parity):** on a dedicated server, damage a piece and assert `Hud` armor icons and
   `ClientboundUpdateAttributesPacket` contents match the server value on the next tick (F3 + a debug command / Jade overlay).
8. **T8 (re-entry):** save+quit+reload with damaged armor; assert the first tick after load computes the same scaled value, that
   the entity NBT `attributes` list contains no equipment modifier ids, and that no modifier accumulates across repeated
   reloads (compare `getModifiers()` sizes).
9. **T9 (tooltip/unchanged-component regression):** assert the item's `ATTRIBUTE_MODIFIERS` component and the tooltip text are
   byte-identical before/after taking damage (guards "no permanent change" requirement).
10. **T10 (held-item boundary):** damage a sword/bow/TACZ gun; assert ATTACK_DAMAGE is unchanged (the wrapper must not touch
    non-armor attributes on the hand slots, which also flow through `collectEquipmentChanges`).
11. **T11 (`/attribute` and scoreboard):** assert `minecraft.armor` scoreboard and `/attribute … modifier value get` report the
    scaled value after damage (documented behaviour, not a bug).
12. **T12 (unbreakable / full durability / broken):** assert `multiplier == 1.0` for unbreakable and full-durability stacks, and
    that a broken armor piece contributes 0 (vanilla already skips broken stacks in `collectEquipmentChanges`, so also assert the
    modifier is removed by `stopLocationBasedEffects`).

---

## 8. Explicit "checked, no impact" list (so it is not re-litigated)

* Tooltips (`ItemStack.addAttributeTooltips`, group overload) — unscaled by design.
* Attack damage / attack speed / knockback from weapons in the hand slots — the wrapper is attribute-gated; `scalesAttribute` is
  false for them.
* Knockback resistance from `ArmorMaterial.createAttributes` (same id as the armor/toughness entries, different attribute) —
  untouched.
* Elytra flight (`GLIDER` component), encumbrance, mob AI attribute reads — no ARMOR/ARMOR_TOUGHNESS funnel use.
* `/attribute … base set`, `/attribute … modifier add/remove`, NBT `attributes`, mob-effect attributes — independent contributions.
* Armor bar rendering (`Hud.extractArmor`), Jade armor element, rearm `scaleArmorBar` — read the live attribute, hence scaled.
* Scoreboard `ObjectiveCriteria.ARMOR` (`ServerPlayer`) — reads the live attribute, hence scaled.
* `AttributeInstance.calculateValue` operation order (ADD_VALUE → ADD_MULTIPLIED_BASE → ADD_MULTIPLIED_TOTAL) — our change only
  affects the amounts of `add_value` equipment entries in practice; a modded `add_multiplied_base/total` armor modifier would be
  scaled multiplicatively on its amount, which matches the design intent.
* Client-side mob effects / NBT loads — server-only, so they cannot write a divergent client value.

---

## 9. Unknowns (not guessed)

1. **Mixin injector ordering between our `@ModifyVariable(HEAD)` and elytraslot's `@Inject(HEAD, cancellable)`.** Both use the
   default order `1000`; the tie-break is the mixin-config/mixin application order, which I could not derive from the FML/Fabric
   loader ordering statically. Both outcomes are described in §5.1; T1/T2 pin it down. This is the only reason P1-1 is stated
   conditionally. **[Superseded: the reworked hook does not depend on this order any more — §A.4.]**
2. **No runtime verification was performed by this task** (Gradle is owned by `impl-core`, and no game run was made). Everything
   above is static evidence: mapped-jar bytecode/decompile + mod-jar bytecode + mod config files.
3. **rearm's `AttributeMixin.increaseArmorCap` constant targeting.** **[RESOLVED in §A.3: it is a MixinExtras
   `@ModifyExpressionValue(method="<clinit>", at=@At(value="CONSTANT", args="doubleValue=30.0"))` on `Attributes`, i.e. exactly
   the ARMOR `RangedAttribute` max 30.0 → `20.0 × armorMultiplier`.]**
4. **Actual `multiplier` values in-game** (e.g. mid-durability steps) were not executed; only the formula and the clamping
   behaviour were verified statically.
5. **Installed-mod list completeness** — I audited the 60 jars ending in `.jar` in `versions/26.2-Fabric/mods` (the 14
   `.jar.disable`/`.jar.disabled` files were inspected by name only, not loaded). Mods installed elsewhere (e.g. resource packs,
   datapacks in `config/compatdatapacks76`, or `defaultconfigs`) were not audited; `config/compatdatapacks76` was not opened.
6. **Whether any mod in another instance/loader (the `26.1.2` neoform cache) adds ARMOR attribute modifiers via a bypass** —
   out of scope; only this instance was audited.
7. **`ServerEntity.updateInterval`** for the exact entity types was not traced to the end; it only affects how quickly an
   attribute update is pushed (the update is always sent when the attribute is dirty on the next eligible tick).

---

# Addendum A — `task-5` follow-up audit (hook rework, lithium, sync chain, rearm)

| | |
| --- | --- |
| Task | `task-5` (revision 2) — appended to the `task-3` audit; nothing above was deleted |
| Date | 2026-10-06 ~15:30 +0800 |
| Scope | (1) lithium DAMAGE-change detection, (2) server→client sync-chain completeness, (3) rearm + `@ModifyArg` coexistence, (4) re-audit of the reworked hook |
| Method | same as §1 (javap on the mapped jar, `javap -v` on mod mixins, bytecode reading of Mixin 0.17.4+mixin.0.8.7 and MixinExtras 0.5.5 — the latter is bundled in Fabric Loader 0.19.5 as `META-INF/jars/mixinextras-fabric-0.5.5.jar`). No Gradle, no game run. |

### A.0 Audited revision (supersedes the hashes pinned in the header of §1–§9)

| File | sha256 (reworked revision, 2026-10-06 15:26) | Status vs the `task-3` revision |
| --- | --- | --- |
| `src/main/java/dev/durabilityarmor/mixin/ItemStackMixin.java` | `b0166d4abb95ed5206747e410933382e19e3422b92fb597d99c8d6c8a7dbb0e5` | **replaced**: one `@ModifyVariable(argsOnly)` → two `@ModifyArg` (dispatch args) |
| `src/main/java/dev/durabilityarmor/ArmorDurabilityScaling.java` | `90eeb7d588e76175ddee77f4cc7edd1d1f0c932954f4b76caf08a41a31dda893` | `multiplier()`/`scalesAttribute()`/`scale()` bodies unchanged vs `1652f13d…`; added `public static BiConsumer<…> wrap(ItemStack, BiConsumer<…>)` + import |
| `src/main/resources/durability_armor.mixins.json` | `e534461af364680d904c0dd9d4cb5fe8908e93ba43f405ff9e67a63e4351649c` (unchanged) | still `mixins: ["ItemStackMixin"]`, `injectors.defaultRequire = 1` |
| `src/main/resources/fabric.mod.json` | `ed72cc9b2fe4a51912fa5bd64785dde9f3925b47cbe3e812342d1b585d88c07d` | `"environment": "*"` (unchanged) |

### A.1 LITHIUM — verdict: **NOT a P0; a pure `DataComponents.DAMAGE` change still triggers re-application**

**One-line answer:** lithium does *not* narrow the comparison (it is still vanilla's `equipmentHasChanged` → `ItemStack.matches` →
component equality); it only adds a "did anything change at all?" fast path, and a DAMAGE write sets that flag through lithium's own
change-tracker. For **players the flag is never cleared at all**, so the full vanilla comparison runs every tick.

Evidence (jar `lithium-fabric-0.25.3+mc26.2.jar`, sha256 `fdde92e2…4ac563`):

1. **What lithium injects into the detection** — `net.caffeinemc.mods.lithium.mixin.entity.equipment_tracking.equipment_changes.LivingEntityMixin`
   (`@Mixin(LivingEntity)`) has exactly two handlers (`javap -v`):
   * `skipSentEquipmentComparison(CallbackInfoReturnable<Map<…>>)` —
     `@Inject(method = "collectEquipmentChanges(Ljava/util/Map;)Ljava/util/Map;", at = @At("HEAD"), cancellable = true)`;
     body (bytecode offsets 0-20): `if (!((EquipmentInfo) this.equipment).lithium$hasUnsentEquipmentChanges()) cir.setReturnValue(null);`
     → when the flag is false, vanilla's whole comparison loop is skipped (returns `null`, so `handleEquipmentChanges`/the
     equipment packet are skipped too).
   * `resetEquipmentChanged(CallbackInfo)` — `@Inject(method = "detectEquipmentUpdates()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;handleHandSwap(Ljava/util/Map;)V"))`;
     body (offsets 0-19): `if (!(this instanceof Player)) ((EquipmentInfo) this.equipment).lithium$onEquipmentChangesSent();`
     → **for `Player` the flag is never cleared.** No other class in lithium references `detectEquipmentUpdates`, and lithium has
     **no** reference to `equipmentHasChanged` at all (0 hits jar-wide); the only `isSameItemSameComponents` hits are
     `net/caffeinemc/mods/lithium/common/hopper/HopperHelper` and `net/caffeinemc/mods/lithium/common/entity/item/ItemEntityList$1`
     (item-merge/hopper code, not equipment). So the comparison itself is **untouched vanilla code**.
2. **Who writes `hasUnsentEquipmentChanges`** (`EntityEquipmentMixin implements EquipmentInfo`): constructor initialiser `true`
   (offset 16); `invalidateData()` → `true` (offsets 10-12); `initializeData()` → `true` (offsets 10-12); `onEquipmentReplaced(new, old)`
   → `true` (offsets 28-30); **`lithium$notify(ItemStack, int)` → `true` (offsets 0-2)**; `lithium$onEquipmentChangesSent()` → `false`
   (offsets 8-10, non-players only).
3. **The tracker chain that reaches `lithium$notify`**:
   * `EntityEquipmentMixin` subscribes itself (data 0) to every non-empty equipped stack: `initializeData()` offsets 76-82
     (`stack.lithium$subscribe(this, 0)`), `onEquipmentReplaced` offsets 33-46/51-64 (unsubscribe old, subscribe new).
   * `lithium.mixin.util.item_component_and_count_tracking.ItemStackMixin` (`@Mixin(ItemStack)`) implements
     `ChangePublisher<ItemStack>`/`ChangeSubscriber<PatchedDataComponentMap>`: `lithium$subscribe` offsets 17-25 calls
     `startTrackingChanges()` on the first subscription, which (offsets 0-9) subscribes **the ItemStack to its own
     `components` map** (`((ChangePublisher) this.components).lithium$subscribe(this, 0)`); `lithium$notify(PatchedDataComponentMap, int)`
     (offsets 32-51) forwards to its own subscribers.
   * `PatchedDataComponentMapMixin` (`@Mixin(PatchedDataComponentMap)`) `trackBeforeChange` —
     `@Inject(method = "ensureMapOwnership()V", at = @At("HEAD"))`; body: `subscriber.lithium$notify(this, 0)`.
4. **A durability change really goes through `ensureMapOwnership`** (vanilla side):
   `ItemStack.hurtAndBreak` → `applyDamage` → `setDamageValue(int)` (`ItemStack.java:379-381`) →
   `ItemStack.set(DataComponents.DAMAGE, v)` (`ItemStack.txt:1482-1489`) → `PatchedDataComponentMap.set(type, value)`, whose
   **first instruction is `ensureMapOwnership()`** (`PatchedDCM.txt`, offset 0). The same is true for `remove`, `applyPatch`,
   `restorePatch`, `clearPatch` and `setAll` (all start with `ensureMapOwnership()`), so every component-mutation path notifies.
   Item count changes are covered separately by `@Inject(method = "setCount(I)V", at = @At("HEAD")) beforeChangeCount` and
   `lithium$notifyCount` → `onEquipmentReplaced(stack, EMPTY)`.
5. **Therefore:** DAMAGE change → notify → `hasUnsentEquipmentChanges = true` → `collectEquipmentChanges` runs →
   `equipmentHasChanged` = `!ItemStack.matches(previous, current)` → `isSameItemSameComponents` compares the component maps and
   `DAMAGE` is a component (`ItemStack.java:562`, `:589`) → `true` → `forEachModifier` re-applies the (scaled) modifier.
   The lithium fast path can only skip when *nothing* changed, in which case there is nothing to re-apply.

**Residual unknown / in-game check (only if we want runtime confirmation):** this is static evidence; the decisive in-game check
is test **T3**: give a zombie/armor stand armor, damage it, and assert `/attribute <target> minecraft:armor get` (or Jade's armor
element) drops within 1-2 ticks, then repeat with lithium disabled for comparison. Watching `mixin.debug` for
`collectEquipmentChanges` would also show whether lithium's early return fired.

### A.2 Server→client sync chain (complete, with the one correction)

1. `AttributeInstance.setDirty()` (`AttributeInstance.txt:243-252`): `this.dirty = true; this.onDirty.accept(this);`
   — reached by `addModifier` (used by `addTransientModifier`), `removeModifier`, `setBaseValue`, `apply(Packed)`, `replaceFrom`.
2. `onDirty` is `AttributeMap::onAttributeModified`: `AttributeMap.getInstance` → `supplier.createInstance(this::onAttributeModified, holder)`
   (`AttributeMap.java:48`); `onAttributeModified` (`AttributeMap.java:28-33`) adds the instance to `attributesToUpdate` and — since
   `Attributes.ARMOR.isClientSyncable()` is true (`Attributes.java:14`) — also to `attributesToSync`.
3. `ServerEntity.sendDirtyEntityData()` (`ServerEntity.java:337-358`, bytecode `ServerEntity.txt:1028+`):
   `Set<AttributeInstance> attributes = living.getAttributes().getAttributesToSync(); if (!attributes.isEmpty()) synchronizer.sendToTrackingPlayersAndSelf(new ClientboundUpdateAttributesPacket(this.entity.getId(), attributes)); attributes.clear();`
   — `sendToTrackingPlayersAndSelf` means **the owning player receives it as well**. It is called from
   `ServerEntity.sendChanges()`, which `ChunkMap.tick()` invokes for tracked entities (`ChunkMap.txt` line 2889, method
   `ChunkMap#tick` at bytecode 2802).
4. `ClientboundUpdateAttributesPacket`'s public constructor (`UpdateAttrsPacket.txt:9-43`) builds, per `AttributeInstance`, an
   `AttributeSnapshot(instance.getAttribute(), instance.getBaseValue(), instance.getModifiers())`. **Correction to the task
   wording:** the packet uses `getModifiers()` (the *full* `modifierById` map: transient **and** permanent), **not**
   `AttributeInstance.pack()`. `pack()` serialises only `permanentModifiers` and is used exclusively for NBT persistence
   (`LivingEntity.addAdditionalSaveData` → `AttributeMap.pack()` → `AttributeInstance.pack()`, §4). The conclusion still holds —
   and is in fact stronger — because `getModifiers()` definitely contains the transient scaled equipment modifier with its
   original id. `AttributeSnapshot` streams `attribute`, `base` and `modifiers` (`MODIFIER_STREAM_CODEC` = id/amount/operation).
5. Client `ClientPacketListener.handleUpdateAttributes` (`ClientPacketListener.txt:6157+`, offsets 100-201):
   `getInstance(attribute)` → `setBaseValue(snapshot.base())` → `removeModifiers()` → `addTransientModifier(m)` for every
   transmitted modifier. That is a **complete replacement** of the instance's modifier set, so no stale/duplicated value can
   survive and no local recomputation exists to diverge (the client never runs the equipment funnel, §3.1).
6. First-time tracking uses the same packet via `ServerEntity.sendPairingData` → `getSyncableAttributes()`
   (`ServerEntity.java:286-289`), bundled together with the equipment packet.
7. **`sentAttributesSnapshot` does not exist in 26.2** (jar-wide grep for `sentAttributesSnapshot`/`attributesSnapshot` = 0 hits).
   The mechanism is the dirty-set above, not a snapshot-value comparison; the task's recollection matches older versions.

Chain verdict: server re-apply → `setDirty` → `attributesToSync` → `ClientboundUpdateAttributesPacket` (full modifier set) →
client full replacement. **Complete, no gap.**

### A.3 rearm coexistence and Mixin `@ModifyArg` index semantics

**rearm's injection type/point** (`rearm-fabric-2.5.6+26.2.jar`, sha256 `9c929ed7…68d54d`):

* `me.pajic.rearm.mixin.ItemStackMixin` has two handlers, both **MixinExtras `@ModifyReceiver`** (`javap -v` annotation dump):
  * `addToughness(ItemAttributeModifiers, EquipmentSlot, BiConsumer)` —
    `@ModifyReceiver(method = "forEachModifier(Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/component/ItemAttributeModifiers;forEach(Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V"))`
    → **the same invoke our `@ModifyArg(index = 1)` targets**, but the *receiver* (operand 0), not the consumer.
  * the same for the `(EquipmentSlotGroup, TriConsumer)` overload (an invoke our mixin never touches).
  * Handler body: `rearm$addToughness(instance)` returns `instance` unchanged unless `CompatFlags.armorRebalanceActive()` and
    `config.armor.enchantmentBasedToughness` and the stack has `EQUIPPABLE` + one of `protection 0.3 / blast_protection 0.8 /
    projectile_protection 0.3 / fire_protection 0.1 / magic_protection 0.8` per level; then
    `instance.withModifierAdded(Attributes.ARMOR_TOUGHNESS, new AttributeModifier(id("armor." + <helmet|chestplate|leggings|boots|body>), toughness, ADD_VALUE), EquipmentSlotGroup.bySlot(equippable.slot()))`.
* `me.pajic.rearm.mixin.AttributeMixin.increaseArmorCap(double)` — **MixinExtras `@ModifyExpressionValue(method = "<clinit>", at = @At(value = "CONSTANT", args = "doubleValue=30.0"))`** on `net.minecraft.world.entity.ai.attributes.Attributes`,
  gated by `@IfModAbsent("apothic_attributes")` (not installed → active): returns `armorRebalance ? 20.0 * armorMultiplier : original`.
  With the instance's `config/rearm/config.toml` (`armorRebalance = true`, `armorMultiplier = 2.0`) the ARMOR `RangedAttribute`
  max becomes **40.0** — this resolves the §9.3 unknown (it *is* the ARMOR max, exactly `30.0`).

**Mixin `@ModifyArg` index semantics (does the receiver count?).** From `ModifyArgInjector.injectAtInvoke`
(`ModifyArgInjector.txt`): `Type[] args = Type.getArgumentTypes(((MethodInsnNode) node.getCurrentTarget()).desc)` — the
**descriptor arguments only**; then `ArgOffsets argOffsets = node.getDecoration("argOffsets", ArgOffsets.DEFAULT)`,
`applicableArgs = argOffsets.apply(args)`, `argIndex = argOffsets.getArgIndex(findArgIndex(target, applicableArgs))`.
`ArgOffsets.DEFAULT` is the identity (`ArgOffsets$Default`: offset 0, length 255, `getArgIndex(i) = i`, `apply(args) = args`).
`findArgIndex` validates `args[index].equals(returnType)` and throws `InvalidInjectionException("Specified index … is invalid")`
otherwise. Conclusion:

* `ItemAttributeModifiers.forEach(EquipmentSlot, BiConsumer)` (invokevirtual): descriptor args = `[EquipmentSlot, BiConsumer]`
  → **`index = 1` is the `BiConsumer`** ✔ (the receiver is *not* part of the array — that is why MixinExtras needs its own
  `@ModifyReceiver`).
* `EnchantmentHelper.forEachModifier(ItemStack, EquipmentSlot, BiConsumer)` (invokestatic): descriptor args =
  `[ItemStack, EquipmentSlot, BiConsumer]` → **`index = 2` is the `BiConsumer`** ✔.
* The handler's erased return type `Ljava/util/function/BiConsumer;` matches the selected arg type, and
  `ModifyArgInjector.checkTarget` only rejects `!handler.isStatic && hostMethod.isStatic`; `ItemStack.forEachModifier` is an
  instance method → non-static handlers are legal (that is how `(ItemStack) (Object) this` works).
* *Caveat for other packs:* a Mixin `@Redirect` on the same invoke decorates the node with a chained `ArgOffsets`
  (`RedirectInjector` bytecode 231-235; also read by `ModifyArgsInjector`) and would remap `@ModifyArg` indices. MixinExtras
  0.5.5 has **no** reference to `ArgOffsets` at all (`grep -ral ArgOffsets` over the MixinExtras jar = none), rearm uses
  `@ModifyReceiver`, and no other installed mod references `ItemAttributeModifiers;forEach` — so nothing can shift our indices here.

**Can our two `@ModifyArg`s coexist with rearm's `@ModifyReceiver` and elytraslot's `@Inject`?** Yes:

* Different operands: ours replaces the consumer argument (index 1), rearm's replaces the receiver (operand 0). Each injector
  implementation is stack-neutral and self-contained:
  * `ModifyArgInjector.injectAtInvoke` finishes with `target.insns.insertBefore(invokeNode, insns)` after a save-args →
    invoke-handler → push-args sequence (offsets 272-279).
  * `ModifyReceiverInjector.modifyReceiverOfTarget` computes `effectiveArgs = getEffectiveArgTypes(...)` =
    `[owner type] + descriptor args`, does `storeArgs(target, effectiveArgs, insns, 0)`, invokes the handler on the receiver,
    coerces the return type, then `pushArgs(effectiveArgs, insns, argMap, 1, argMap.length)` — i.e. consumes and re-pushes the
    whole argument list with the new receiver first — and inserts with the fork's `Target.insertBefore(InjectionNode, InsnList)`.
  * Both are balanced "consume N args, push N args (one replaced)" sequences, so composing them in either application order is
    layout-safe. Mixin has no blanket "one injector per instruction" guard (the only duplicate-related message in the injection
    package is the `@Inject` group max-count one, "already did this {} times!"), and `InjectionNode` decorations chain.
* Semantics compose correctly: rearm's extra ARMOR_TOUGHNESS entry is appended to the receiver *before* `forEach` runs, and the
  entries are handed to the consumer — which is our wrapper → the rearm bonus is durability-scaled **exactly once**.
* elytraslot is orthogonal: its `@Inject(HEAD, cancellable)` cancels before any body instruction, so for the BODY-slot glider
  neither our `@ModifyArg` nor rearm's `@ModifyReceiver` runs on the outer call; the inner `forEachModifier(CHEST, freshLambda)`
  runs its own body, where rearm adds toughness to the receiver and our wrapper wraps the forwarded lambda once.

**What cannot be resolved statically:** the *runtime application order* of the mixins (both our injectors and elytraslot's use
`order` default 1000; the tie-break is mixin application order). With the reworked hook this no longer affects correctness
(§A.4), but the order was not executed. **What the integration test must prove:** (a) the three mods load together with no
`InvalidInjectionException`/`VerifyError` and all injections apply (the loom run config already sets
`-Dmixin.debug.countInjections=true`, `build.gradle` `runs.integrationTest`); (b) with an enchanted elytra in the elytraslot slot
and rearm's rebalance active, `ARMOR_TOUGHNESS` shows a single multiplier, not `multiplier²` (T2); (c) on a normal armor piece the
applied modifier set contains exactly one modifier per id (T6).

### A.4 Re-audit of the reworked hook (verdict on P1-1 and on the invariants)

Audited files/hashes: see §A.0. The reworked `ItemStackMixin` is exactly two Mixin `@ModifyArg` injections in
`ItemStack.forEachModifier(EquipmentSlot, BiConsumer)`:

```java
@ModifyArg(method = "forEachModifier(...EquipmentSlot;Ljava/util/function/BiConsumer;)V",
           at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/component/ItemAttributeModifiers;forEach(...EquipmentSlot;Ljava/util/function/BiConsumer;)V"),
           index = 1)  // -> ArmorDurabilityScaling.wrap((ItemStack)(Object) this, consumer)
@ModifyArg(method = "forEachModifier(...EquipmentSlot;Ljava/util/function/BiConsumer;)V",
           at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;forEachModifier(Lnet/minecraft/world/item/ItemStack;...EquipmentSlot;Ljava/util/function/BiConsumer;)V"),
           index = 2)  // -> ArmorDurabilityScaling.wrap((ItemStack)(Object) this, consumer)
```

1. **`modifier.id()` is still preserved — CONFIRMED.** `ArmorDurabilityScaling.scale` line 105:
   `new AttributeModifier(modifier.id(), modifier.amount() * multiplier, modifier.operation())`; `wrap` (lines 128-138) only
   delegates to `scale`. The removal path `LivingEntity.stopLocationBasedEffects` → `previous.forEachModifier(slot, (attr, mod) -> instance.removeModifier(mod))`
   therefore still removes the applied modifier by the same id (it is the same funnel, both dispatch calls wrapped, id unchanged).
   The identity fast-path (`multiplier == 1.0` → the original `AttributeModifier`/consumer instance) is irrelevant for removal
   either way.
2. **Both dispatch paths are covered — CONFIRMED.** Two distinct `@ModifyArg` on the two distinct invokes inside the *same*
   method (the only two dispatch invokes in that body: `ItemStack.txt:2254` and `:2259`), with full target descriptors and the
   verified descriptor-based indices (1 and 2, §A.3). The other overloads (`forEachModifier(EquipmentSlotGroup, TriConsumer)`,
   both in `ItemStack` and in `EnchantmentHelper`) are not named by either `method =` selector, so tooltips and any group-overload
   mod code stay unscaled as before.
3. **Double-scaling under elytraslot-style re-entry is impossible now — CONFIRMED, and the lead's rationale is correct.**
   * The outer BODY-slot call is cancelled at HEAD by `elytraslot$bodyGliderKeepsChestModifiers` (`ci.cancel()` at bytecode 23-24),
     i.e. before any body instruction; `@ModifyArg` only rewrites the argument at the dispatch invoke *inside the body*, so the
     outer invocation wraps nothing.
   * The inner `self.forEachModifier(CHEST, freshLambda)` executes its own body exactly once → each of its dispatches is wrapped
     once → `freshLambda` receives a single-scaled modifier, re-ids it with `/elytraslot_body` and forwards it to the *original*
     consumer (never touched by us) → applied with one `multiplier`.
   * This holds for **both** mixin application orders, because unlike `@ModifyVariable(argsOnly)` the injection no longer changes
     the value the HEAD callback sees.
   * The marker/`instanceof` alternative really would not have worked: elytraslot's forwarded object is a **fresh lambda**
     (`lambda$…$0`, bytecode 229-248: `new AttributeModifier(mod.id().withSuffix("/elytraslot_body"), mod.amount(), mod.operation())`
     inside a lambda that closes over the received consumer), so an `instanceof` test on the object elytraslot passes would
     always be false even if the received consumer had been our wrapper.
   * **P1-1 is therefore RESOLVED by design** (not merely order-dependent-mitigated). The residual obligation is a runtime
     test (T1/T2) that the composed class actually loads and counts as expected, since no Gradle/game run was possible here.
4. **Interesting side effect of the reworked injection (positive):** because the wrapper is installed at the dispatch site, a
   `multiplier == 1.0` stack returns the original consumer (`ArmorDurabilityScaling.wrap` lines 133-136), so the vanilla-identical
   path allocates nothing and behaves exactly as before for full-durability items.
5. **New-code review notes (no defect found, two documentation items):**
   * `wrap(...)` is a **new public API method not listed in the frozen `DESIGN.md` §4 table** (`multiplier`, `scalesAttribute`,
     `scale`). It must be public because `dev.durabilityarmor.mixin` is a different package. → Lead may want to add it to
     DESIGN §4 (contract sync), or document it as an internal helper. Flagged as a documentation item, not a bug.
   * The multiplier is now sampled once per dispatch call (in `wrap`) instead of once per emitted modifier (in `scale`). Inside a
     single `forEachModifier` execution the stack cannot be mutated, so this is equivalent; `scale` still recomputes per modifier
     (defensive, no behaviour change observed).
   * `wrap(null, …)` returns `null` (defensive); `@ModifyArg` handlers return non-null in practice.
   * Both handlers are non-static instance methods casting `this` to `ItemStack`; legal because the host method is non-static
     (`ModifyArgInjector.checkTarget` only forbids a non-static handler on a static host method).
   * `durability_armor.mixins.json` keeps `injectors.defaultRequire = 1`, so if either dispatch target ever stops matching, the
     game fails loudly at load instead of silently not scaling — good failure mode.
6. **Re-verification of the unchanged logic:** `multiplier()`/`scalesAttribute()`/`scale()` are byte-for-byte the same logic as
   the revision audited in §0–§9 (only `wrap` + imports were added), so the §0/§2/§4/§5 conclusions for those methods carry over
   unchanged; the only behavioural delta is the injection mechanism, covered above.

### A.5 Updated findings and test-request deltas

| Item | Status after `task-5` |
| --- | --- |
| P1-1 (elytraslot double scaling) | **Resolved by the reworked hook** (§A.4). Kept in §6 as the historical finding + rationale. |
| P2-6 / §9.1 (lithium + mixin ordering unknowns) | lithium: **checked, not a defect** (§A.1). Injector-order dependency: **gone** (the rework removes it). |
| §9.3 (rearm cap constant) | **Resolved**: `@ModifyExpressionValue(<clinit>, CONSTANT doubleValue=30.0)` → ARMOR max 30.0→40.0 (§A.3). |
| P2-3 (sum clamp after scaling) | Unchanged, now with the confirmed cap = 40.0 under the active rearm config. |
| New P0 | **None.** |
| New unknowns | (a) the runtime *composition* of our `@ModifyArg` with rearm's `@ModifyReceiver` was reasoned statically, not executed (no Gradle/game run) — the integration test must show a clean load and single scaling; (b) a future/other-pack Mixin `@Redirect` on either dispatch invoke would chain `argOffsets` and change `@ModifyArg` index mapping (mitigation: the target descriptors + `defaultRequire = 1` make a mismatch fatal, not silent); (c) `wrap(...)` is not yet part of the frozen `DESIGN.md` §4 API list. |

**Test requests delta (for `impl-tests`, via the Lead):**

* **T1 (revised).** Replace the old "no-game re-entrancy proxy" with: (i) a unit test that `ArmorDurabilityScaling.wrap(stack, c)`
  scales a modifier exactly once and returns the original consumer for a full-durability stack, and (ii) a mixin-application
  assertion (integration run, `-Dmixin.debug.countInjections=true` is already set) that both `@ModifyArg` injections applied
  (`require = 1` each) and that a BODY-slot glider re-entry produces one `multiplier`.
* **T2 (keep, now the primary P1-1 regression):** elytraslot + rearm (`armorRebalance`, `enchantmentBasedToughness` as configured)
  with a Protection-enchanted elytra in the elytraslot slot, damaged → `ARMOR_TOUGHNESS` must equal a single-multiplier value.
* **T3 (keep):** mob/zombie with damaged armor → ARMOR drops within 1-2 ticks with lithium enabled (and compare with lithium
  disabled); this is the runtime confirmation of §A.1.
* **T12 (extended):** add "load the mod together with elytraslot + rearm + lithium and assert startup has no
  `InvalidInjectionException`/`VerifyError` and that `getModifiers()` ordering/ids are unchanged" — this is the §A.3 coexistence
  proof the static analysis cannot give.
* T4–T11 unchanged.
