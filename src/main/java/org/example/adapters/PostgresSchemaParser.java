package org.example.adapters;

public final class PostgresSchemaParser extends AbstractJdbcSchemaParser {
    @Override
    protected String dialect() {
        return "postgresql";
    }
}
