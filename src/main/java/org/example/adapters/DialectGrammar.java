package org.example.adapters;

import net.sf.jsqlparser.statement.alter.AlterOperation;

import java.util.List;
import java.util.Objects;

/**
 * One SQL dialect's DDL vocabulary - how it spells a column retype, and what its identity/auto-increment specs
 * look like. Everything else is identical across dialects and lives in {@link SqlDdlParser}, which this
 * configures rather than being subclassed per dialect.
 */
public record DialectGrammar(String name, AlterOperation retypeOperation, String retypeSyntax, List<List<String>> identitySpecs) {
    public DialectGrammar {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(retypeOperation, "retypeOperation must not be null");
        Objects.requireNonNull(retypeSyntax, "retypeSyntax must not be null");
        Objects.requireNonNull(identitySpecs, "identitySpecs must not be null");
    }

    /** Postgres: retypes are {@code ALTER COLUMN ... TYPE}; identity is {@code GENERATED ALWAYS/BY DEFAULT AS IDENTITY}. */
    public static DialectGrammar postgresql() {
        return new DialectGrammar("postgresql", AlterOperation.ALTER, "ALTER TABLE ALTER COLUMN ... TYPE",
                List.of(List.of("GENERATED", "ALWAYS", "AS", "IDENTITY"),
                        List.of("GENERATED", "BY", "DEFAULT", "AS", "IDENTITY")));
    }

    /** MySQL: retypes are {@code ALTER TABLE t MODIFY COLUMN c x}; identity is {@code AUTO_INCREMENT}. */
    public static DialectGrammar mysql() {
        return new DialectGrammar("mysql", AlterOperation.MODIFY, "ALTER TABLE MODIFY COLUMN ...",
                List.of(List.of("AUTO_INCREMENT")));
    }

    /** H2: retypes the same way Postgres spells them, and recognizes both Postgres's and MySQL's identity spellings. */
    public static DialectGrammar h2() {
        return new DialectGrammar("h2", AlterOperation.ALTER, "ALTER TABLE ALTER COLUMN ... TYPE",
                List.of(List.of("GENERATED", "ALWAYS", "AS", "IDENTITY"),
                        List.of("GENERATED", "BY", "DEFAULT", "AS", "IDENTITY"),
                        List.of("AUTO_INCREMENT")));
    }

    /** Whether {@code operation} is this dialect's spelling of a column retype. */
    boolean isRetypeOperation(AlterOperation operation) {
        return operation == retypeOperation;
    }
}
