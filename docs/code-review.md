# Independent adversarial code review — `durability_armor` (MC 26.2 / Fabric loader 0.19.5)

| | |
| --- | --- |
| Task | `task-6` — independent adversarial code review |
| Author | teammate `reviewer` (read-only for `src/**`; only this file written) |
| Date | 2026-10-06 ~15:35–15:45 +0800 |
| Frozen design | `docs/DESIGN.md` v1.0.0+mc26.2 |
| Reviewed revision (sha256) | `ArmorDurabilityScaling.java` `90eeb7d588e76175ddee77f4cc7edd1d1f0c932954f4b76caf08a41a31dda893`; `ItemStackMixin.java` `b0166d4abb95ed5206747e410933382e19e3422b92fb597d99c8d6c8a7dbb0e5`; `fabric.mod.json` `ed72cc9b2fe4a51912fa5bd64785dde9f3925b47cbe3e812342d1b585d88c07d`; `durability_armor.mixins.json` `e534461af364680d904c0dd9d4cb5fe8908e93ba43f405ff9e67a63e4351649c`; built jar `2963567382409083cb7c21749a81ba14c38a04a4f06c9cda2154b2acc7800cbe`; suite `DurabilityArmorIntegrationTests.java` `97515e0a0d2ce810285f195e7d16f2ee4a5120a475ecb46742a66f040f5548d0` |
| Method | `javap -p -c/-v` on the mapped 26.2 jar, the Mixin 0.17.4 runtime, MixinExtras 0.5.5 and every installed mod jar; read-only unzip into `/tmp`; jar/class/metadata inspection of the built artifact; read of `src/**` and the existing run artifacts. **No Gradle was run, no game was started, nothing outside this file was modified.** |
| Runtime evidence used (pre-existing, not produced by me) | `run-test/test-result.txt` = `PASS: 178 checks` (mtime 15:34:40), `run-test/logs/debug-1.log.gz` (mtime 15:34); `build/classes/java/main` mtime 15:26:24 (newer than both `src/main` sources, 15:25:53 / 15:26:10); test class mtime 15:34:20 (newer than the test source, 15:33:53) |

**Chinese bottom line:** 代码与需求一致，未发现 P0/P1 级实现缺陷；发布阻塞项为「0 个」，但我**不能**代替 Lead 在真实实例（含 lithium / elytraslot / rearm / firstaid）中做启动与联机验证 —— 这 4 项是必须由 Lead 执行的关闭条件。发现 1 处**文档与实现不符**（客户端并不"自行计算"，而是接收服务端快照；结论正确但描述错误）与 6 处 P2/测试缺口。

---

## 0. Release verdict

**VERDICT: RELEASE-CANDIDATE — no P0 and no P1 defect found in `src/main/**`, `src/main/resources/**`, `build.gradle` or the built jar.** The implementation matches the user's five requirements as frozen in `docs/DESIGN.md`, the previously reported P1-1 (elytraslot recursion) fix is verified independently at bytecode level, and the lithium claim is verified independently and now has a complete evidence chain that the earlier audit lacked.

The verdict is **conditional** on §5 (four in-game/interop verifications that my tooling cannot perform: no Gradle, no game run). It is **not** conditional on anything I was able to test myself.

Confidence: high on formula/edge-case/mixin-semantics/metadata/docs (all re-derived from bytecode and from the compiled artifact); high on lithium; medium-high on rearm+elytraslot interop (argued from injector bytecode, not yet exercised with the real jars in one JVM).

---

## 1. Requirement conformance (原文逐条)

