package org.example.diff;

/**
 * Which side of a comparison a value belongs to - or, for a value present on both sides, that it conflicts.
 * {@link #of} is the one place that turns "is the left value present, is the right value present" into this
 * classification, shared by every level of the diff ({@link TableDiff}, {@link ColumnDiff}) instead of each
 * re-deriving it from its own null checks.
 */
public enum Side {
    LEFT, RIGHT, CONFLICT;

    /**
     * Classifies a pairing by which of its two nullable sides is present.
     *
     * @throws IllegalArgumentException if both sides are absent - a diff must always be anchored to at least one
     */
    public static <T> Side of(T left, T right) {
        if (left == null && right == null) {
            throw new IllegalArgumentException("at least one side must be present");
        }
        if (right == null) {
            return LEFT;
        }
        if (left == null) {
            return RIGHT;
        }
        return CONFLICT;
    }
}
