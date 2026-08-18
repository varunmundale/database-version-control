package org.example.schema;

public final class PostgresSchemaParser extends AbstractJdbcSchemaParser {
    @Override
    protected String dialect() {
        return "postgresql";
    }
}
