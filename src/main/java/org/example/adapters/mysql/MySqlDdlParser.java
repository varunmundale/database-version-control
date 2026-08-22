package org.example.adapters.mysql;

import net.sf.jsqlparser.statement.alter.AlterOperation;
import org.example.adapters.SqlDdlParser;

/**
 * MySQL's DDL grammar: retypes are spelled {@code ALTER TABLE t MODIFY COLUMN c x}, not Postgres's
 * {@code ALTER COLUMN ... TYPE}. Everything else is {@link SqlDdlParser}'s shared logic - see it for what is
 * actually understood.
 */
public final class MySqlDdlParser extends SqlDdlParser {
    @Override
    protected boolean isRetypeOperation(AlterOperation operation) {
        return operation == AlterOperation.MODIFY;
    }

    @Override
    protected String retypeSyntax() {
        return "ALTER TABLE MODIFY COLUMN ...";
    }
}
