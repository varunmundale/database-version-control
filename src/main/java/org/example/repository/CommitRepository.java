package org.example.repository;

import org.example.models.versioning.CommitParents;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.util.HashMap;
import java.util.Map;

/** The {@code branch_commits} table: the shared commit graph every branch's history is woven from. */
public final class CommitRepository {
    private static final CommitRepository INSTANCE = new CommitRepository();

    private static final Table<Record> TABLE = DSL.table("branch_commits");
    private static final Field<Long> ID = DSL.field("id", SQLDataType.BIGINT);
    private static final Field<Long> PARENT_COMMIT_ID = DSL.field("parent_commit_id", SQLDataType.BIGINT);
    private static final Field<Long> SECOND_PARENT_COMMIT_ID = DSL.field("second_parent_commit_id", SQLDataType.BIGINT);
    private static final Field<Long> NEXT_COMMIT_ID = DSL.field("next_commit_id", SQLDataType.BIGINT);

    private CommitRepository() {
    }

    public static CommitRepository getInstance() {
        return INSTANCE;
    }

    /** Inserts a commit with the given parent(s) - {@code secondParentCommitId} non-null only for a merge commit - and returns its generated id. */
    public long insert(Long parentCommitId, Long secondParentCommitId) {
        return dsl().insertInto(TABLE, PARENT_COMMIT_ID, SECOND_PARENT_COMMIT_ID)
                .values(parentCommitId, secondParentCommitId)
                .returning(ID)
                .fetchOne(ID);
    }

    public void updateNextCommitId(long commitId, long nextCommitId) {
        dsl().update(TABLE).set(NEXT_COMMIT_ID, nextCommitId).where(ID.eq(commitId)).execute();
    }

    /** Every commit's parents, keyed by id - the raw material a branch's history is walked out of. */
    public Map<Long, CommitParents> findAllParents() {
        Map<Long, CommitParents> parents = new HashMap<>();
        dsl().select(ID, PARENT_COMMIT_ID, SECOND_PARENT_COMMIT_ID).from(TABLE)
                .forEach(row -> parents.put(row.value1(), new CommitParents(row.value2(), row.value3())));
        return parents;
    }

    private static DSLContext dsl() {
        return MetadataDatabase.getInstance().dsl();
    }
}
