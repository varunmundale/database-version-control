package org.example.core.differ;

/**
 * Which side of a comparison a value is on - one side only, or both. {@link #BOTH} is deliberately not called a
 * conflict: whether two branches actually disagree depends on what the object looked like before they diverged,
 * which is {@link SideChanges}' question to answer, not this one.
 */
public enum Side {
    LEFT, RIGHT, BOTH;

    /** @throws IllegalArgumentException if both sides are absent */
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
