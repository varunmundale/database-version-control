package org.example.adapters.h2;

import net.sf.jsqlparser.statement.alter.AlterOperation;
import org.example.adapters.SqlDdlParser;

/**
 * H2's DDL grammar, as dbgit uses it: retypes are spelled the same way Postgres's are,
 * {@code ALTER TABLE t ALTER COLUMN c TYPE x} - H2 accepts that syntax directly, unlike MySQL's
 * {@code MODIFY COLUMN}. Everything else is {@link SqlDdlParser}'s shared logic - see it for what is actually
 * understood.
 */
public final class H2DdlParser extends SqlDdlParser {
    @Override
    protected boolean isRetypeOperation(AlterOperation operation) {
        return operation == AlterOperation.ALTER;
    }

    @Override
    protected String retypeSyntax() {
        return "ALTER TABLE ALTER COLUMN ... TYPE";
    }
}
