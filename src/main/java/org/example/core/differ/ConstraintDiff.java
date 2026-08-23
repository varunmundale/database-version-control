package org.example.core.differ;

import org.example.models.schema.ConstraintModel;
import org.example.models.schema.StableId;

/**
 * One constraint, matched by stable id, that differs between two sides - see {@link #side()}.
 * {@link Side#BOTH} means both branches have a constraint of this name but defined differently, e.g. a
 * {@code UNIQUE} over different columns on each side - a conflict only if both of them changed it
 * ({@link SchemaConflicts}).
 */
public record ConstraintDiff(StableId id, ConstraintModel left, ConstraintModel right) implements ElementDiff<ConstraintModel> {
    public ConstraintDiff {
        Side.of(left, right); // validates at least one side is present
    }

    /** The name to sort and label this diff by - whichever side has the constraint. */
    public String constraintName() {
        return left != null ? left.name() : right.name();
    }
}
