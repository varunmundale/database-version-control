package org.example.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyConfigTest {

    @Test
    void theShippedConfigurationLoadsAndIsInternallyConsistent() {
        ConcurrencyConfig concurrency = ConcurrencyConfig.getInstance();
        PoolConfig pools = PoolConfig.getInstance();

        assertTrue(concurrency.handlerThreads() >= 1);
        assertTrue(pools.metadata().maxSize() >= 2 * concurrency.handlerThreads(),
                "the shipped dbgit.json must satisfy the rule it is validated against");
    }

    @Test
    void metadataPoolSizeDefaultsToTwiceTheThreadCount() {
        // dbgit.json deliberately leaves pools.metadata.maxSize unset, so the derived default is what runs.
        assertEquals(2 * ConcurrencyConfig.getInstance().handlerThreads(),
                PoolConfig.getInstance().metadata().maxSize());
    }

    /**
     * The failure this refuses to boot into is a deadlock, not a slowdown: a thread holding a branch lock queues
     * for a connection held by a thread waiting for that same lock.
     */
    @Test
    void anExplicitMetadataPoolSmallerThanTwoPerThreadIsRefusedNamingBothNumbers() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> new PoolConfig(
                new PoolConfig.MetadataPool(8, 5000),
                new PoolConfig.BranchPool(4, 30000, 32),
                8));

        assertTrue(exception.getMessage().contains("(8)"), exception.getMessage());
        assertTrue(exception.getMessage().contains("= 16"), exception.getMessage());
        assertTrue(exception.getMessage().contains("deadlock"), exception.getMessage());
    }

    @Test
    void exactlyTwoConnectionsPerThreadIsAccepted() {
        PoolConfig pools = new PoolConfig(new PoolConfig.MetadataPool(16, 5000),
                new PoolConfig.BranchPool(4, 30000, 32), 8);

        assertEquals(16, pools.metadata().maxSize());
        assertEquals(32, pools.branch().maxPools());
    }

    @Test
    void nonPositiveSettingsAreRefusedByName() {
        assertTrue(assertThrows(IllegalStateException.class,
                () -> new ConcurrencyConfig(0, 64, 30000, 30000, 60000))
                .getMessage().contains("concurrency.handlerThreads"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> new ConcurrencyConfig(8, 64, 30000, 30000, 0))
                .getMessage().contains("concurrency.lockTimeoutMs"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> new PoolConfig(new PoolConfig.MetadataPool(16, 5000),
                        new PoolConfig.BranchPool(4, 30000, 0), 8))
                .getMessage().contains("pools.branch.maxPools"));
    }
}
