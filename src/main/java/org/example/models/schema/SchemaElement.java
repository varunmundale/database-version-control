package org.example.models.schema;

/**
 * What a table's columns, constraints and indexes all are: something owned by a table, addressed by name in DDL,
 * identified by a {@link StableId} once the model has been built, and comparable against its own kind.
 *
 * <p>Naming this shape is what lets the two places that handle all three kinds handle them once rather than three
 * times: {@link org.example.core.replayer.SchemaOperationApplier} looks members up by name and rewrites them, and
 * {@link org.example.core.differ.TableDiff} pairs them up by id. Neither needs to know which kind it holds.
 *
 * @param <S> the implementing type itself, so {@link #differsFrom} compares a member only against its own kind
 */
public interface SchemaElement<S extends SchemaElement<S>> {
    /** Identity that survives replay, and a rename where the kind tracks one. */
    StableId id();

    /** What DDL calls this member. */
    String name();

    /** True when this member, matched by id with another, has something worth reporting. */
    boolean differsFrom(S other);
}
