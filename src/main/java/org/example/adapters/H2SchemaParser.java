package org.example.adapters;

public final class H2SchemaParser extends AbstractJdbcSchemaParser {
    @Override
    protected String dialect() {
        return "h2";
    }
}
