package org.example.core.differ;

import org.example.models.schema.SchemaElement;
import org.example.models.schema.StableId;

/**
 * A {@link Sided} pairing of one table member matched by {@link StableId} - what {@link ColumnDiff},
 * {@link ConstraintDiff} and {@link IndexDiff} all are. Naming the shape is what lets {@link SideChanges}
 * judge all three the same way instead of three times over.
 */
interface ElementDiff<S extends SchemaElement<S>> extends Sided<S> {
    StableId id();
}
