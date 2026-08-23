package org.example.unit.adapters.spi;

import org.example.adapters.spi.DdlParserRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@link org.example.core.replayer.Replayer}'s default constructor looks up by {@code branchDatabases.dialect}. */
class DdlParserRegistryTest {
    private final DdlParserRegistry registry = DdlParserRegistry.builtins();

    @Test
    void resolvesEachBuiltinDialectToAParserConfiguredForItsOwnGrammar() {
        assertDoesNotThrow(() -> registry.get("postgresql").parse("ALTER TABLE orders ALTER COLUMN total TYPE BIGINT"));
        assertDoesNotThrow(() -> registry.get("h2").parse("ALTER TABLE orders ALTER COLUMN total TYPE BIGINT"));
        assertDoesNotThrow(() -> registry.get("mysql").parse("ALTER TABLE orders MODIFY COLUMN total BIGINT"));

        assertThrows(IllegalArgumentException.class,
                () -> registry.get("mysql").parse("ALTER TABLE orders ALTER COLUMN total TYPE BIGINT"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.get("postgresql").parse("ALTER TABLE orders MODIFY COLUMN total BIGINT"));
    }

    @Test
    void rejectsAnUnregisteredDialect() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> registry.get("oracle"));

        assertTrue(exception.getMessage().contains("oracle"), exception.getMessage());
    }
}
