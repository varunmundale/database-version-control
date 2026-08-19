package org.example.adapters;

import java.util.Objects;

public record ColumnModel(StableId id, String name, String nativeType, int ordinal, boolean nullable,
                          String defaultValue) {
    public ColumnModel {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(nativeType, "nativeType must not be null");
    }
}
