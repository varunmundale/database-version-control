package org.example.ddl;

import java.util.Objects;

/** A column as a vendor's DDL grammar described it, before it is assigned a stable id and folded into the model. */
public record ColumnDefinition(String name, String nativeType, boolean nullable, String defaultValue) {
    public ColumnDefinition {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(nativeType, "nativeType must not be null");
    }
}
