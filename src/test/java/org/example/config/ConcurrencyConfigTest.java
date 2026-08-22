package org.example.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyConfigTest {

    @Test
    void theShippedConfigurationLoads() {
        ConcurrencyConfig concurrency = ConcurrencyConfig.getInstance();

        assertTrue(concurrency.handlerThreads() >= 1);
        assertTrue(concurrency.queueDepth() >= 1);
        assertTrue(concurrency.lockTimeoutMs() > 0);
    }

    @Test
    void everySettingIsAcceptedAtItsSmallestUsefulValue() {
        ConcurrencyConfig concurrency = new ConcurrencyConfig(1, 1, 1, 1, 1);

        assertEquals(1, concurrency.handlerThreads());
        assertEquals(1, concurrency.queueDepth());
        assertEquals(1, concurrency.drainTimeoutMs());
    }

    /** Nonsense is refused at start-up rather than turning into a hang or a dropped connection later. */
    @Test
    void nonPositiveSettingsAreRefusedByName() {
        assertTrue(assertThrows(IllegalStateException.class,
                () -> new ConcurrencyConfig(0, 64, 30000, 30000, 60000))
                .getMessage().contains("concurrency.handlerThreads"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> new ConcurrencyConfig(8, 0, 30000, 30000, 60000))
                .getMessage().contains("concurrency.queueDepth"));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> new ConcurrencyConfig(8, 64, 30000, 30000, 0))
                .getMessage().contains("concurrency.lockTimeoutMs"));
    }
}
