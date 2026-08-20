package org.example.connector.h2;

import org.example.connector.AbstractJdbcSchemaParser;

public final class H2SchemaParser extends AbstractJdbcSchemaParser {
    @Override
    protected String dialect() {
        return "h2";
    }
}
