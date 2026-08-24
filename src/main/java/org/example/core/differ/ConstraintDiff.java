package org.example.core.differ;

import org.example.models.schema.ConstraintModel;
import org.example.models.schema.StableId;

/**
 * One constraint, matched by stable id, that differs between two sides. {@link Side#BOTH} means both branches
 * defined a constraint of this name differently - a conflict only if both actually changed it ({@link SideChanges}).
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
