package dev.durabilityarmor;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. The whole feature is implemented inside {@code ArmorDurabilityScaling} plus the
 * {@code ItemStackMixin} hook, so there is nothing to initialise besides logging.
 */
public final class DurabilityArmor implements ModInitializer {
    public static final String MOD_ID = "durability_armor";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Durability Armor: armor value and armor toughness now scale with each piece's remaining durability");
    }
}
