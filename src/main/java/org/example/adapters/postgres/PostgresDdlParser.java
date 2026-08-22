package org.example.adapters.postgres;

import net.sf.jsqlparser.statement.alter.AlterOperation;
import org.example.adapters.SqlDdlParser;

/**
 * PostgreSQL's DDL grammar: retypes are spelled {@code ALTER TABLE t ALTER COLUMN c TYPE x}. Everything else is
 * {@link SqlDdlParser}'s shared logic - see it for what is actually understood.
 */
public final class PostgresDdlParser extends SqlDdlParser {
    @Override
    protected boolean isRetypeOperation(AlterOperation operation) {
        return operation == AlterOperation.ALTER;
    }

    @Override
    protected String retypeSyntax() {
        return "ALTER TABLE ALTER COLUMN ... TYPE";
    }
}
