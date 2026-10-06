# Independent adversarial code review — `durability_armor` (MC 26.2 / Fabric loader 0.19.5)

| | |
| --- | --- |
| Task | `task-6` — independent adversarial code review; updated under `task-8` (new runtime + vanilla evidence, §7) |
| Author | teammate `reviewer` (read-only for `src/**`; only this file written) |
| Date | 2026-10-06 ~15:35–15:45 +0800; T8 update ~16:10–16:25 +0800 |
| Frozen design | `docs/DESIGN.md` v1.0.0+mc26.2 |
| Reviewed revision (sha256, re-verified for T8) | `ArmorDurabilityScaling.java` `90eeb7d588e76175ddee77f4cc7edd1d1f0c932954f4b76caf08a41a31dda893`; `ItemStackMixin.java` `b0166d4abb95ed5206747e410933382e19e3422b92fb597d99c8d6c8a7dbb0e5`; `fabric.mod.json` `ed72cc9b2fe4a51912fa5bd64785dde9f3925b47cbe3e812342d1b585d88c07d`; `durability_armor.mixins.json` `e534461af364680d904c0dd9d4cb5fe8908e93ba43f405ff9e67a63e4351649c`; built jar `2963567382409083cb7c21749a81ba14c38a04a4f06c9cda2154b2acc7800cbe` (rebuilt 16:09:20, byte-identical) |
| Test revision pinned | suite `DurabilityArmorIntegrationTests.java` `4ef8de4d185badbbb801e490dda48ab178924ed6bafdd1aba2d00fdcbe7e486d`; `RearmProbe.java` `e59a073b8bc397d3e809410663a26e62d596efd3690170bca5acb1cc1e841d32`; `RearmStyleReceiverProbeMixin.java` `f82c7533a6f2f72c47aa351ca4f4090852d5959fd6e9c47e7193ff54b5dcf88b` |
| Method | `javap -p -c/-v` on the mapped 26.2 jar, the Mixin 0.17.4 runtime, MixinExtras 0.5.5 and every installed mod jar; read-only unzip into `/tmp`; jar/class/metadata inspection of the built artifact; read of `src/**` and the existing run artifacts. **No Gradle was run, no game was started, nothing outside this file was modified.** |
| Runtime evidence used (pre-existing, not produced by me) | T8 run: `run-test/test-result.txt` = `PASS: 222 checks` (mtime 16:09:24, 222 `OK`, 0 `FAILED`; A44+B33+C13+D10+R1 18+R2 18+R3 13+R4 11+R5 18+G1 17+G2 6+G3 10+G4 11 = 222), `run-test/logs/debug.log` (16:09:24) contains both `Mixing RearmStyleReceiverProbeMixin … into net.minecraft.world.item.ItemStack` (line 127) and `Mixing ItemStackMixin …` (line 128) and **no** `InvalidInjectionException`/`VerifyError`/`MixinApplyError`; test source mtime 16:07:13 < run 16:09:24, jar mtime 16:09:20. Earlier T6 run kept for history: `PASS: 178 checks` (15:34:40). Content hashes of `src/main/**` are identical across both runs; note `ArmorDurabilityScaling.java` was re-written at 16:09:12 with an **unchanged** hash (`90eeb7d5…`), so the pinned content is intact. |

**Chinese bottom line:** 代码与需求一致，仍未发现 P0/P1 级实现缺陷。T8 新增两项证据并已独立核实：① 与**真实 MixinExtras `@ModifyReceiver`** 在同一 INVOKE 上的共存已在运行时观察到（G1，17 项断言，服务器正常启动）——我原先的「最大不确定点」从"仅静态论证"升级为"测试环境内已运行验证"，但**用户实例中真实 rearm jar 的加载仍未验证**；② 原版 `ArmorMaterial.createAttributes` 对同一件护甲的 ARMOR / ARMOR_TOUGHNESS / KNOCKBACK_RESISTANCE 复用同一个 Identifier（我已用 javap 复核）——按 id 移除仍然安全，§2.3 结论不变。我原提的 3 个测试缺口（G2/G3/G4）已被补齐；剩余 P2-b（EnchantmentHelper 分支无运行时测试）未变。

---

## 0. Release verdict

**VERDICT: RELEASE-CANDIDATE — no P0 and no P1 defect found in `src/main/**`, `src/main/resources/**`, `build.gradle` or the built jar.** The implementation matches the user's five requirements as frozen in `docs/DESIGN.md`, the previously reported P1-1 (elytraslot recursion) fix is verified independently at bytecode level, and the lithium claim is verified independently and now has a complete evidence chain that the earlier audit lacked.

The verdict is **conditional** on §5 (the remaining in-game/interop verifications that my tooling cannot perform: no Gradle, no game run). It is **not** conditional on anything I was able to test myself.

