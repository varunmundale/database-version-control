package org.example.repository;

import org.example.models.versioning.Commit;
import org.example.models.versioning.CommitMetadata;
import org.example.models.versioning.CommitParents;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/** The {@code branch_commits} table: the shared commit graph every branch's history is woven from. */
public final class CommitRepository {
    private static final CommitRepository INSTANCE = new CommitRepository();

    private static final Table<Record> TABLE = DSL.table("branch_commits");
    private static final Field<Long> ID = DSL.field("id", SQLDataType.BIGINT);
    private static final Field<Long> PARENT_COMMIT_ID = DSL.field("parent_commit_id", SQLDataType.BIGINT);
    private static final Field<Long> SECOND_PARENT_COMMIT_ID = DSL.field("second_parent_commit_id", SQLDataType.BIGINT);
    private static final Field<String> AUTHOR = DSL.field("author", SQLDataType.CLOB);
    private static final Field<String> MESSAGE = DSL.field("message", SQLDataType.CLOB);
    private static final Field<OffsetDateTime> CREATED_AT = DSL.field("created_at", SQLDataType.TIMESTAMPWITHTIMEZONE);

    private CommitRepository() {
    }

    public static CommitRepository getInstance() {
        return INSTANCE;
    }

    /** Inserts a commit with the given parent(s) - {@code secondParentCommitId} non-null only for a merge commit - and returns its generated id. */
    public long insert(Long parentCommitId, Long secondParentCommitId, CommitMetadata metadata) {
        return dsl().insertInto(TABLE, PARENT_COMMIT_ID, SECOND_PARENT_COMMIT_ID, AUTHOR, MESSAGE)
                .values(parentCommitId, secondParentCommitId, metadata.author(), metadata.message())
                .returning(ID)
                .fetchOne(ID);
    }

    /** Every commit, keyed by id - the raw material a branch's history is walked out of, and what {@code dbgit log} reads. */
    public Map<Long, Commit> findAll() {
        Map<Long, Commit> commits = new HashMap<>();
        dsl().select(ID, PARENT_COMMIT_ID, SECOND_PARENT_COMMIT_ID, AUTHOR, MESSAGE, CREATED_AT).from(TABLE)
                .forEach(row -> commits.put(row.get(ID), toCommit(row)));
        return commits;
    }

    private static Commit toCommit(Record row) {
        return new Commit(row.get(ID),
                new CommitMetadata(row.get(AUTHOR), row.get(MESSAGE)),
                row.get(CREATED_AT).toInstant(),
                new CommitParents(row.get(PARENT_COMMIT_ID), row.get(SECOND_PARENT_COMMIT_ID)));
    }

    private static DSLContext dsl() {
        return MetadataDatabase.getInstance().dsl();
    }
}
