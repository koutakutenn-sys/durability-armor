# Durability Armor

Armor stops protecting you properly once it is beaten up. The protection each equipped piece
provides shrinks with its remaining durability:

```
r          = remaining durability / max durability
multiplier = 1 - (1 - r)^2

armor value     = original armor value     * multiplier
armor toughness = original armor toughness * multiplier
```

| remaining durability | 100% | 75% | 50% | 25% | 0% |
| --- | --- | --- | --- | --- | --- |
| multiplier | 1.00 | 0.9375 | 0.75 | 0.4375 | 0.00 |

Knockback resistance is deliberately **not** affected.

## How it works

The mod never edits items. It hooks the single vanilla code path that pushes equipment attribute
modifiers into an entity's attribute map
(`ItemStack#forEachModifier(EquipmentSlot, BiConsumer)`, used by `LivingEntity#collectEquipmentChanges`)
and scales the `minecraft:armor` / `minecraft:armor_toughness` amounts on the fly: additive
modifiers (`ADD_VALUE`, `ADD_MULTIPLIED_BASE`) are scaled, while `ADD_MULTIPLIED_TOTAL` modifiers are
left alone — that is what makes the piece's *final* contribution equal `original value × multiplier`
(vanilla computes `(base + Σ add) · (1 + Σ total)`). No durability exemption is made for
`unbreakable` items either: they are treated like any other stack, and since they normally carry no
damage their multiplier is simply 1.

Because of that:

* the item's data components and base attributes are untouched (no permanent changes, nothing to
  undo when you uninstall);
* the scaled value is produced on the server by vanilla's own equipment-attribute pipeline and is
  mirrored to the client by the vanilla `ClientboundUpdateAttributesPacket` (whose snapshot carries
  the scaled transient modifiers, and which the client applies as a full replacement), so the client
  cannot diverge and the mod needs no packets of its own;
* armor from other mods is covered automatically as long as it uses the standard attribute-modifier
  component, exactly like vanilla armor;
* nothing is added on top of the vanilla equipment modifiers — the same modifier ids are reused, so
  attributes cannot stack up.

Damage reduction, the HUD armor bar and anything else that reads `Attributes.ARMOR` /
`Attributes.ARMOR_TOUGHNESS` from the entity see the scaled values.

## Compatibility

* Minecraft 26.2, Fabric Loader >= 0.19.5, Java >= 25.
* No Fabric API requirement, no configuration file.
* Works on both client and dedicated server (`environment: "*"`). The value is computed server-side
  and mirrored to the client by vanilla, so both sides always show the same protection.

### Known behaviour

* Vanilla re-applies equipment attribute modifiers on the tick after a piece's durability changes,
  so a hit that damages your armor is still reduced with the previous tick's value. From the next
  tick on, the reduced protection is in effect.
* The item's own attribute lines in the tooltip are untouched (the item component is never edited).
  Below them the mod adds red lines reporting what that piece has already lost to durability:

  ```
  +8 Armor
  +2 Armor Toughness
  Wear: -2 Armor
  Wear: -0.5 Armor Toughness
  ```

  A pristine piece adds nothing, and hiding the attribute lines with the vanilla tooltip-display
  setting hides these lines too. The numbers are measured against the very scaling the runtime
  applies, so the tooltip and the live value cannot disagree.
* Armor points injected directly into an `AttributeInstance` by another mod, bypassing the standard
  item attribute component, are not scaled. Likewise an attribute's **base value** and any
  `ADD_MULTIPLIED_TOTAL` factor are never scaled (the latter is a global multiplier, not a
  contribution owned by a single piece).
* The mod ships no client-specific code: the tooltip hook targets `ItemStack`, a common class, and the
  protection value itself is still computed server-side and mirrored to the client. Knockback
  resistance is deliberately never scaled, so it never prints a `Wear:` line.

## Build

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
./gradlew build
./gradlew runIntegrationTest -PacceptMinecraftEula=true
```

The integration suite runs on a dedicated server and writes its report to `run-test/test-result.txt`.

## License

MIT. See [LICENSE](LICENSE).
