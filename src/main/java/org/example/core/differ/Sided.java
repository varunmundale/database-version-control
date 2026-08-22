package org.example.core.differ;

/**
 * A pairing with a nullable left and right - the one thing {@link ColumnDiff}, {@link ConstraintDiff},
 * {@link IndexDiff} and {@link TableDiff} all are, which is what lets them share {@link #side()} rather than each
 * re-deriving it via {@link Side#of}.
 */
interface Sided<T> {
    T left();

    T right();

    default Side side() {
        return Side.of(left(), right());
    }
}