Confidence (updated for T8): high on formula/edge-case/mixin-semantics/metadata/docs (all re-derived from bytecode and from the compiled artifact); high on lithium; **high on the coexistence of our `@ModifyArg`s with a real MixinExtras `@ModifyReceiver` on the same INVOKE** — section G1 of the suite now exercises exactly that shape on a live server (`PASS: 222`, no `InvalidInjectionException`/`VerifyError`), which retires the bytecode-only argument of §2.7; the *only* remaining interop unknown is loading the actual `rearm-fabric-2.5.6+26.2.jar` (plus its own mixins and config library) in the user's real 26.2-Fabric instance.

---

## 1. Requirement conformance (原文逐条)

| # | Requirement | Verdict | Independent basis |
| --- | --- | --- | --- |
| 1 | `r = 当前耐久/最大耐久`; `multiplier = 1-(1-r)^2`; 护甲值 = 原始*m; 护甲韧性 = 原始*m | **PASS** | `ArmorDurabilityScaling.multiplier` reads `MAX_DAMAGE` + `DAMAGE` and computes `1 - deficit*deficit`; `scale()` multiplies `amount` and keeps `id()`/`operation()`. Closed form + documented 0.75 / 0.4375 pinned by suite A9–A13; live entity sums pinned by B11/B12/B23/B24. Independently: `ItemStack.getDamageValue() = Mth.clamp(getOrDefault(DAMAGE,0), 0, getMaxDamage())`, so `r` is exactly remaining/max. |
| 2 | 击退抗性不受影响 | **PASS** | `scalesAttribute` is true only for `ARMOR`/`ARMOR_TOUGHNESS`; `KNOCKBACK_RESISTANCE` and `EXPLOSION_KNOCKBACK_RESISTANCE` (blast_protection's enchantment attribute) are untouched. B32/B33/R5e assert entity + funnel bit-identity. |
| 3 | 动态计算，不永久修改基础属性 | **PASS** | `scale()` builds a new immutable `AttributeModifier` for the transient application only; no component write anywhere in `src/main`. C2/C3 (component byte-identical), C9/C10 (no mod-namespace modifier), R4 (nothing persisted in entity NBT) all pass. |
| 4 | 兼容原版与其他 Mod 护甲 | **PASS** | All vanilla armor goes through the hooked funnel; the only vanilla caller of the hooked overload is `LivingEntity` (2 call sites); modded armor that uses the standard attribute-modifier component is covered. 4 installed mods touch this area: elytraslot (re-entry — fixed and regression-guarded), rearm (`@ModifyReceiver` on the same INVOKE — now **observed coexisting at runtime** with a real MixinExtras `@ModifyReceiver`, suite G1), firstaid (consumes the funnel read-only — automatically durability-aware), lithium (change detection preserved, bytecode chain in §2.5). Only "the real rearm jar inside the user's instance" remains a runtime check. See §2.5–2.8, §3, §5. |
| 5 | 不重复叠加 / 重进世界正常 / 不双端不同步 | **PASS** | Removal is by **id** (`AttributeInstance.removeModifier(AttributeModifier)` → `removeModifier(Identifier)`), add is guarded by `addModifier`'s duplicate-id `IllegalArgumentException`, and the hook never changes the id → remove-then-add cannot stack; G4 additionally installs a same-id modifier with a deliberately different amount and proves it is still removed, and §2.14 shows the shared vanilla id across ARMOR/ARMOR_TOUGHNESS/KBR is a non-issue because addressing is per `AttributeInstance`. Only `permanentModifiers` are persisted (§2.11). Client mirrors the server's snapshot (see the correction in §2.9). |

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
→ Because `scale()` returns `new AttributeModifier(modifier.id(), amount*m, modifier.operation())`, both paths stay consistent even though the mixin also wraps the *removal* consumer (which receives a freshly scaled instance that is only used for its id). **This is why "double stacking" is impossible and why a scaled modifier can always be removed.** One nuance on "unique id": the id only has to be unique **within one `AttributeInstance`** — vanilla deliberately reuses one id across a piece's ARMOR / ARMOR_TOUGHNESS / KNOCKBACK_RESISTANCE entries, which live on three different instances (see §2.14). That does not change this conclusion.

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
Static verdict: compatible. **T8 update — this is no longer bytecode-only.** `RearmStyleReceiverProbeMixin` (`src/integrationTest/**`, test-only) uses the *real* `com.llamalad7.mixinextras.injector.ModifyReceiver` on the very same `method`/`target` strings as rearm's real mixin, with rearm's handler shape `(ItemAttributeModifiers, EquipmentSlot, BiConsumer) -> ItemAttributeModifiers`, appending an ARMOR_TOUGHNESS modifier plus a non-armor ATTACK_DAMAGE control. On the authoritative run the server started with `Mixing RearmStyleReceiverProbeMixin …` **and** `Mixing ItemStackMixin …` applied to `ItemStack`, and section G1 (17 checks) observed the appended toughness as exactly `4.0 * m` (asserted also `!= 4.0` and `!= 4.0 * m * m`), the component's own ARMOR as exactly `raw * m`, and the appended ATTACK_DAMAGE unscaled (see §4 for the non-vacuity analysis). What remains unverified is only the real `rearm-fabric-2.5.6+26.2.jar` in the user's instance (its other mixins, its config library and its `AttributeMixin` armor cap are not exercised by the probe). See §5 and §6.

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

### 2.14 New vanilla fact (T8): `ArmorMaterial.createAttributes` reuses ONE `Identifier` across three entries — safe, and §2.3 is unchanged
Reported by `impl-tests`; verified independently with `javap -p -c` on `net.minecraft.world.item.equipment.ArmorMaterial.createAttributes(ArmorType)`:
```
34: aload_1; invokevirtual ArmorType.getName()          // local 5 =
38: invokedynamic makeConcatWithConstants:(String)String  //   Identifier.withDefaultNamespace("<type>…")
43: invokestatic Identifier.withDefaultNamespace(String)  //   (one id, computed once)
…
48-68: builder.add(Attributes.ARMOR,           new AttributeModifier(local5, defense,  ADD_VALUE), group)
72-95: builder.add(Attributes.ARMOR_TOUGHNESS,  new AttributeModifier(local5, toughness, ADD_VALUE), group)
99-131: if (knockbackResistance > 0)
           builder.add(Attributes.KNOCKBACK_RESISTANCE, new AttributeModifier(local5, kbr, ADD_VALUE), group)
```
So one armor piece's `ATTRIBUTE_MODIFIERS` component can legally contain **the same modifier id two or three times**, once per attribute (KBR only when `knockbackResistance > 0`; verified from `ArmorMaterials`: diamond is `fconst_2, fconst_0` → toughness 2.0 / kbr 0.0 so it has no KBR entry, netherite is `ldc 3.0f, ldc 0.1f` so it does). Consequences:
* **Removal by id stays safe.** `AttributeInstance.removeModifier(Identifier)` removes from *that instance's* `modifierById`/`modifiersByOperation`/`permanentModifiers` only, and `addModifier`'s duplicate check is likewise per instance. Both consumers in `LivingEntity` (`lambda$collectEquipmentChanges$0`, `lambda$stopLocationBasedEffects$0`) resolve the instance first via `AttributeMap.getInstance(attribute)` and then act on it, so there is no cross-attribute removal. The G1/G4 sections plus G1's "vanilla reuses one modifier id per armour piece across ARMOR and ARMOR_TOUGHNESS" fixture pin this at runtime.
* **§2.3 is unaffected**: the invariant that matters is *id stability per `AttributeInstance`*, and `scale()` preserves the id. Nothing in `src/main` keys equipment modifiers by id globally (it only preserves the id), so the reuse cannot confuse the hook.
* **It is a trap for id-only bookkeeping** elsewhere, and the suite handles it correctly: `collectExpected`/`amountsFor` filter by attribute before keying by id, and G1 explicitly asserts `componentArmor.equals(componentToughness) && !componentArmor.isEmpty()` so that a future vanilla change that makes the two ids different fails loudly rather than silently merging. Worth one line in DESIGN §7 as a known vanilla quirk.

---

## 3. Findings

### P0 — none.

### P1 — none in the reviewed sources.

### P2 — documentation/behaviour/coverage items (none block release, all cheap to fix)

**P2-a — `DESIGN.md` §3, `README.md`, `README.zh-CN.md` describe a client-side computation that does not happen.** *Evidence:* §2.9. Claims affected: DESIGN "so client and server compute identical values with no packets of our own"; README "the scaled value is a pure function of the synced `ItemStack`, so client and server always agree" and "values are computed identically on both sides"; README.zh-CN "数值完全由（已同步的）ItemStack 推导，客户端与服务端计算结果一致". *Impact:* the mechanism claim is false and would mislead any future maintainer (e.g. someone "restoring symmetry" by also hooking a client path could introduce a genuine double-apply). The outcome claims (no mod packets, no desync) are correct. *Recommendation:* reword to "the server computes the scaled value and the client receives it in `ClientboundUpdateAttributesPacket` (which carries the transient equipment modifiers); therefore the client cannot diverge and the mod needs no packets of its own." Note the wording issue also weakens the *reason* given for the chosen architecture, so it should be fixed in DESIGN §3 too — Lead-owned files; flagging, not editing.

**P2-b — the `EnchantmentHelper` dispatch (`@ModifyArg index = 2`) has no runtime test.** No vanilla enchantment grants `minecraft:armor`/`minecraft:armor_toughness`: I checked all 43 enchantment JSONs in the jar and the complete set of enchantment-granted attributes is `burning_time, explosion_knockback_resistance, mining_efficiency, movement_efficiency, movement_speed, oxygen_bonus, sneaking_speed, submerged_mining_speed, sweeping_damage_ratio, water_movement_efficiency` — neither ARMOR nor ARMOR_TOUGHNESS is among them. So with vanilla content that handler is never exercised at runtime; only the static ASM scan (R1e) asserts its existence/index. *Impact:* a modded armor enchantment is the reason the handler exists (requirement 4), and a regression there would go unnoticed. *Recommendation:* add an integrationTest datapack defining an enchantment with an attribute effect granting `minecraft:armor` (id under the test namespace), apply it via `DataComponents.ENCHANTMENTS` to a damaged chestplate, and assert the funnel emits `raw * m` exactly once.

**P2-c — A20/A21 ("negative damage") assert vanilla clamping, not our branch.** `setDamageValue(-5)` clamps to 0 inside `ItemStack`, so the test proves `getDamageValue() == 0` and `multiplier == 1.0` — it cannot reach the `remaining >= maxDamage` guard with a negative value, and no test can (the component value is clamped on write *and* on read). *Impact:* the "negative DAMAGE" edge case listed in the task is unreachable by construction; the guard is defensive dead-ish code (harmless, documents intent). *Recommendation:* either delete A20/A21 or relabel them as "vanilla clamps DAMAGE; our endpoint branch handles it"; the real endpoints (0 / max / >max / `maxDamage <= 0`) are covered by A16–A19, A24–A26, R5b.

**P2-d — theoretical `multiplier == 1.0` collision at absurd `MAX_DAMAGE`.** `1.0 - deficit*deficit` rounds to exactly `1.0` when `deficit² < 2^-53`, i.e. `deficit < ~1.054e-8`, i.e. `maxDamage ≳ 9.49e7` (with `remaining = maxDamage-1`). Since `MAX_DAMAGE` is a settable component, a datapack/mod could in principle create such a stack, and `scale()` would then skip scaling for that one point of durability. *Impact:* the skipped correction is `< 1.2e-16` relative — physically irrelevant; no test covers it. *Recommendation:* no code change; optionally document the boundary or replace the `== 1.0` shortcut with a `>= 1.0` check (equivalent in practice).

**P2-e — `compatibilityLevel: "JAVA_25"` is above what the bundled Mixin understands.** Both configs declare `JAVA_25` and Mixin 0.17.4 caps at `JAVA_13`, so it logs once per run for whichever config it processes first: the T8 `run-test/logs/debug.log` line 34 shows `Compatibility level JAVA_25 specified by durability_armor_test.mixins.json is higher than the maximum level supported by this version of mixin (JAVA_13)` followed by `Compatibility level set to JAVA_25`; the rotated `run-test/logs/debug-1.log.gz` shows the same message for `durability_armor.mixins.json`. It works in this configuration (the hook applies — `Mixing ItemStackMixin …` line 128), and `elytraslot` ships `JAVA_21`, so `JAVA_25` is a house-style choice rather than an anomaly. *Impact:* a stricter Mixin build could reject or mis-handle class-version checks; a warning in every log. *Recommendation:* keep, but record the tolerated warning in DESIGN §7, or lower to `JAVA_21`.

**P2-f — "armor value × m" is only exactly true for additive modifier operations.** `scale()` multiplies `amount` regardless of `operation()`. All vanilla armor uses `ADD_VALUE` (the suite asserts this for its fixtures, B5/B22), for which amount-scaling *is* contribution-scaling. A modded armor piece using `ADD_MULTIPLIED_BASE/TOTAL` on `minecraft:armor` would get "the modifier amount scaled", not strictly "the piece's final contribution × m". *Impact:* none for vanilla/modded-ADD_VALUE armor; unspecified-but-reasonable otherwise. *Recommendation:* one sentence in DESIGN §7.

### Cleared (explicitly checked, no finding)
* **Double scale / zero scale:** only two dispatch calls exist; both are hooked; `defaultRequire: 1` makes a missing hook a hard failure; the removal consumer is id-based; no marker/static state is needed; elytraslot and rearm both analysed in §2.6–2.7.
* **Unintended attributes:** only `ARMOR`/`ARMOR_TOUGHNESS`; `ATTACK_DAMAGE` (R3, and now the receiver-appended ATTACK_DAMAGE control in G1), `KNOCKBACK_RESISTANCE` (B32/R5e), `EXPLOSION_KNOCKBACK_RESISTANCE` (blast_protection's attribute) are untouched. `Holder` comparison uses an identity fast-path plus a `.value()` identity fallback; the fallback is now genuinely exercised with `Holder.direct(...)` by suite G3 (fixture proves the identity fast path is missed), and an unbound holder cannot throw because the fallback is wrapped.
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
* **G1 (T8) — coexistence with a real MixinExtras `@ModifyReceiver` on the same INVOKE.** `RearmStyleReceiverProbeMixin` mirrors rearm's annotation (`method`/`target` identical to rearm's, verified by the ASM scan at G1's last check) and gates on `RearmProbe.shouldReplace((ItemStack)(Object) this)`, which only fires for the exact armed stack instance. Non-vacuity to my satisfaction: (i) the two appended ids `da_test:rearm_style_armor_toughness` / `da_test:rearm_style_attack_damage` exist **nowhere** in the item's component and cannot appear unless the receiver swap ran — the disarmed control asserts they are absent and the armed phase asserts they are present; (ii) the appended toughness is asserted to be exactly `4.0*m`, and **also** explicitly `!= 4.0` and `!= 4.0*m*m`, so neither "injector silently ignored" nor "our wrapper applied twice to the swapped receiver's entries" can pass; (iii) a real injector failure cannot be masked — an unapplied `@ModifyReceiver` leaves `replacements == 0` and G1's `replacements >= 1` check fails; (iv) `amountsFor()` collects through `stack.forEachModifier(slot, …)`, i.e. the hooked overload, so the assertion really measures *our* wrapper applied to the *swapped* receiver's entries. What it proves: our operands-1/2 `@ModifyArg`s and a real MixinExtras operand-0 `@ModifyReceiver` both apply to the same INVOKE on a live server, with no `InvalidInjectionException`/`VerifyError` and the expected single scaling.
* **G2 (T8) — the group overload stays raw**, closing my earlier gap (b): the fixture asserts `raw != raw*m` for the stack, then calls both overloads and requires the hooked `(EquipmentSlot)` overload to report `raw*m` while `(EquipmentSlotGroup, TriConsumer)` reports `raw`, and that the two differ. This pins the documented tooltip contract against a "hook both overloads" refactor.
* **G3 (T8) — the `.value()` fallback of `scalesAttribute`**, closing my earlier gap (c): fixture asserts `Holder.direct(Attributes.ARMOR.value()) != Attributes.ARMOR` while `.value()` is identical, so the identity fast path is provably missed; then `scalesAttribute`/`scale` must be true/scaled for direct ARMOR and ARMOR_TOUGHNESS holders and false/identity for direct KNOCKBACK_RESISTANCE and ATTACK_DAMAGE holders.
* **G4 (T8) — removal is by id, not by amount**, closing my earlier gap (d): after the equipment tick, the test installs a same-id modifier with amount `raw*m + 123` through `addOrUpdateTransientModifier`, asserts the id was replaced (one modifier, new amount — i.e. the conflicting value is really in place), then `removeModifier(instance)` and requires the id to be gone, the modifier set empty, the ARMOR value back to base, and — after a further damage tick — exactly one restored modifier with amount `raw*m`. An amount- or identity-based removal would leave the modifier behind and fail.

**Weaknesses / gaps — status after the T8 additions:**
1. **R1e's ASM scan still encodes the same index assumption as the implementation.** `biConsumerParameterIndex(target)` computes the BiConsumer position in the *descriptor* (receiver excluded), which is exactly the semantics I proved in §2.2 — but as an oracle it is circular: if the assumption were wrong, the scan would still pass. The empirical live checks (B11/B15, and now G1) are what actually falsify a wrong index, so this is acceptable; label it a "shape guard", not proof of index semantics.
2. ~~No test for the group overload staying unscaled~~ — **closed by G2** (6 checks: hooked overload `raw*m`, group overload `raw`, and the two must differ).
3. ~~The `.value()` fallback in `scalesAttribute` is never exercised~~ — **closed by G3** (10 checks with `Holder.direct`, fixture proves the identity fast path is missed).
4. ~~No direct test of the id-based removal under a differently scaled instance~~ — **closed by G4** (11 checks: same id with amount `raw*m + 123` is installed, then removed by the value overload → id gone, set empty, value back to base, and a later tick restores exactly one `raw*m`).
5. **Still open — A20/A21 test vanilla clamping rather than our branch (P2-c), and the `EnchantmentHelper` dispatch has no runtime test (P2-b).** No `G` section covers enchantment-sourced ARMOR/ARMOR_TOUGHNESS, so `@ModifyArg index = 2` remains statically verified only.
6. **Still open by design — no client-side check is possible on a dedicated server**; the client path is proven by bytecode (§2.9) and must be confirmed in-game (§5.3). The suite is honest about not faking it.
7. **Minor observation on G1's disarmed control (not a defect, but do not over-credit it):** its two halves have different strength. The *id-absence* half is a real guard — a rogue unconditional receiver swap would make `da_test:rearm_style_*` appear and fail the check. The `RearmProbe.replacements() == 0` half is structurally guaranteed and cannot fail: `shouldReplace` is the only caller, it does not increment while `armedStack == null`, and `arm()` (the only thing that could leave a non-zero counter) is called later in the same section. Harmless; the id-absence assertion is the one doing the work.
8. **Framing note:** G1 proves coexistence with a *faithful shape* of rearm (same annotation, same `method`/`target` strings, same handler signature, plus a toughness-and-attack pair), not with rearm's jar. Its handler body, its `AttributeMixin` armor-cap change and its own dependency set stay outside the harness.

The suite reports `PASS: 222 checks` (T8 run; 178 before G1–G4), 222 `OK` lines, 0 `FAILED`, no `skip/assume/todo` markers, and the Gradle gate requires the file to start with `PASS` after deleting any previous result.

---

## 5. Verifications the Lead must execute to close this review

I could not run Gradle or the game, so these remain open. Ordered by value.

1. **Interop launch with the real mod jars (highest remaining value).** The *generic* risk — our two `@ModifyArg`s and a real MixinExtras `@ModifyReceiver` on the same INVOKE, plus elytraslot's `@Inject(HEAD)` re-entry — is now covered at runtime by suites G1/R1a on the test server. Still unverified: loading `rearm-fabric-2.5.6+26.2.jar` itself (its other mixins, `AttributeMixin` armor cap, `fzzy_config` dependency) and `elytraslot-fabric-26.2-3.0.0.jar` inside the user's real 26.2-Fabric instance alongside the built jar. Run the instance and confirm: no mixin application error / crash at startup (`InvalidInjectionException`/`VerifyError`), and for a damaged armor piece the ARMOR/ARMOR_TOUGHNESS values behave as in the harness. Note that the probe cannot be copied into the instance; this is the real-jar check. Expected: no error (the harness succeeded with the same annotation, strings and handler signature).
2. **In-game durability behaviour with lithium active:** damage an equipped armor piece and confirm (a) the HUD armor bar / F3 attribute value drops on the next tick, (b) removing and re-equipping restores the full value, (c) no duplicate/accumulating modifiers (`/attribute <player> minecraft:armor get`). This closes the lithium chain (§2.5) empirically.
3. **Client/server agreement:** on a real client+server pair, damage another entity's or your own armor and confirm the client display matches the server value, and that no mod-owned packet is registered. Note the expected HUD latency of ~1 tick after the server re-applies (the protection used for the damaging hit is intentionally the pre-hit value — `getDamageAfterArmorAbsorb` calls `hurtArmor` before `CombatRules`).
4. **Tooltip/contract sanity:** confirm a damaged chestplate shows unscaled values in its tooltip and scaled values in the armor bar (§2.12), and that `durability_armor` is not required on both sides (`environment: "*"`).
5. **Re-run the authoritative build/tests on the frozen tree:** `export JAVA_HOME=$(/usr/libexec/java_home -v 25) && ./gradlew build && ./gradlew runIntegrationTest -PacceptMinecraftEula=true`, confirming `head -1 run-test/test-result.txt` = `PASS: 222 checks`. The T8 artifact (`PASS: 222`, 16:09:24) already covers the current hashes (test source 16:07:13 / jar 16:09:20 both older than the run, `src/main/**` hashes unchanged from the T6 run), so this is a re-confirmation, not a blocker.
6. **After any edit to `src/main/**`:** re-pin the header hashes and re-check §2.2 (indices), §2.3 (id preservation / per-instance id uniqueness, §2.14), R1a and G1–G4. The only change I recommend outside tests is the P2-a doc wording (`DESIGN.md` §3 + both READMEs) and an optional DESIGN §7 line for §2.14's shared-id quirk.

---

## 6. Unknowns (stated, not guessed)

* **rearm coexistence — reduced from "biggest uncertainty" to "harness-verified, real jar pending".** MixinExtras `@ModifyReceiver` is an order-independent in-place injector (`Target.insertBefore`, INVOKE preserved, §2.7) and suite G1 now observes the real annotation on the same INVOKE alongside our `@ModifyArg`s on a live server, with the appended ARMOR_TOUGHNESS scaled exactly once. What remains open is the actual `rearm-fabric-2.5.6+26.2.jar` in the user's instance (§5.1) — its own mixins and config library are not exercised by the probe. If the instance launch does show a mixin error, the evidence points to ordering/other-mod interaction; measure (e.g. raise our `@ModifyArg` priority), do not guess.
* **firstaid's full damage pipeline** — I verified that firstaid reads armor through the hooked funnel, but not whether its own reduction and vanilla `CombatRules` can both apply in the same damage event. This is pre-existing firstaid behaviour and orthogonal to our scaling, but "no double reduction with firstaid" is **not** verified by me.
* **Client rendering of the armor bar for *other* entities** — the packet path is proven; whether every client-side widget reads the attribute is not something I can test on a dedicated server.
* **Enchantment-sourced armor modifiers from mods** — no vanilla case exists, and no runtime test exists (§4 gap 5); the handler's correctness rests on §2.1/§2.2 plus R1e.
* **The theoretical `maxDamage ≳ 9.49e7` boundary (P2-d)** — analysed, deliberately not tested; no user-visible impact.

**Biggest single uncertainty (T8):** loading the **real** `rearm-fabric-2.5.6+26.2.jar` (and elytraslot's real jar) together with the built mod inside the user's 26.2-Fabric instance. The *shape* of that coexistence is now observed at runtime in the harness, so this is narrower than the T6 answer: it is a specific-jar/integration question (other mixins, `fzzy_config`, load order), not a question about whether a MixinExtras `@ModifyReceiver` and our `@ModifyArg`s can share the INVOKE.

---

## 7. Change log vs the T6 revision of this review (task-8)

No earlier conclusion was withdrawn or weakened; one uncertainty was retired and one vanilla fact was added.

| # | T6 statement | T8 status | Evidence |
| --- | --- | --- | --- |
| 1 | §6/§0/§5.1: rearm coexistence "argued from injector bytecode, **not observed**"; declared the biggest uncertainty | **Retired at the shape level** — now observed at runtime; only the real jar in the user's instance remains | `RearmStyleReceiverProbeMixin` uses the real `com.llamalad7.mixinextras.injector.ModifyReceiver` with rearm's exact `method`/`target`; G1 = 17 checks in the `PASS: 222` run; `debug.log` lines 127–128 apply probe + hook to `ItemStack`; no `InvalidInjectionException`/`VerifyError`; appended toughness `4.0*m` and explicitly `!= 4.0`, `!= 4.0*m*m` |
| 2 | §2.7 rearm analysis was "static verdict: compatible" with a residual-risk note | Rewritten: static analysis **plus** runtime evidence; residual narrowed to the real jar | as above |
| 3 | §0 confidence "medium-high on rearm+elytraslot interop" | **High** for the harness-observed shape; medium for the real jar only | §5.1 |
| 4 | §1 row 4 "PASS (static) / conditionally verified (runtime)" | **PASS** (same conclusion, now runtime-backed) | as above |
| 5 | §1 row 5 / §2.3: removal-by-id invariant | Strengthened: G4 installs a same-id modifier with a **different amount** and proves removal still works; §2.3 gains the "id is unique per `AttributeInstance`, not per item" nuance | G4 (11 checks), `AttributeInstance.removeModifier(AttributeModifier)` → `removeModifier(Identifier)` |
| 6 | — (new) | **New §2.14:** `ArmorMaterial.createAttributes` reuses one `Identifier` for a piece's ARMOR / ARMOR_TOUGHNESS / (optional) KNOCKBACK_RESISTANCE entries. Removal stays safe (different `AttributeInstance`s); **§2.3 unchanged**; it is a trap only for id-only bookkeeping, which the suite avoids per attribute | my own `javap -p -c` of `net.minecraft.world.item.equipment.ArmorMaterial.createAttributes`; G1's shared-id fixture |
| 7 | §4 gaps 2–4 (group overload raw, `Holder.value()` fallback, id-based removal) were *requested* fixes | **All three closed** by G2/G3/G4; §4 now analyses their non-vacuity and keeps gap 1 (R1e circularity) and gap 5 (P2-b enchantment) open, plus two new framing/strength notes | G2 6 + G3 10 + G4 11 checks; 178 → 222 total |
| 8 | Header hashes / runtime evidence | Re-pinned and re-verified: `src/main/**` and the jar hashes are **identical** (jar rebuilt 16:09:20 is byte-identical to 15:26); `ArmorDurabilityScaling.java` was re-written at 16:09:12 with an unchanged hash; test revision pinned separately | `shasum -a 256` on all files; mtimes in the header |
| 9 | P2-a (DESIGN §3 + both READMEs claim the client computes the value), P2-b (enchantment dispatch untested), P2-c (A20/A21 vacuous wrt our branch), P2-d (float boundary), P2-e (`JAVA_25` warning), P2-f (multiplicative operations) | **Unchanged — still open**; P2-a is still the one doc fix I recommend before release | §3 |
| 10 | lithium chain, elytraslot re-entry, persistence (§2.11), client-sync correction (§2.9), refmap (§2.10), tooltip path (§2.12), performance, metadata | **Unchanged** — no new evidence contradicted them | §2.5–2.13 |

**Net effect on the verdict:** unchanged (`RELEASE-CANDIDATE`, no P0/P1); the confidence on the single previously-argued interop item moved from "static argument" to "runtime observation", and one new vanilla fact is pinned with a documented (non-)impact.

---

## 8. Post-review addendum — superseded by the Codex final acceptance (Lead, 2026-10-06 16:40 +0800)

> Everything above reflects the revision pinned in the header (`ArmorDurabilityScaling.java` `90eeb7d5…`). **That revision has been superseded.** A separate, independent final acceptance performed by Codex on the then-current tree `f82f41a` rejected release and found **two P1 defects this review missed**, plus two test gaps. Both P1s were real, have been fixed, and the Lead re-verified the fixes and the suite on the frozen tree. This section is an appended record only; the review text above is left untouched as history.

**What Codex found (and this review did not):**

1. **P1-1 — the `UNBREAKABLE` exemption contradicted the requirement.** `multiplier(...)` returned `1.0` for any stack carrying `DataComponents.UNBREAKABLE`, but the requirement is literally `r = remaining / max durability` with no exemption, and `ItemStack.getDamageValue()` still reports the clamped `DAMAGE` component of an unbreakable stack. Codex's repro: diamond chestplate, `maxDamage 528`, `damage 264`, `+UNBREAKABLE` → multiplier `1.0` / armor `8.0` where `0.75` / `6.0` is required. **The suite did not fail on this — it asserted the wrong behaviour** (`A23` said "unbreakable is never scaled"), so a green suite proved nothing here. That is exactly the "self-fulfilling oracle" failure mode §4 was supposed to catch and did not.
2. **P1-2 — scaling `amount` for *every* operation is not `final value × multiplier`.** Vanilla computes `value = (base + Σ ADD_VALUE + base·Σ ADD_MULTIPLIED_BASE) · (1 + Σ ADD_MULTIPLIED_TOTAL)`; with `+8 ADD_VALUE` and `+0.5 ADD_MULTIPLIED_TOTAL` at `m = 0.75` the old code produced `(8·0.75)·(1 + 0.5·0.75) = 8.25`, not `12·0.75 = 9.0`. §2 of the review had noted this as a P2 *documentation* item; Codex correctly treated it as a requirement violation.

**Fixes (frozen revision `ArmorDurabilityScaling.java` `11b265514fd2c892f2afd33428908d6902885f61ab076d559943485bcb8b7957`; `ItemStackMixin.java` unchanged at `b0166d4a…`):**

* the `UNBREAKABLE` short-circuit is gone — `MAX_DAMAGE` + `DAMAGE` is used for every stack (ordinary damage-0 unbreakable gear is still `1.0` through the `remaining >= maxDamage` guard);
* `scale(...)` returns `ADD_MULTIPLIED_TOTAL` modifiers **unchanged** and scales only `ADD_VALUE` / `ADD_MULTIPLIED_BASE`, which yields exactly `original value × multiplier` for the vanilla formula (residual: a total factor and an attribute's base value are global/non-piece and are deliberately not scaled — now recorded in DESIGN §7).

**Test changes (suite now `PASS: 255 checks`, 0 FAILED):** `A23`/`R5c` **corrected** to the required behaviour (with the reason in the check name), `A23a`/`A23b` added to bracket both ends, a new 19-check `P1-2 operation aware scaling` section, G4 strengthened to 13 checks (removal with a newly constructed same-id/different-operation instance), and a new 9-check `GAP-A enchantment dispatch` section that finally gives the `EnchantmentHelper` `@ModifyArg` runtime coverage (Codex's P2-1 gap).

**Independent Lead verification on the restored tree:**

* `export JAVA_HOME=$(/usr/libexec/java_home -v 25) && ./gradlew clean build runIntegrationTest -PacceptMinecraftEula=true` → `BUILD SUCCESSFUL`, `PASS: 255 checks`, `OK=255`, `FAILED=0`, no serialization/`InvalidInjection` errors; jar `durability-armor-1.0.0+mc26.2.jar` sha256 `c15a9e9a4db05d26b4ece19f5be35659cadbc9f8836b9e4b3a558f007fd1a351`.
* **Negative control:** temporarily restoring the old `UNBREAKABLE` short-circuit *and* the old "scale every operation" behaviour made the suite go red — `BUILD FAILED`, `241 OK / 14 FAILED`, with the failures being exactly `A23`, `A23b`, both `R5c` lines and the whole `P1-2` section. The file was then restored byte-for-byte (`shasum` back to `11b26551…`, no marker text left).
* A harness-only shutdown warning (a `Holder.direct` enchantment that cannot be serialized, from the GAP-A fixture) was cleaned up in the same session; the shutdown log is now clean.

**Status of the review's own findings after this addendum:** P2-a (client-side computation wording) was **fixed by the Lead** in `DESIGN.md` §3, `README.md` and `README.zh-CN.md`; P2-c relabelled; P2-d/P2-e/P2-f documented in DESIGN §7; P2-b (enchantment dispatch) **closed** by GAP-A. The review's §5 in-game/integration items (real `rearm`/`elytraslot` jars inside the user's 26.2-Fabric instance, live client/server and world-reload checks) remain **not executed** — they need a human at the machine, and no agent has claimed otherwise.
