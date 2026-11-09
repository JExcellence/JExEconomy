package de.jexcellence.economy.command;

import com.raindropcentral.commands.v2.argument.ArgumentType;
import de.jexcellence.economy.command.admin.economy.MigrateAction;
import de.jexcellence.economy.command.player.currencies.CurrencyField;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class TokenArgumentTypeTest {

    private final CommandSender sender = mock(CommandSender.class);

    @Test
    void currencyFieldParsesIgnoringCase() {
        ArgumentType<CurrencyField> type = CurrencyField.argumentType();

        assertEquals(CurrencyField.ARGUMENT_TYPE, type.id());
        assertEquals(CurrencyField.SYMBOL, type.parse(sender, "Symbol").value());
        assertEquals(CurrencyField.IDENTIFIER, type.parse(sender, "identifier").value());
    }

    @Test
    void unknownFieldReportsTheValidTokens() {
        var result = CurrencyField.argumentType().parse(sender, "colour");

        assertFalse(result.isOk());
        assertEquals("jexcommand.error.invalid-enum", result.errorKey());
        assertEquals("identifier, symbol, prefix, suffix, icon", result.placeholders().get("options"));
        assertEquals("colour", result.placeholders().get("value"));
    }

    @Test
    void currencyFieldCompletesByPrefix() {
        assertEquals(List.of("symbol", "suffix"), CurrencyField.argumentType().complete(sender, "S"));
        assertEquals(5, CurrencyField.argumentType().complete(sender, "").size());
    }

    @Test
    void migrateActionsAreTheImportSteps() {
        ArgumentType<MigrateAction> type = MigrateAction.argumentType();

        assertEquals(List.of("sources", "plan", "start", "pause", "resume", "status", "rollback"),
                type.complete(sender, ""));
        assertEquals(MigrateAction.ROLLBACK, type.parse(sender, "ROLLBACK").value());
        assertEquals(List.of("start", "status"), type.complete(sender, "st"));
        assertFalse(type.parse(sender, "supported").isOk());
    }
}
