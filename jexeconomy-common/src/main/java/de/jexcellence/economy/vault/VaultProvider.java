package de.jexcellence.economy.vault;

import com.raindropcentral.commands.v2.argument.OfflinePlayerLookup;
import de.jexcellence.economy.api.TransactionResult;
import de.jexcellence.economy.database.entity.Currency;
import de.jexcellence.economy.format.CurrencyFormat;
import de.jexcellence.economy.money.Money;
import de.jexcellence.economy.service.EconomyService;
import de.jexcellence.economy.service.WalletService;
import de.jexcellence.economy.service.ledger.BalanceLedger;
import de.jexcellence.jexplatform.logging.JExLogger;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Bridges Vault's synchronous single-currency API to {@link EconomyService}'s asynchronous multi-currency backend.
 *
 * <p>Vault requires an immediate answer, so calls wait for the backend through a {@link VaultCallGate}: the wait is
 * limited, and server threads are refused for a moment after a timeout. A deposit or withdrawal that outlives its
 * wait is reported as failed and taken back as soon as it finishes, so the caller's view stays true. A change
 * requested on a server thread does not wait for the listeners of the balance pre-event, because that wait would
 * cost a server tick per call; those listeners cannot refuse it, the post-event still fires. Balance reads of online
 * players come from memory, see {@link de.jexcellence.economy.service.OnlineBalances}, and do not wait at all.
 *
 * <p>The provider is registered during the server's enable phase, so a Vault plugin that looks it up in its own
 * {@code onEnable} finds it. Until the database is ready it reports itself disabled and refuses bookings.
 *
 * <p>Vault sees one currency, {@code vault.currency} in {@code currencies.yml}. When that currency does not exist the
 * provider reports itself disabled; it never falls back to another currency, which could be a premium one.
 *
 * <p>The error texts of the {@link EconomyResponse} objects stay English: Vault consumers log or compare them, and
 * Vault has no locale. Failures of the economy backend are logged here with their cause.
 *
 * @author JExcellence
 * @since 3.0.0
 */
public final class VaultProvider implements Economy {

    static final String PROVIDER_NAME = "JExEconomy";
    static final String ERROR_BANKS_NOT_SUPPORTED = "Banks not supported";
    static final String ERROR_PLAYER_NOT_FOUND = "Player not found";
    static final String ERROR_NEGATIVE_WITHDRAW = "Cannot withdraw negative amount";
    static final String ERROR_NEGATIVE_DEPOSIT = "Cannot deposit negative amount";
    static final String ERROR_INVALID_AMOUNT = "Amount is not a number";
    static final String ERROR_NO_CURRENCY = "No currency available";
    static final String ERROR_BUSY = "Economy storage is busy, try again";
    static final String ERROR_INTERNAL = "Internal error: ";
    static final String FALLBACK_SINGULAR = "currency";
    static final String FALLBACK_PLURAL = "currencies";
    private static final String PLURAL_SUFFIX = "s";
    private static final String FALLBACK_FORMAT = "%.2f";
    private static final String UNDO_REASON = "Vault call timed out";
    private static final int FRACTIONAL_DIGITS = 2;

