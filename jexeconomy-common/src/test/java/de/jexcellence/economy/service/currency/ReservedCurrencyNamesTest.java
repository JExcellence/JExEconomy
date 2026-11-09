package de.jexcellence.economy.service.currency;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReservedCurrencyNamesTest {

    @Test
    void balanceSubcommandsAreReservedIgnoringCaseAndSpaces() {
        assertTrue(ReservedCurrencyNames.isReserved("all"));
        assertTrue(ReservedCurrencyNames.isReserved("TOP"));
        assertTrue(ReservedCurrencyNames.isReserved(" Help "));
    }

    @Test
    void ordinaryNamesAreFree() {
        assertFalse(ReservedCurrencyNames.isReserved("coins"));
        assertFalse(ReservedCurrencyNames.isReserved("allcoins"));
        assertFalse(ReservedCurrencyNames.isReserved("to"));
        assertFalse(ReservedCurrencyNames.isReserved(null));
    }

    @Test
    void namesMatchTheBalanceSubcommands() throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("commands/balance.yml")) {
            assertNotNull(in);
            Map<?, ?> root = new Yaml().load(in);
            List<String> names = new ArrayList<>();
            for (Object sub : (List<?>) root.get("subcommands")) {
                names.add(String.valueOf(((Map<?, ?>) sub).get("name")));
            }
            assertEquals(names, ReservedCurrencyNames.names());
        }
    }

    @Test
    void currenciesYmlRejectsAReservedIdentifier() throws InvalidConfigurationException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("currencies:\n  top:\n    symbol: T\n  gems:\n    symbol: G\n");
        List<String> warnings = new ArrayList<>();

        var seeds = CurrencyConfig.read(yaml, warnings::add).currencies().stream()
                .map(CurrencyConfig.Seed::identifier).toList();

        assertEquals(List.of("gems"), seeds);
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("reserved"), warnings.getFirst());
    }
}
