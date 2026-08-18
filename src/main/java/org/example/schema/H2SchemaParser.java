package org.example.schema;

public final class H2SchemaParser extends AbstractJdbcSchemaParser {
    @Override
    protected String dialect() {
        return "h2";
    }
}
