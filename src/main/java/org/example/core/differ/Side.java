package org.example.core.differ;

/**
 * Which side of a comparison a value is on - one side only, or both. {@link #of} is the one place that turns "is
 * the left value present, is the right value present" into this classification, shared by every level of the diff
 * ({@link TableDiff}, {@link ColumnDiff}) instead of each re-deriving it from its own null checks.
 *
 * <p>{@link #BOTH} is deliberately not called a conflict. Whether two branches genuinely disagree about an object
 * cannot be read off the two sides alone - it depends on what the object looked like before they diverged, which
 * is {@link SchemaConflicts}' question, answered on the whole {@link HistoryDiff} rather than one pairing.
 */
public enum Side {
    LEFT, RIGHT, BOTH;

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
        return BOTH;
    }
}
