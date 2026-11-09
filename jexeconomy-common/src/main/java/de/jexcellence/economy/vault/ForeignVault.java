package de.jexcellence.economy.vault;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lets JExEconomy work next to a separately installed Vault or VaultUnlocked jar. Such a jar brings its own
 * {@code Economy} class, and a Vault plugin may look up either copy, so the provider also has to be registered under
 * the jar's class. An adapter built at runtime forwards that class's calls to the {@link VaultProvider}.
 *
 * @author JExcellence
 * @since 3.2.0
 */
public final class ForeignVault {

    private static final Set<String> VAULT_PLUGINS = Set.of("Vault", "VaultUnlocked");
    private static final String RESPONSE_CLASS = EconomyResponse.class.getName();
    private static final String RESPONSE_TYPE_CLASS = EconomyResponse.ResponseType.class.getName();

    private ForeignVault() {
    }

    /**
     * A separately installed Vault jar.
     *
     * @param pluginName   the plugin's name
     * @param economyClass the {@code Economy} class that plugin's class loader resolves
     */
    public record Found(@NotNull String pluginName, @NotNull Class<?> economyClass) {

        /**
         * Whether the jar's {@code Economy} class differs from the one bundled in JExEconomy.
         *
         * @return {@code true} when the provider needs an adapter for that class
         */
        public boolean separateApi() {
            return economyClass != Economy.class;
        }
    }

    /**
     * Finds a separately installed Vault jar.
     *
     * @param self    this plugin, which also answers to the name Vault
     * @param plugins the server's plugins
     * @return the jar, empty when there is none
     */
    public static @NotNull Optional<Found> find(@NotNull Plugin self, @NotNull Plugin @NotNull [] plugins) {
        return Arrays.stream(plugins)
                .filter(candidate -> candidate != self && VAULT_PLUGINS.contains(candidate.getName()))
                .findFirst()
                .map(vault -> new Found(vault.getName(), economyClassOf(vault.getClass().getClassLoader())));
    }

    private static @NotNull Class<?> economyClassOf(@NotNull ClassLoader loader) {
        try {
            return Class.forName(Economy.class.getName(), false, loader);
        } catch (ClassNotFoundException noOwnApi) {
            return Economy.class;
        }
    }

    /**
     * Builds an object of the jar's {@code Economy} class that forwards to the provider.
     *
     * @param foreignEconomy the jar's {@code Economy} class
     * @param provider       the provider
     * @return the adapter
     * @throws IllegalStateException when the jar's Vault API does not have the expected response classes
     */
    public static @NotNull Object adapt(@NotNull Class<?> foreignEconomy, @NotNull Economy provider) {
        ClassLoader loader = foreignEconomy.getClassLoader();
        return Proxy.newProxyInstance(loader, new Class<?>[]{foreignEconomy},
                new Adapter(provider, responseFactory(loader)));
    }

    private static @NotNull Function<EconomyResponse, Object> responseFactory(@NotNull ClassLoader loader) {
        try {
            Class<?> responseType = Class.forName(RESPONSE_TYPE_CLASS, true, loader);
            Constructor<?> constructor = Class.forName(RESPONSE_CLASS, true, loader)
                    .getConstructor(double.class, double.class, responseType, String.class);
            Map<String, Object> types = Arrays.stream(responseType.getEnumConstants())
                    .collect(Collectors.toMap(String::valueOf, Function.identity()));
            return response -> newResponse(constructor, types, response);
        } catch (ReflectiveOperationException incompatible) {
            throw new IllegalStateException("Unexpected Vault API in the installed jar", incompatible);
        }
    }

    private static @NotNull Object newResponse(@NotNull Constructor<?> constructor, @NotNull Map<String, Object> types,
                                               @NotNull EconomyResponse response) {
        try {
            return constructor.newInstance(response.amount, response.balance, types.get(response.type.name()),
                    response.errorMessage);
        } catch (ReflectiveOperationException incompatible) {
            throw new IllegalStateException("Could not build a Vault response", incompatible);
        }
    }

    /** Forwards each call to the provider's method of the same signature. */
    private record Adapter(@NotNull Economy provider, @NotNull Function<EconomyResponse, Object> responses)
            implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args);
            }
            Method target = targetOf(method);
            if (target == null) {
                return unknownMethod(proxy, method, args);
            }
            Object result = call(target, args);
            return result instanceof EconomyResponse response ? responses.apply(response) : result;
        }

        private static @Nullable Method targetOf(@NotNull Method method) {
            try {
                return Economy.class.getMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException newerApi) {
                return null;
            }
        }

        private @Nullable Object call(@NotNull Method target, @Nullable Object[] args) throws Throwable {
            try {
                return target.invoke(provider, args);
            } catch (InvocationTargetException failed) {
                throw failed.getCause();
            }
        }

        private static @Nullable Object unknownMethod(@NotNull Object proxy, @NotNull Method method,
                                                      @Nullable Object[] args) throws Throwable {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            throw new UnsupportedOperationException("Not supported by JExEconomy: " + method.getName());
        }

        private static @NotNull Object objectMethod(@NotNull Object proxy, @NotNull Method method,
                                                    @Nullable Object[] args) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> "JExEconomy Vault adapter";
            };
        }
    }
}
