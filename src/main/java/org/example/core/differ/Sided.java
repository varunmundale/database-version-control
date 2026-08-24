package org.example.core.differ;

/** A pairing with a nullable left and right, shared by {@link ColumnDiff}, {@link ConstraintDiff}, {@link IndexDiff}
 * and {@link TableDiff} so they can all derive {@link #side()} the same way. */
interface Sided<T> {
    T left();

    T right();

    default Side side() {
        return Side.of(left(), right());
    }
}
