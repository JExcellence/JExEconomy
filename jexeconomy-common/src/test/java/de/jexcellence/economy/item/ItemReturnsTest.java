package de.jexcellence.economy.item;

import de.jexcellence.economy.database.entity.ItemReturn;
import de.jexcellence.economy.database.repository.ItemReturnRepository;
import de.jexcellence.jexplatform.logging.JExLogger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A stored item reaches its owner exactly once, and goes back to storage when the owner leaves again.
 */
class ItemReturnsTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final byte[] DATA = {1, 2, 3};

    private final ItemReturnRepository repository = mock(ItemReturnRepository.class);
    private final AtomicBoolean playerLeft = new AtomicBoolean();
    private final Player player = mock(Player.class);
    private final ItemStack item = mock(ItemStack.class);
    private final List<ItemStack> given = new ArrayList<>();
    private final ItemReturn row = spy(new ItemReturn(PLAYER, DATA));
    private final ItemReturns.PlayerThread playerThread = (target, task, retired) -> {
        if (playerLeft.get()) {
            retired.run();
        } else {
            task.run();
        }
    };
    private final ItemReturns returns = new ItemReturns(repository, JExLogger.of("ItemReturnsTest"),
            new ItemReturns.Handover(stack -> DATA, bytes -> item, (receiver, stack) -> given.add(stack), playerThread));

    @BeforeEach
    void wire() {
        when(player.getUniqueId()).thenReturn(PLAYER);
        when(row.getId()).thenReturn(5L);
        when(repository.findByPlayerAsync(PLAYER)).thenReturn(CompletableFuture.completedFuture(List.of(row)));
        when(repository.claimAsync(5L)).thenReturn(CompletableFuture.completedFuture(true));
        when(repository.createAsync(any())).thenAnswer(call -> CompletableFuture.completedFuture(call.getArgument(0)));
    }

    @Test
    void anItemIsStoredForItsOwner() {
        returns.store(PLAYER, item);

        ArgumentCaptor<ItemReturn> stored = ArgumentCaptor.forClass(ItemReturn.class);
        verify(repository).createAsync(stored.capture());
        assertEquals(PLAYER, stored.getValue().getPlayerUuid());
        assertArrayEquals(DATA, stored.getValue().getItemData());
    }

    @Test
    void aWaitingItemIsHandedOutAfterItsRowIsClaimed() {
        returns.deliver(player);

        verify(repository).claimAsync(5L);
        assertEquals(List.of(item), given);
    }

    @Test
    void aRowAnotherServerClaimedIsNotHandedOut() {
        when(repository.claimAsync(5L)).thenReturn(CompletableFuture.completedFuture(false));

        returns.deliver(player);

        assertTrue(given.isEmpty());
    }

    @Test
    void anOwnerWhoLeftAgainGetsTheItemStoredAgain() {
        playerLeft.set(true);

        returns.deliver(player);

        assertTrue(given.isEmpty());
        verify(repository).createAsync(any(ItemReturn.class));
    }

    @Test
    void aFailedClaimHandsNothingOut() {
        when(repository.claimAsync(5L)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));

        returns.deliver(player);

        assertTrue(given.isEmpty());
        verify(repository, never()).createAsync(any());
    }

    @Test
    void aRejectedHandOverStoresTheClaimedItemAgain() {
        var rejecting = new ItemReturns(repository, JExLogger.of("ItemReturnsTest"),
                new ItemReturns.Handover(stack -> DATA, bytes -> item, (receiver, stack) -> given.add(stack),
                        (target, task, retired) -> {
                            throw new IllegalStateException("plugin disabled");
                        }));

        rejecting.deliver(player);

        assertTrue(given.isEmpty());
        verify(repository).createAsync(any(ItemReturn.class));
    }

    @Test
    void anUnreadableItemStaysStored() {
        var unreadable = new ItemReturns(repository, JExLogger.of("ItemReturnsTest"),
                new ItemReturns.Handover(stack -> DATA, bytes -> {
                    throw new IllegalArgumentException("newer data version");
                }, (receiver, stack) -> given.add(stack), playerThread));

        unreadable.deliver(player);

        verify(repository, never()).claimAsync(anyLong());
        assertTrue(given.isEmpty());
    }
}
