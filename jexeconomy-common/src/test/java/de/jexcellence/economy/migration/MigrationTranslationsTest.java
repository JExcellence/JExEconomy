package de.jexcellence.economy.migration;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every step and refusal of the import has its text in both languages.
 */
class MigrationTranslationsTest {

    private static final String BLOCK = "economy-v2.migration";

    private static ConfigurationSection load(String file) throws IOException, InvalidConfigurationException {
        try (InputStream in = MigrationTranslationsTest.class.getResourceAsStream("/translations/" + file)) {
            var yaml = new YamlConfiguration();
            yaml.loadFromString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return yaml.getConfigurationSection(BLOCK);
        }
    }

    @Test
    void bothLanguagesHaveTheSameTexts() throws IOException, InvalidConfigurationException {
        assertEquals(load("en_US.yml").getKeys(true), load("de_DE.yml").getKeys(true));
    }

    @Test
    void everyRefusalAndStepHasAText() throws IOException, InvalidConfigurationException {
        ConfigurationSection texts = load("en_US.yml");

        for (String reason : List.of("busy", "plan-unknown", "plan-stale", "run-unknown", "not-rollbackable",
                "nothing-paused", "source-unknown", "currency-unknown", "source-missing", "console-only",
                "id-invalid", "failed")) {
            assertTrue(texts.isString("error." + reason), reason);
        }
        for (String step : List.of("sources", "plan", "start", "pause", "resume", "status", "rollback")) {
            assertTrue(texts.isString("usage." + step + ".command"), step);
            assertTrue(texts.isString("usage." + step + ".text"), step);
        }
    }
}
