package de.jexcellence.economy.permission;

import org.bukkit.permissions.Permissible;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EconomyPermissionTest {

    private static final Path DESCRIPTORS = Path.of("..", "jexeconomy-plugin", "src", "main", "resources");
    private static final List<String> DESCRIPTOR_FILES = List.of("plugin.yml", "paper-plugin.yml");
    private static final List<String> COMMAND_TREES = List.of("balance", "coupon", "currencies", "currencylog",
            "economy", "pay");
    private static final String DEFAULT = "default";

    @Test
    void explicitNewNodeWinsOverTheOldOne() {
        Permissible player = mock(Permissible.class);
        when(player.isPermissionSet("jexeconomy.admin.give")).thenReturn(true);
        when(player.hasPermission("jexeconomy.admin.give")).thenReturn(false);
        when(player.isPermissionSet("economy.command.give")).thenReturn(true);
        when(player.hasPermission("economy.command.give")).thenReturn(true);

        assertFalse(EconomyPermission.ADMIN_GIVE.has(player));
    }

    @Test
    void oldNodeAloneStillGrants() {
        Permissible player = mock(Permissible.class);
        when(player.isPermissionSet("economy.command.give")).thenReturn(true);
        when(player.hasPermission("economy.command.give")).thenReturn(true);

        assertTrue(EconomyPermission.ADMIN_GIVE.has(player));
    }

    @Test
    void deniedOldNodeDeniesWithoutANewGrant() {
        Permissible player = mock(Permissible.class);
        when(player.hasPermission("jexeconomy.balance")).thenReturn(true);
        when(player.isPermissionSet("currency.command")).thenReturn(true);
        when(player.hasPermission("currency.command")).thenReturn(false);

        assertFalse(EconomyPermission.BALANCE.has(player));
    }

    @Test
    void withoutExplicitNodesTheNewDefaultDecides() {
        Permissible player = mock(Permissible.class);
        when(player.hasPermission("jexeconomy.pay")).thenReturn(true);

        assertTrue(EconomyPermission.PAY.has(player));
        assertFalse(EconomyPermission.ADMIN_SET.has(player));
    }

    @Test
    void secondOldNodeOfTheEditPermissionCounts() {
        Permissible player = mock(Permissible.class);
        when(player.isPermissionSet("jexeconomy.admin.edit")).thenReturn(true);
        when(player.hasPermission("jexeconomy.admin.edit")).thenReturn(true);

        assertTrue(EconomyPermission.CURRENCIES_EDIT.has(player));
    }

    @Test
    void adminWildcardAndOperatorsPassAdminChecks() {
        Permissible wildcard = mock(Permissible.class);
        when(wildcard.hasPermission(EconomyPermission.ADMIN_WILDCARD)).thenReturn(true);
        Permissible operator = mock(Permissible.class);
        when(operator.isOp()).thenReturn(true);

        assertTrue(EconomyPermission.CURRENCIES_DELETE.hasOrAdmin(wildcard));
        assertTrue(EconomyPermission.CURRENCIES_DELETE.hasOrAdmin(operator));
        assertFalse(EconomyPermission.CURRENCIES_DELETE.hasOrAdmin(mock(Permissible.class)));
    }

    @Test
    void oldCurrencyResetNodeKeepsItsMeaning() {
        Permissible holder = mock(Permissible.class);
        when(holder.isPermissionSet("jexeconomy.admin.reset")).thenReturn(true);
        when(holder.hasPermission("jexeconomy.admin.reset")).thenReturn(true);

        assertTrue(EconomyPermission.CURRENCIES_RESET.has(holder));
        assertFalse(EconomyPermission.ADMIN_RESET_PLAYER.has(holder));
        assertEquals(List.of("economy.command.reset"), EconomyPermission.ADMIN_RESET_PLAYER.legacyNodes());
    }

    @Test
    void everyOldNodeIsTheAliasOfExactlyOneNewNode() {
        Set<String> seen = new HashSet<>();
        for (EconomyPermission permission : EconomyPermission.values()) {
            for (String legacy : permission.legacyNodes()) {
                assertTrue(EconomyPermission.byNode(legacy).isEmpty(), legacy + " is also a new node");
                assertTrue(seen.add(legacy), legacy + " is the alias of two permissions");
            }
        }
    }

    @Test
    void descriptorsDeclareEveryNodeAndAlias() throws IOException {
        for (String descriptor : DESCRIPTOR_FILES) {
            Map<?, ?> permissions = descriptorPermissions(descriptor);
            for (EconomyPermission permission : EconomyPermission.values()) {
                Map<?, ?> node = entry(permissions, permission.node(), descriptor);
                assertEquals(permission.defaultValue().name().toLowerCase(Locale.ROOT),
                        String.valueOf(node.get(DEFAULT)).toLowerCase(Locale.ROOT), permission.node());
                for (String legacy : permission.legacyNodes()) {
                    Map<?, ?> alias = entry(permissions, legacy, descriptor);
                    assertEquals(Boolean.FALSE, alias.get(DEFAULT), legacy);
                    Map<?, ?> children = assertInstanceOf(Map.class, alias.get("children"), legacy);
                    assertEquals(Boolean.TRUE, children.get(permission.node()), legacy + " -> " + permission.node());
                }
            }
        }
    }

    @Test
    void commandTreesNameOnlyKnownNodes() throws IOException {
        for (String tree : COMMAND_TREES) {
            List<String> nodes = permissionsOf("commands/" + tree + ".yml");
            assertFalse(nodes.isEmpty(), tree);
            for (String node : nodes) {
                assertTrue(EconomyPermission.byNode(node).isPresent(), tree + ".yml: " + node);
            }
        }
    }

    private static Map<?, ?> descriptorPermissions(String descriptor) throws IOException {
        try (Reader reader = Files.newBufferedReader(DESCRIPTORS.resolve(descriptor), StandardCharsets.UTF_8)) {
            Map<?, ?> root = new Yaml().load(reader);
            return assertInstanceOf(Map.class, root.get("permissions"), descriptor);
        }
    }

    private static Map<?, ?> entry(Map<?, ?> permissions, String node, String descriptor) {
        Object value = permissions.get(node);
        assertNotNull(value, descriptor + " lacks " + node);
        return assertInstanceOf(Map.class, value, node);
    }

    private static List<String> permissionsOf(String resource) throws IOException {
        try (InputStream in = EconomyPermissionTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, resource);
            Map<?, ?> root = new Yaml().load(in);
            List<String> nodes = new ArrayList<>();
            collect(root, nodes);
            return nodes;
        }
    }

    private static void collect(Map<?, ?> node, List<String> nodes) {
        if (node.get("permission") instanceof String permission) {
            nodes.add(permission);
        }
        if (node.get("subcommands") instanceof List<?> subcommands) {
            for (Object sub : subcommands) {
                if (sub instanceof Map<?, ?> child) {
                    collect(child, nodes);
                }
            }
        }
    }
}
