package org.example.unit.adapters.spi;

import org.example.adapters.h2.H2DdlParser;
import org.example.adapters.mysql.MySqlDdlParser;
import org.example.adapters.postgres.PostgresDdlParser;
import org.example.adapters.spi.DdlParserRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What {@link org.example.core.replayer.Replayer}'s default constructor looks up by {@code branchDatabases.dialect}. */
class DdlParserRegistryTest {
    private final DdlParserRegistry registry = DdlParserRegistry.builtins();

    @Test
    void resolvesEachBuiltinDialectToItsOwnParser() {
        assertInstanceOf(PostgresDdlParser.class, registry.get("postgresql"));
        assertInstanceOf(MySqlDdlParser.class, registry.get("mysql"));
        assertInstanceOf(H2DdlParser.class, registry.get("h2"));
    }

    @Test
    void rejectsAnUnregisteredDialect() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> registry.get("oracle"));

        assertTrue(exception.getMessage().contains("oracle"), exception.getMessage());
    }
}
