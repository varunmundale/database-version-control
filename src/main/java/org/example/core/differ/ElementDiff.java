package org.example.core.differ;

import org.example.models.schema.SchemaElement;
import org.example.models.schema.StableId;

/** A {@link Sided} pairing of one table member matched by {@link StableId} - what {@link ColumnDiff},
 * {@link ConstraintDiff} and {@link IndexDiff} all are, so {@link SideChanges} can judge all three the same way. */
interface ElementDiff<S extends SchemaElement<S>> extends Sided<S> {
    StableId id();
}