| # | Requirement | Verdict | Independent basis |
| --- | --- | --- | --- |
| 1 | `r = 当前耐久/最大耐久`; `multiplier = 1-(1-r)^2`; 护甲值 = 原始*m; 护甲韧性 = 原始*m | **PASS** | `ArmorDurabilityScaling.multiplier` reads `MAX_DAMAGE` + `DAMAGE` and computes `1 - deficit*deficit`; `scale()` multiplies `amount` and keeps `id()`/`operation()`. Closed form + documented 0.75 / 0.4375 pinned by suite A9–A13; live entity sums pinned by B11/B12/B23/B24. Independently: `ItemStack.getDamageValue() = Mth.clamp(getOrDefault(DAMAGE,0), 0, getMaxDamage())`, so `r` is exactly remaining/max. |
| 2 | 击退抗性不受影响 | **PASS** | `scalesAttribute` is true only for `ARMOR`/`ARMOR_TOUGHNESS`; `KNOCKBACK_RESISTANCE` and `EXPLOSION_KNOCKBACK_RESISTANCE` (blast_protection's enchantment attribute) are untouched. B32/B33/R5e assert entity + funnel bit-identity. |
| 3 | 动态计算，不永久修改基础属性 | **PASS** | `scale()` builds a new immutable `AttributeModifier` for the transient application only; no component write anywhere in `src/main`. C2/C3 (component byte-identical), C9/C10 (no mod-namespace modifier), R4 (nothing persisted in entity NBT) all pass. |
| 4 | 兼容原版与其他 Mod 护甲 | **PASS (static) / conditionally verified (runtime)** | All vanilla armor goes through the hooked funnel; the only vanilla caller of the hooked overload is `LivingEntity` (2 call sites); modded armor that uses the standard attribute-modifier component is covered. 4 installed mods touch this area: elytraslot (re-entry — fixed), rearm (`@ModifyReceiver` on the same INVOKE — analysed compatible), firstaid (consumes the funnel read-only — automatically durability-aware), lithium (change detection preserved). See §2.5–2.8, §3, §5. |
| 5 | 不重复叠加 / 重进世界正常 / 不双端不同步 | **PASS** | Removal is by **id** (`AttributeInstance.removeModifier(AttributeModifier)` → `removeModifier(Identifier)`), add is guarded by `addModifier`'s duplicate-id `IllegalArgumentException`, and the hook never changes the id → remove-then-add cannot stack. Only `permanentModifiers` are persisted. Client mirrors the server's snapshot (see the correction in §2.9). |

---

## 2. Facts I re-derived myself (not taken from comments/reports)

### 2.1 The hooked method body (vanilla)
`ItemStack.forEachModifier(EquipmentSlot, BiConsumer)` contains exactly two dispatch calls and no other attribute sink:
* `aload_3; aload_1; aload_2; invokevirtual ItemAttributeModifiers.forEach(EquipmentSlot, BiConsumer)V` (instance call; descriptor args = `[EquipmentSlot, BiConsumer]`)
* `aload_0; aload_1; aload_2; invokestatic EnchantmentHelper.forEachModifier(ItemStack, EquipmentSlot, BiConsumer)V` (descriptor args = `[ItemStack, EquipmentSlot, BiConsumer]`)

There is no third path. Across the whole 26.2 jar only three classes contain the string `forEachModifier`: `ItemStack` (definition), `LivingEntity` (the only caller of the hooked overload), `EnchantmentHelper` (definition). Therefore **the hook cannot miss or double-hit a vanilla attribute sink.**

### 2.2 `@ModifyArg` `index` semantics — proven from Mixin source, not inferred
`org.spongepowered.asm.mixin.injection.invoke.ModifyArgInjector.injectAtInvoke` does
`Type.getArgumentTypes(MethodInsnNode.desc)` and then `findArgIndex(argTypes,…)` / `ArgOffsets.getArgIndex(...)`; `singleArgMode` is taken when the handler has exactly one parameter, and `sanityCheck` requires that parameter's type to equal the handler's return type. The argument array comes from the **invoked method's descriptor, so the receiver is not counted**.
→ `index = 1` on `ItemAttributeModifiers.forEach(EquipmentSlot, BiConsumer)` selects the `BiConsumer`; `index = 2` on the static `EnchantmentHelper.forEachModifier(ItemStack, EquipmentSlot, BiConsumer)` selects the `BiConsumer`. Both handlers are `(BiConsumer) -> BiConsumer`. Confirmed. (Runtime corroboration: the suite passes B15/B16/B11/B12 — a receiver-inclusive index would have selected `EquipmentSlot` and thrown `ClassCastException` on every equipment change.)

### 2.3 Id preservation and the removal path (the load-bearing invariant)
* Add path: `LivingEntity.lambda$collectEquipmentChanges$0` = `instance.removeModifier(modifier.id()); instance.addTransientModifier(modifier);` — idempotent by id.
* Removal path: `LivingEntity.stopLocationBasedEffects(itemStack, slot, attributes)` calls `itemStack.forEachModifier(slot, lambda$stopLocationBasedEffects$0)`, and that lambda calls `AttributeInstance.removeModifier(AttributeModifier)`, whose body is exactly `removeModifier(modifier.id())` → map removal by id, **not** by value.
* `AttributeInstance.addModifier` throws `IllegalArgumentException` if the id is already present; the consumer always removes first.
→ Because `scale()` returns `new AttributeModifier(modifier.id(), amount*m, modifier.operation())`, both paths stay consistent even though the mixin also wraps the *removal* consumer (which receives a freshly scaled instance that is only used for its id). **This is why "double stacking" is impossible and why a scaled modifier can always be removed.**

### 2.4 Broken / unequip / negative-damage edges
* Breaking a piece: `collectEquipmentChanges` first calls `stopLocationBasedEffects(oldStack, …)` for the changed slot (guarded only by `!old.isEmpty()`), then re-applies only `if (!newStack.isEmpty() && !newStack.isBroken())`. So a broken piece keeps no modifier → contribution 0 = `raw * 0.0`. Matches B28/R5b.
* Damage is clamped twice in vanilla: `setDamageValue` and `getDamageValue` both use `Mth.clamp(v, 0, getMaxDamage())`. Consequently `remaining = maxDamage - getDamageValue()` is always in `[0, maxDamage]`, and the two early returns in `multiplier` (`remaining <= 0` → `0.0`, `remaining >= maxDamage` → `1.0`) are exactly the endpoints. See P2-c for the test-quality consequence.

### 2.5 lithium 0.25.3 — the full chain (independent, bytecode)
Earlier audit (§A.1) concluded "damage changes still trigger re-application". I re-derived **every link**:
1. `ItemStack.setDamageValue(int)` → `this.set(DataComponents.DAMAGE, clamped)`.
2. `PatchedDataComponentMap.set/remove/applyPatch/clearPatch/restorePatch` all call `ensureMapOwnership()` at offset 1.
3. lithium `mixin/util/item_component_and_count_tracking/PatchedDataComponentMapMixin` → `@Inject(method="ensureMapOwnership()V", at=@At("HEAD"))` → `subscriber.lithium$notify(map, 0)`.
4. → lithium `…/ItemStackMixin.lithium$notify(...)` → forwards to its own subscriber.
5. → `mixin/entity/equipment_tracking/EntityEquipmentMixin.lithium$notify(ItemStack,int)` sets `hasUnsentEquipmentChanges = true` (field initialised `true`, reset by `lithium$onEquipmentChangesSent()`).
6. `mixin/entity/equipment_tracking/equipment_changes/LivingEntityMixin` → `@Inject(method="collectEquipmentChanges(Map)Map", at=@At("HEAD"), cancellable=true)`: returns `null` only when `!hasUnsentEquipmentChanges`.
7. Vanilla `detectEquipmentUpdates()` is `map = collectEquipmentChanges(lastEquipmentItems); if (map != null) { handleHandSwap(map); if (!map.isEmpty()) handleEquipmentChanges(map); }`.
→ Damaging equipped armor makes the flag true, so the comparison and our hook run normally. **Lithium does not silently disable the durability refresh. Confirmed, not assumed.**
Note: for non-players the flag is reset each tick at the `handleHandSwap` INVOKE; for players the reset is skipped (`instanceof Player` early return), so players always run the full comparison. Either way the refresh happens.

### 2.6 elytraslot 3.0.0 — the re-entry is exactly as described
`com.warwa.elytraslot.mixin.ItemStackModifierMixin` (`javap`): `@Inject(method="forEachModifier(EquipmentSlot, BiConsumer)V", at=HEAD, cancellable=true)`; returns unless `slot == BODY`; returns unless `Gliders.isChestGlider(self)`; then `ci.cancel()` and `self.forEachModifier(EquipmentSlot.CHEST, (attr, mod) -> consumer.accept(attr, new AttributeModifier(mod.id().withSuffix("/elytraslot_body"), mod.amount(), mod.operation())))`, where `consumer` is the parameter the *target method* received.
→ With the current hook: the outer BODY body never executes (so no wrapping of the parameter), and the inner CHEST call's own body wraps its consumer once → `raw * m`, not `m²`. The probe mixin + suite section R1a reproduces this shape (including identity check `sawOriginalConsumer`), so the fix is regression-guarded. The real mod additionally re-suffixes the id; that is orthogonal to the multiplier and is consistent on the removal path because the suffix is deterministic.

### 2.7 rearm 2.5.6 — same INVOKE, different operand (new detail)
rearm's `me.pajic.rearm.mixin.ItemStackMixin` uses
`com.llamalad7.mixinextras.injector.ModifyReceiver(method="forEachModifier(EquipmentSlot, BiConsumer)V", at=@At(value="INVOKE", target="Lnet/minecraft/world/item/component/ItemAttributeModifiers;forEach(Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V"))`
with handler `(ItemAttributeModifiers, EquipmentSlot, BiConsumer) -> ItemAttributeModifiers` (adds armor-toughness modifiers from its config/enchantments). That is **the very same INVOKE node** my hook `@ModifyArg`s.
MixinExtras `com.llamalad7.mixinextras.injector.ModifyReceiverInjector extends org.spongepowered.asm.mixin.injection.code.Injector`; `inject()` calls `checkTargetIsValid`, `checkTargetModifiers(target, false)`, `modifyReceiverOfTarget(...)`, and the only instruction-list operation is `Target.insertBefore(InjectionNode, InsnList)` — **it does not replace or remove the INVOKE** (it explicitly rejects nodes already converted by a redirect via `InjectorUtils.isVirtualRedirect`). Our `@ModifyArg` runs in place too (`Target.insns.insertBefore`), so the two touch different operands of the same node and are order-independent. Consequence: rearm's *added* ARMOR_TOUGHNESS modifiers are downstream of the receiver edit and therefore **are** scaled by durability — which is the correct reading of requirement 1.
Residual risk: this coexistence is proven only statically; rearm is not on the integration-test server's mod list. See §5.

### 2.8 firstaid 1.3.1 — positive interaction, now verified
`ichttt.mods.firstaid.common.util.ArmorUtils.getValueFromAttributes(...)` calls `stack.forEachModifier(slot, accumulator)` (the hooked overload), and `EqualDamageDistributionAlgorithm`/`DamageDistribution` feed that value into `ArmorUtils.applyArmor(...)` (a damage-side calculation, not an attribute write). So firstaid's body-part model automatically sees the durability-scaled armor, consistently with the entity attribute. **I did not trace firstaid's whole damage pipeline**, so "no double reduction together with vanilla CombatRules" remains unverified (§6).

### 2.9 Correction: the client does NOT compute the value (DESIGN §3 / README are wrong about the mechanism)
`LivingEntity.tick()` starts with `level(); isClientSide(); ifne 170`, and `detectEquipmentUpdates()` sits at offset 125 — **inside the server-only region**, so the client never runs `collectEquipmentChanges` and never runs the funnel. The audit (`docs/compat-audit.md` §3.2) is right; DESIGN §3's bullet ("client and server compute identical values") and both READMEs are wrong about *how* the client learns the value. The actual chain, verified end to end:
* server: hook scales the transient equipment modifier → `AttributeInstance.addModifier/removeModifier` → `setDirty()` → `onDirty` → `AttributeMap.attributesToSync`;
* `ServerEntity.sendChanges` → `getAttributesToSync()` non-empty → `new ClientboundUpdateAttributesPacket(entityId, attributesToSync)`;
* the packet's snapshot is built from **`instance.getModifiers()`** (= `modifierById.values()` = transient **and** permanent, i.e. our scaled amounts), not from `AttributeInstance.pack()` (which packs `permanentModifiers` only and is *not* the network path);
* `Attributes.ARMOR`/`ARMOR_TOUGHNESS` are `setSyncable(true)`, so the packet carries them;
* client `ClientPacketListener.handleUpdateAttributes` → `instance.setBaseValue(base); instance.removeModifiers(); for (mod : snapshot.modifiers()) instance.addTransientModifier(mod);` → **full replace**, so no accumulation and no divergence; `sendToTrackingPlayersAndSelf` covers the local player.

**Outcome claims remain true** (no mod-owned packets, no desync); only the "both sides compute it" wording is false. See finding **P2-a**.

### 2.10 Packaging: no refmap is needed here (checked because it is the classic trap)
The built jar contains only `durability_armor.mixins.json` (no `refmap` key) and **no** `*-refmap.json`; the mixin class in the jar still carries Mojang-named targets (`forEachModifier(...)`, `Lnet/minecraft/world/item/component/ItemAttributeModifiers;forEach(...)`) identical to the dev class. That is correct **for this ecosystem**: three unrelated production mod jars in the user's instance (lithium, elytraslot, rearm) reference `net.minecraft.world.item.ItemStack` / `net.minecraft.world.entity.EquipmentSlot` in their own method descriptors and contain **zero** intermediary (`class_`/`method_`) references, and elytraslot's mixin config likewise has no `refmap`. So the runtime namespace here is Mojang official names and the missing refmap is a non-issue. (Had the runtime been intermediary, this would have been a P0.)

### 2.11 Persistence, independently confirmed at the bytecode level
`LivingEntity.addAdditionalSaveData` and `readAdditionalSaveData` serialize the attribute map through `AttributeMap.pack()` (`AttributeInstance.Packed.LIST_CODEC`), and `AttributeMap.pack()` → `AttributeInstance.pack()` packs `baseValue` + `List.copyOf(this.permanentModifiers.values())`. Transient equipment modifiers — the only thing this mod touches — are therefore **structurally incapable of being saved**, independent of the suite's NBT string check (R4). On load, `lastEquipmentItems` starts empty, so the first `detectEquipmentUpdates()` re-applies the (scaled) equipment modifiers from the persisted item damage. This is a stronger proof of the "重进世界异常" requirement than R4's textual check.

### 2.12 What the tooltip path is (and is not)
`ItemStack.addAttributeTooltips` is the **only** caller of the `forEachModifier(EquipmentSlotGroup, TriConsumer)` overload, which the mixin deliberately does not hook. So tooltips keep printing the item's own component values, exactly as README/DESIGN claim. This also means: a mod that used the group overload to *apply* attributes would bypass the scaling — no vanilla code does, and of the installed mods only rearm touches that overload (for display), so there is no functional gap in the audited set.

### 2.13 Formula/float behaviour
* `multiplier == 1.0` in `scale()`/`wrap()` is safe: every way to obtain exactly `1.0` is one of the explicit early returns (`null`/empty, no `MAX_DAMAGE`, `UNBREAKABLE`, `maxDamage <= 0`, `remaining >= maxDamage`); the computed branch returns `1 - deficit*deficit` with `deficit ∈ (0,1)` and never rounds up to `1.0` for any realistic `maxDamage` (see P2-d for the theoretical threshold).
* No precision trap in the comparison itself; `remainingRatio` is computed from two ints and cannot overflow (`maxDamage` is a non-negative int from the component; `remaining ≤ maxDamage`).
* `scale()` also honours `operation()` for non-`ADD_VALUE` armor modifiers by scaling the *amount*; for vanilla armor (all `ADD_VALUE`) this is exactly "contribution × m".

---

## 3. Findings

### P0 — none.

### P1 — none in the reviewed sources.

### P2 — documentation/behaviour/coverage items (none block release, all cheap to fix)

**P2-a — `DESIGN.md` §3, `README.md`, `README.zh-CN.md` describe a client-side computation that does not happen.** *Evidence:* §2.9. Claims affected: DESIGN "so client and server compute identical values with no packets of our own"; README "the scaled value is a pure function of the synced `ItemStack`, so client and server always agree" and "values are computed identically on both sides"; README.zh-CN "数值完全由（已同步的）ItemStack 推导，客户端与服务端计算结果一致". *Impact:* the mechanism claim is false and would mislead any future maintainer (e.g. someone "restoring symmetry" by also hooking a client path could introduce a genuine double-apply). The outcome claims (no mod packets, no desync) are correct. *Recommendation:* reword to "the server computes the scaled value and the client receives it in `ClientboundUpdateAttributesPacket` (which carries the transient equipment modifiers); therefore the client cannot diverge and the mod needs no packets of its own." Note the wording issue also weakens the *reason* given for the chosen architecture, so it should be fixed in DESIGN §3 too — Lead-owned files; flagging, not editing.

**P2-b — the `EnchantmentHelper` dispatch (`@ModifyArg index = 2`) has no runtime test.** No vanilla enchantment grants `minecraft:armor`/`minecraft:armor_toughness`: I checked all 43 enchantment JSONs in the jar and the complete set of enchantment-granted attributes is `burning_time, explosion_knockback_resistance, mining_efficiency, movement_efficiency, movement_speed, oxygen_bonus, sneaking_speed, submerged_mining_speed, sweeping_damage_ratio, water_movement_efficiency` — neither ARMOR nor ARMOR_TOUGHNESS is among them. So with vanilla content that handler is never exercised at runtime; only the static ASM scan (R1e) asserts its existence/index. *Impact:* a modded armor enchantment is the reason the handler exists (requirement 4), and a regression there would go unnoticed. *Recommendation:* add an integrationTest datapack defining an enchantment with an attribute effect granting `minecraft:armor` (id under the test namespace), apply it via `DataComponents.ENCHANTMENTS` to a damaged chestplate, and assert the funnel emits `raw * m` exactly once.

**P2-c — A20/A21 ("negative damage") assert vanilla clamping, not our branch.** `setDamageValue(-5)` clamps to 0 inside `ItemStack`, so the test proves `getDamageValue() == 0` and `multiplier == 1.0` — it cannot reach the `remaining >= maxDamage` guard with a negative value, and no test can (the component value is clamped on write *and* on read). *Impact:* the "negative DAMAGE" edge case listed in the task is unreachable by construction; the guard is defensive dead-ish code (harmless, documents intent). *Recommendation:* either delete A20/A21 or relabel them as "vanilla clamps DAMAGE; our endpoint branch handles it"; the real endpoints (0 / max / >max / `maxDamage <= 0`) are covered by A16–A19, A24–A26, R5b.

**P2-d — theoretical `multiplier == 1.0` collision at absurd `MAX_DAMAGE`.** `1.0 - deficit*deficit` rounds to exactly `1.0` when `deficit² < 2^-53`, i.e. `deficit < ~1.054e-8`, i.e. `maxDamage ≳ 9.49e7` (with `remaining = maxDamage-1`). Since `MAX_DAMAGE` is a settable component, a datapack/mod could in principle create such a stack, and `scale()` would then skip scaling for that one point of durability. *Impact:* the skipped correction is `< 1.2e-16` relative — physically irrelevant; no test covers it. *Recommendation:* no code change; optionally document the boundary or replace the `== 1.0` shortcut with a `>= 1.0` check (equivalent in practice).

**P2-e — `compatibilityLevel: "JAVA_25"` is above what the bundled Mixin understands.** `run-test/logs/debug-1.log.gz`: `Compatibility level JAVA_25 specified by durability_armor.mixins.json is higher than the maximum level supported by this version of mixin (JAVA_13). Compatibility level set to JAVA_25`. It works in this configuration (the previous run applied both injections), and `elytraslot` ships `JAVA_21`, so this is the house style rather than an anomaly. *Impact:* a stricter Mixin build could reject or mis-handle class-version checks; a warning in every log. *Recommendation:* keep, but record the tolerated warning in DESIGN §7, or lower to `JAVA_21`.

**P2-f — "armor value × m" is only exactly true for additive modifier operations.** `scale()` multiplies `amount` regardless of `operation()`. All vanilla armor uses `ADD_VALUE` (the suite asserts this for its fixtures, B5/B22), for which amount-scaling *is* contribution-scaling. A modded armor piece using `ADD_MULTIPLIED_BASE/TOTAL` on `minecraft:armor` would get "the modifier amount scaled", not strictly "the piece's final contribution × m". *Impact:* none for vanilla/modded-ADD_VALUE armor; unspecified-but-reasonable otherwise. *Recommendation:* one sentence in DESIGN §7.

### Cleared (explicitly checked, no finding)
* **Double scale / zero scale:** only two dispatch calls exist; both are hooked; `defaultRequire: 1` makes a missing hook a hard failure; the removal consumer is id-based; no marker/static state is needed; elytraslot and rearm both analysed in §2.6–2.7.
* **Unintended attributes:** only `ARMOR`/`ARMOR_TOUGHNESS`; `ATTACK_DAMAGE` (R3), `KNOCKBACK_RESISTANCE` (B32/R5e), `EXPLOSION_KNOCKBACK_RESISTANCE` (blast_protection's attribute) are untouched. `Holder` comparison uses identity fast-path + `.value()` identity fallback, wrapped so an unbound holder cannot throw; the robust path is never exercised by vanilla (see §4 gap (c)).
* **Static mutable state / cache / custom packets / client classes:** `ArmorDurabilityScaling` is stateless with a private ctor; `ItemStackMixin` is stateless; `DurabilityArmor` has only a `static final` logger; no `net.minecraft.network`/`net.minecraft.client` member anywhere; `environment: "*"` and the mixin is under `mixins` (not `client`), so the dedicated server loads it (D8–D10 pass).
* **Performance:** the funnel runs only on equipment change (and on firstaid's damage-side reads). Per hooked call: two `multiplier` computations (a few component lookups) + at most two captured lambdas; per scaled pair: one `AttributeModifier` allocation; when `multiplier == 1.0` (`wrap`) the **original consumer is returned unchanged and nothing is allocated**. No per-tick/per-frame path is added.
* **Persisted state:** only `AttributeInstance.permanentModifiers` are serialized (`LivingEntity.addAdditionalSaveData` → `AttributeMap.pack()` → `AttributeInstance.pack()`), and the hook adds none, so a scaled value cannot be persisted by construction (§2.11). R4's NBT round-trip passes as well.
* **Metadata/jar:** `fabric.mod.json` id/entrypoint/`"*"`/mixins/version-expansion correct in the jar; mixins json package+class+`defaultRequire` correct; jar contains exactly the 3 mod classes + both resources + LICENSE and **no integrationTest class**; `build.gradle` `options.release = 25`, toolchain 25, `jar { from('LICENSE') }`, EULA flag only honours an explicit `-PacceptMinecraftEula=true`, and `doFirst` deletes the previous `test-result.txt` so a crashed run cannot leave a stale `PASS`.
* **Refmap:** not needed in this mapping generation (§2.10).

---

## 4. Test non-vacuity assessment (`src/integrationTest/**`, owner `impl-tests`)

Overall this is a genuinely adversarial suite, not a rubber stamp. Evidence for the strongest property — *would it fail if the mixin were removed or the formula changed?* — is structural: the oracle (`raw()`, `collectExpected()`) reads the item's **`ItemAttributeModifiers` component directly** (`modifiers.forEach(slot, …)`), never through `ItemStack.forEachModifier`, so it is independent of the code under test. `expected()` and `collectExpected()` use `ArmorDurabilityScaling.multiplier` for the factor, but the formula itself is pinned independently against the closed form and the documented constants 0.75/0.4375 (A9–A13), so the unit + integration split is sound.

**Assertions that do real work (verified non-vacuous):**
* B15/B16 — `damagedChestplate.forEachModifier(CHEST, collector)` must equal `rawComponent * m`. With the mixin gone this yields `raw` ⇒ fail.
* B11/B12/B23/B24/B30 — live entity sum equals `Σ raw_i * m_i`, and B26 additionally asserts the weighted sum differs from the unscaled baseline (so the expectation cannot coincide accidentally).
* B14/B18/B20 — the armor bar and `getDamageAfterArmorAbsorb` must actually differ for worn armor and match `CombatRules` with the scaled values.
* B33/R5e — knockback resistance must be bit-identical (`Double.compare == 0`) while ARMOR on the same stack is scaled.
* C2/C3 — component byte-identity; C11/C12/C13 — the modifier *id set* must equal exactly the four items' own ids with amounts `raw*m` (catches extras, missing, duplicates and accumulation).
* R1a — the probe mixin reproduces elytraslot's `@Inject(HEAD, cancellable)` shape (`ci.cancel()` + re-entry with the consumer the **parameter** carried), and asserts `sawOriginalConsumer`, delivery count `== 1`, value `raw*m` **and** explicitly `!= raw*m*m`. A parameter-replacing hook fails three of these. R2's catch block cannot hide anything: it calls `require(false, …)`.
* R3a/R3b — includes the explicit "this check is not vacuous" assertion `!near(funnelAttack, raw*m)`.
* R4 — NBT round-trip of a damaged set plus `permanentModifiers.isEmpty()` and "no `durability_armor` id in the serialized entity"; honest about the UUID workaround (the same live level, not a real world reload).
* D1–D7 — bit-identical results for a rebuilt/equal stack, pinning the "pure function of the stack" property without hardcoded numbers.

**Weaknesses / gaps I am asking for (not weaknesses to hide):**
1. **R1e's ASM scan encodes the same index assumption as the implementation.** `biConsumerParameterIndex(target)` computes the BiConsumer position in the *descriptor* (receiver excluded), which is exactly the semantics I proved in §2.2 — but as an oracle it is circular: if the assumption were wrong, the scan would still pass. The empirical live checks (B11/B15) are what actually falsify a wrong index, so this is acceptable; label it as a "shape guard", not as proof of index semantics.
2. **No test for the group overload staying unscaled** (`forEachModifier(EquipmentSlotGroup, TriConsumer)`), even though README/DESIGN make "tooltips unchanged" a documented contract. Add: damaged chestplate + `forEachModifier(EquipmentSlotGroup.CHEST, triConsumer)` → ARMOR amount must be `raw`, never `raw*m`. This pins §2.12 against a future "hook both overloads" refactor.
3. **The `.value()` fallback in `scalesAttribute` is never exercised** (A27–A30 use the canonical holders and hit the identity fast-path). Add: `scale(damaged, Holder.direct(Attributes.ARMOR.value()), mod)` must scale (and a non-armor direct holder must not).
4. **No direct test of the id-based removal under a *differently scaled* instance** (the situation the mixin actually creates on `stopLocationBasedEffects`). Add: take the modifier instance out of `AttributeInstance.getModifiers()`, rebuild it with the same id but a different amount, feed it to the vanilla removal consumer (or `instance.removeModifier(rebuilt)`), and assert the modifier is gone and the value returns to base. This is the invariant that keeps requirement 5 safe.
5. **A20/A21 test vanilla, not us** (P2-c) and **the EnchantmentHelper dispatch is untested at runtime** (P2-b).
6. No client-side check is possible in this harness (dedicated server); the client path can only be confirmed in-game (§5.3) — the suite is honest about not faking it.

The suite reports `PASS: 178 checks`, 178 `OK` lines, 0 `FAILED`, no `skip/assume/todo` markers, and the Gradle gate requires the file to start with `PASS` after deleting any previous result.

---

## 5. Verifications the Lead must execute to close this review

I could not run Gradle or the game, so these remain open. Ordered by value.

1. **Interop launch with the real jars (highest value, currently untested):** run the user's 26.2-Fabric instance mods folder (or, if those mods cannot load on the test server, the real client instance) with the built jar and confirm: no mixin application error / crash at startup, and `durability_armor.mixins.json` applies both `@ModifyArg`s. This is the only check for rearm's `@ModifyReceiver` + elytraslot's `@Inject(HEAD)` living in the same JVM as our two `@ModifyArg`s (§2.6–2.7 are static arguments). Expected: no error; optional confirmation via the Mixin debug log line `Mixing ItemStackMixin … into net.minecraft.world.item.ItemStack`.
2. **In-game durability behaviour with lithium active:** damage an equipped armor piece and confirm (a) the HUD armor bar / F3 attribute value drops on the next tick, (b) removing and re-equipping restores the full value, (c) no duplicate/accumulating modifiers (`/attribute <player> minecraft:armor get`). This closes the lithium chain (§2.5) empirically.
3. **Client/server agreement:** on a real client+server pair, damage another entity's or your own armor and confirm the client display matches the server value, and that no mod-owned packet is registered. Note the expected HUD latency of ~1 tick after the server re-applies (the protection used for the damaging hit is intentionally the pre-hit value — `getDamageAfterArmorAbsorb` calls `hurtArmor` before `CombatRules`).
4. **Tooltip/contract sanity:** confirm a damaged chestplate shows unscaled values in its tooltip and scaled values in the armor bar (§2.12), and that `durability_armor` is not required on both sides (`environment: "*"`).
5. **Re-run the authoritative build/tests on the frozen tree:** `export JAVA_HOME=$(/usr/libexec/java_home -v 25) && ./gradlew build && ./gradlew runIntegrationTest -PacceptMinecraftEula=true`, confirming `head -1 run-test/test-result.txt` = `PASS: 178 checks` (or the new count after the additions in §4). The existing artifact (`PASS: 178`, 15:34) already covers the current hashes, so this is a re-confirmation, not a blocker.
6. **After any edit to `src/main/**`:** the hashes in the header must be re-pinned and §2.3 (id preservation), §2.2 (indices) and R1a re-checked. Docs fixes for P2-a are the only change I recommend outside tests.

---

## 6. Unknowns (stated, not guessed)

* **rearm coexistence in a live JVM** — argued from injector bytecode (different operands, both insert-before, INVOKE preserved), not observed. If the Lead's §5.1 launch shows a mixin error, the evidence points to injector ordering as the cause; the first thing to try is raising our `@ModifyArg` priority above rearm's (or vice versa) and re-launching — measure, do not guess.
* **firstaid's full damage pipeline** — I verified that firstaid reads armor through the hooked funnel, but not whether its own reduction and vanilla `CombatRules` can both apply in the same damage event. This is pre-existing firstaid behaviour and orthogonal to our scaling, but "no double reduction with firstaid" is **not** verified by me.
* **Client rendering of the armor bar for *other* entities** — the packet path is proven; whether every client-side widget reads the attribute is not something I can test on a dedicated server.
* **Enchantment-sourced armor modifiers from mods** — no vanilla case exists, and no runtime test exists; the handler's correctness rests on §2.1/§2.2 plus R1e.
* **The theoretical `maxDamage ≳ 9.49e7` boundary (P2-d)** — analysed, deliberately not tested; no user-visible impact.

**Biggest single uncertainty:** the real-jar interop launch (§5.1). Everything else is either re-derived from bytecode or covered by the passing suite; the combination `our @ModifyArg` + `rearm @ModifyReceiver` + `elytraslot @Inject(HEAD)` in one JVM is the one thing that is only argued, not observed.
