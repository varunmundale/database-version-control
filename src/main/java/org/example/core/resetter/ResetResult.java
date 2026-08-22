package org.example.core.resetter;

/**
 * What a {@link Resetter} run did: where the branch now points, how much of its working set was thrown away, and
 * how much history was replayed to rebuild its database.
 *
 * @param droppedChangesets working changesets discarded - the whole working set, whatever state it was in
 * @param replayedChangesets committed changesets replayed into the freshly recreated database
 * @param tableCount        tables the branch's schema has once the truncated history is replayed
 */
public record ResetResult(String branch, long commitId, int droppedChangesets, int replayedChangesets, int tableCount) {
}
