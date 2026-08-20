package org.example.connector.postgres;

import org.example.connector.AbstractJdbcSchemaParser;

public final class PostgresSchemaParser extends AbstractJdbcSchemaParser {
    @Override
    protected String dialect() {
        return "postgresql";
    }
}
