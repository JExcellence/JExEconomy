package de.jexcellence.economy.database.repository;

import de.jexcellence.jehibernate.repository.base.AbstractCrudRepository;
import jakarta.persistence.EntityManagerFactory;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Base for every JExEconomy repository: an asynchronous write that fails is logged with the entity type and the
 * cause, so a write whose future nobody inspects does not fail silently. The returned future keeps its outcome.
 * Custom queries run on {@link #async(Supplier)}, the repository executor, never on the common pool.
 *
 * @param <T> the entity
 * @author JExcellence
 * @since 3.1.0
 */
public abstract class LoggedCrudRepository<T> extends AbstractCrudRepository<T, Long> {

    private static final Logger LOGGER = Logger.getLogger(LoggedCrudRepository.class.getName());

    protected LoggedCrudRepository(@NotNull ExecutorService executor, @NotNull EntityManagerFactory emf,
                                   @NotNull Class<T> entityClass) {
        super(executor, emf, entityClass);
    }

    @Override
    public CompletableFuture<T> createAsync(T entity) {
        return logged(super.createAsync(entity), () -> "create " + entityName());
    }

    @Override
    public CompletableFuture<List<T>> createAllAsync(Collection<T> entities) {
        return logged(super.createAllAsync(entities), () -> "create " + entities.size() + " " + entityName());
    }

    @Override
    public CompletableFuture<T> updateAsync(T entity) {
        return logged(super.updateAsync(entity), () -> "update " + entityName());
    }

    @Override
    public CompletableFuture<List<T>> saveAllAsync(Collection<T> entities) {
        return logged(super.saveAllAsync(entities), () -> "save " + entities.size() + " " + entityName());
    }

    @Override
    public CompletableFuture<Void> deleteAsync(Long id) {
        return logged(super.deleteAsync(id), () -> "delete " + entityName() + " #" + id);
    }

    @Override
    public CompletableFuture<Void> deleteEntityAsync(T entity) {
        return logged(super.deleteEntityAsync(entity), () -> "delete " + entityName());
    }

    /**
     * Runs blocking repository work on the repository executor.
     *
     * @param work the work
     * @param <R>  the result type
     * @return the future of the result
     */
    protected final <R> @NotNull CompletableFuture<R> async(@NotNull Supplier<R> work) {
        return CompletableFuture.supplyAsync(work, getExecutorService());
    }

    protected final @NotNull String entityName() {
        return getEntityClass().getSimpleName();
    }

    /**
     * Logs a failure of {@code write} as "Could not {action}" and returns a future with the same outcome.
     *
     * @param write  the write
     * @param action describes the write for the log line
     * @param <R>    the result type
     * @return a future with the outcome of {@code write}
     */
    protected static <R> @NotNull CompletableFuture<R> logged(@NotNull CompletableFuture<R> write,
                                                              @NotNull Supplier<String> action) {
        return write.whenComplete((ignored, failure) -> {
            if (failure != null) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause() : failure;
                LOGGER.log(Level.WARNING, cause, () -> "Could not " + action.get() + ": " + cause.getMessage());
            }
        });
    }
}