    private static final EconomyResponse BANK_NOT_SUPPORTED =
            new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, ERROR_BANKS_NOT_SUPPORTED);

    private final @NotNull Supplier<@Nullable EconomyService> services;
    private final @NotNull JExLogger logger;
    private final @NotNull VaultCallGate gate;

    /**
     * Creates a new Vault provider backed by the given economy service.
     *
     * @param services the JExEconomy service layer, {@code null} until the database is ready
     * @param logger   logs failures of the economy backend
     */
    public VaultProvider(@NotNull Supplier<@Nullable EconomyService> services, @NotNull JExLogger logger) {
        this(services, logger,
                new VaultCallGate(VaultCallGate.Threads.ofServer(), VaultCallGate.Timing.DEFAULTS, logger));
    }

    VaultProvider(@NotNull Supplier<@Nullable EconomyService> services, @NotNull JExLogger logger,
                  @NotNull VaultCallGate gate) {
        this.services = services;
        this.logger = logger;
        this.gate = gate;
    }

    /**
     * Registers this provider with Bukkit's {@link org.bukkit.plugin.ServicesManager}
     * at {@link ServicePriority#Highest}.
     *
     * @param plugin the owning plugin instance
     */
    public void register(@NotNull JavaPlugin plugin) {
        Bukkit.getServicesManager().register(Economy.class, this, plugin, ServicePriority.Highest);
    }

    @Override
    public boolean isEnabled() {
        return getDefaultCurrency() != null;
    }

    @Override
    public @NotNull String getName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return FRACTIONAL_DIGITS;
    }

    @Override
    public @NotNull String format(double amount) {
        var currency = getDefaultCurrency();
        if (currency == null) {
            return String.format(Locale.ROOT, FALLBACK_FORMAT, amount);
        }
        return CurrencyFormat.plain(currency, amount, economy().isWholeNumbers(currency));
    }

    @Override
    public @NotNull String currencyNameSingular() {
        var currency = getDefaultCurrency();
        return currency != null ? currency.getIdentifier() : FALLBACK_SINGULAR;
    }

    @Override
    public @NotNull String currencyNamePlural() {
        var currency = getDefaultCurrency();
        return currency != null ? currency.getIdentifier() + PLURAL_SUFFIX : FALLBACK_PLURAL;
    }

    @Override
    public boolean hasAccount(@NotNull String playerName) {
        var player = resolveOffline(playerName);
        return player != null && hasAccount(player);
    }

    @Override
    public boolean hasAccount(@NotNull OfflinePlayer player) {
        var currency = getDefaultCurrency();
        if (currency == null) {
            return false;
        }
        if (economy().cachedBalance(player, currency).isPresent()) {
            return true;
        }
        return read("account lookup", player,
                () -> economy().getAccount(player, currency).thenApply(Optional::isPresent), false);
    }

    @Override
    public boolean hasAccount(@NotNull String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(@NotNull OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public double getBalance(@NotNull String playerName) {
        var player = resolveOffline(playerName);
        return player != null ? getBalance(player) : 0.0;
    }

    @Override
    public double getBalance(@NotNull OfflinePlayer player) {
        var currency = getDefaultCurrency();
        if (currency == null) {
            return 0.0;
        }
        var cached = economy().cachedBalance(player, currency);
        if (cached.isPresent()) {
            return cached.getAsDouble();
        }
        return read("balance lookup", player, () -> economy().getBalance(player, currency), 0.0);
    }

    @Override
    public double getBalance(@NotNull String playerName, String worldName) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(@NotNull OfflinePlayer player, String worldName) {
        return getBalance(player);
    }

    @Override
    public boolean has(@NotNull String playerName, double amount) {
        return getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(@NotNull OfflinePlayer player, double amount) {
        return getBalance(player) >= amount;
    }

    @Override
    public boolean has(@NotNull String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(@NotNull OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public @NotNull EconomyResponse withdrawPlayer(@NotNull String playerName, double amount) {
        var player = resolveOffline(playerName);
        if (player == null) {
            return failure(ERROR_PLAYER_NOT_FOUND);
        }
        return withdrawPlayer(player, amount);
    }

    @Override
    public @NotNull EconomyResponse withdrawPlayer(@NotNull OfflinePlayer player, double amount) {
        var early = earlyAnswer(player, amount, ERROR_NEGATIVE_WITHDRAW);
        if (early != null) {
            return early;
        }
        var currency = getDefaultCurrency();
        if (currency == null) {
            return failure(ERROR_NO_CURRENCY);
        }
        return settle(new Booking<>("withdraw", player, currency,
                economy().withdrawForWaitingCaller(player, currency, amount, gate.onServerThread()),
                Function.identity(),
                done -> economy().creditWallet(player, currency, done.amount(), UNDO_REASON)
                        .thenApply(TransactionResult::isSuccess)));
    }

    @Override
    public @NotNull EconomyResponse withdrawPlayer(@NotNull String playerName,
                                                    String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public @NotNull EconomyResponse withdrawPlayer(@NotNull OfflinePlayer player,
                                                    String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public @NotNull EconomyResponse depositPlayer(@NotNull String playerName, double amount) {
        var player = resolveOffline(playerName);
        if (player == null) {
            return failure(ERROR_PLAYER_NOT_FOUND);
        }
        return depositPlayer(player, amount);
    }

    @Override
    public @NotNull EconomyResponse depositPlayer(@NotNull OfflinePlayer player, double amount) {
        var early = earlyAnswer(player, amount, ERROR_NEGATIVE_DEPOSIT);
        if (early != null) {
            return early;
        }
        var currency = getDefaultCurrency();
        if (currency == null) {
            return failure(ERROR_NO_CURRENCY);
        }
        return settle(new Booking<>("deposit", player, currency,
                economy().depositForWaitingCaller(player, currency, amount, gate.onServerThread()),
                WalletService.Receipt::result,
                done -> economy().reverse(player, currency.getIdentifier(), done, UNDO_REASON)));
    }

    @Override
    public @NotNull EconomyResponse depositPlayer(@NotNull String playerName,
                                                   String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public @NotNull EconomyResponse depositPlayer(@NotNull OfflinePlayer player,
                                                   String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public boolean createPlayerAccount(@NotNull String playerName) {
        var player = resolveOffline(playerName);
        return player != null && createPlayerAccount(player);
    }

    @Override
    public boolean createPlayerAccount(@NotNull OfflinePlayer player) {
        var currency = getDefaultCurrency();
        if (currency == null) {
            return false;
        }
        return read("account creation", player,
                () -> economy().getOrCreateAccount(player.getUniqueId(), currency).thenApply(account -> true),
                false);
    }

    @Override
    public boolean createPlayerAccount(@NotNull String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(@NotNull OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    @Override
    public @NotNull EconomyResponse createBank(String name, String player) {
        return BANK_NOT_SUPPORTED;
    }

    @Override
    public @NotNull EconomyResponse createBank(String name, OfflinePlayer player) {
        return BANK_NOT_SUPPORTED;
    }

    @Override
    public @NotNull EconomyResponse deleteBank(String name) {
        return BANK_NOT_SUPPORTED;
    }

    @Override
    public @NotNull EconomyResponse bankBalance(String name) {
        return BANK_NOT_SUPPORTED;
    }

    @Override
    public @NotNull EconomyResponse bankHas(String name, double amount) {
        return BANK_NOT_SUPPORTED;
    }

    @Override
    public @NotNull EconomyResponse bankWithdraw(String name, double amount) {
        return BANK_NOT_SUPPORTED;
    }

    @Override
    public @NotNull EconomyResponse bankDeposit(String name, double amount) {
        return BANK_NOT_SUPPORTED;
    }

    @Override
    public @NotNull EconomyResponse isBankOwner(String name, String playerName) {
        return BANK_NOT_SUPPORTED;
    }

    @Override
    public @NotNull EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return BANK_NOT_SUPPORTED;
    }

    @Override
    public @NotNull EconomyResponse isBankMember(String name, String playerName) {
        return BANK_NOT_SUPPORTED;
    }

    @Override
    public @NotNull EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return BANK_NOT_SUPPORTED;
    }

    @Override
    public @NotNull List<String> getBanks() {
        return List.of();
    }

    private @Nullable Currency getDefaultCurrency() {
        EconomyService service = services.get();
        return service != null ? service.vaultCurrency().orElse(null) : null;
    }

    /** The service layer; only reached after {@link #getDefaultCurrency()} returned a currency. */
    private @NotNull EconomyService economy() {
        return services.get();
    }

    /**
     * Answers calls that need no booking: invalid amounts, a busy storage and amounts that round to nothing, which
     * Vault plugins treat as a successful no-op.
     *
     * @return the response, or {@code null} when the amount has to be booked
     */
    private @Nullable EconomyResponse earlyAnswer(@NotNull OfflinePlayer player, double amount,
                                                  @NotNull String negativeError) {
        if (Double.isNaN(amount) || Double.isInfinite(amount)) {
            return failure(ERROR_INVALID_AMOUNT);
        }
        if (amount < 0) {
            return failure(negativeError);
        }
        if (gate.refusesCaller()) {
            return failure(ERROR_BUSY);
        }
        if (Money.of(amount).signum() == 0) {
            return new EconomyResponse(0, getBalance(player), ResponseType.SUCCESS, null);
        }
        return null;
    }

    private <T> T read(@NotNull String operation, @NotNull OfflinePlayer player,
                       @NotNull Supplier<CompletableFuture<T>> query, @NotNull T fallback) {
        if (gate.refusesCaller()) {
            return fallback;
        }
        try {
            return gate.await(query.get());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            logFailure(operation, player, interrupted);
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            logFailure(operation, player, failure);
        }
        return fallback;
    }

    private <R> @NotNull EconomyResponse settle(@NotNull Booking<R> booking) {
        try (var ignored = gate.onDispatchThread()
                ? economy().parkDispatchThread(booking.player(), booking.currency()) : BalanceLedger.Parking.NONE) {
            return toVaultResponse(booking.result().apply(gate.await(booking.running())));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return abandon(booking, interrupted);
        } catch (TimeoutException timedOut) {
            return abandon(booking, timedOut);
        } catch (ExecutionException | RuntimeException failure) {
            logFailure(booking.operation(), booking.player(), failure);
            return failure(ERROR_INTERNAL + failure.getMessage());
        }
    }

    /**
     * Gives up waiting. The caller is told the change failed, so a change that still goes through is taken back
     * when it finishes.
     */
    private <R> @NotNull EconomyResponse abandon(@NotNull Booking<R> booking, @NotNull Exception reason) {
        logFailure(booking.operation(), booking.player(), reason);
        booking.running()
                .thenCompose(done -> booking.result().apply(done).isSuccess()
                        ? booking.undo().apply(done)
                        : CompletableFuture.completedFuture(true))
                .whenComplete((undone, failure) -> {
                    if (failure != null || !Boolean.TRUE.equals(undone)) {
                        logger.error("Vault {} for {} finished after its caller gave up and could not be taken back."
                                        + " Check the balance by hand.", booking.operation(),
                                booking.player().getUniqueId());
                    }
                });
        return failure(ERROR_BUSY);
    }

    private void logFailure(@NotNull String operation, @NotNull OfflinePlayer player, @NotNull Exception failure) {
        logger.warn("Vault {} failed for {}: {}", operation, player.getUniqueId(), String.valueOf(failure.getMessage()));
    }

    private static @NotNull EconomyResponse failure(@NotNull String error) {
        return new EconomyResponse(0, 0, ResponseType.FAILURE, error);
    }

    private static @NotNull EconomyResponse toVaultResponse(@NotNull TransactionResult result) {
        if (result.isSuccess()) {
            return new EconomyResponse(result.amount(), result.balance(), ResponseType.SUCCESS, null);
        }
        return new EconomyResponse(0, result.balance(), ResponseType.FAILURE, result.error());
    }

    /**
     * Resolves a player name from memory, see {@link OfflinePlayerLookup}; it never asks Mojang, which would stall
     * the calling server thread.
     *
     * @param playerName the player name
     * @return the player, or {@code null} when the server does not know the name
     */
    private static @Nullable OfflinePlayer resolveOffline(@Nullable String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            return null;
        }
        return OfflinePlayerLookup.byName(playerName);
    }

    /**
     * A change the caller waits for.
     *
     * @param operation the name used in log lines
     * @param player    the account owner
     * @param currency  the Vault currency
     * @param running   the running change
     * @param result    reads the outcome from the finished change
     * @param undo      takes a finished, successful change back; resolves to {@code true} when it worked
     * @param <R>       what the change produces
     */
    private record Booking<R>(@NotNull String operation, @NotNull OfflinePlayer player, @NotNull Currency currency,
                              @NotNull CompletableFuture<R> running, @NotNull Function<R, TransactionResult> result,
                              @NotNull Function<R, CompletableFuture<Boolean>> undo) {
    }
}
