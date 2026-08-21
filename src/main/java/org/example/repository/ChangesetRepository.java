package org.example.repository;

import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.ChangesetStatus;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectJoinStep;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The {@code branch_changesets} table: one row per staged DDL statement, mapped straight to a {@link ChangeSet}. */
public final class ChangesetRepository {
    private static final ChangesetRepository INSTANCE = new ChangesetRepository();

    private static final Table<Record> TABLE = DSL.table("branch_changesets");
    private static final Field<Long> ID = DSL.field("id", SQLDataType.BIGINT);
    private static final Field<String> BRANCH_NAME = DSL.field("branch_name", SQLDataType.VARCHAR);
    private static final Field<String> DDL = DSL.field("ddl", SQLDataType.CLOB);
    private static final Field<String> STATUS = DSL.field("status", SQLDataType.VARCHAR);
    private static final Field<Long> COMMIT_ID = DSL.field("commit_id", SQLDataType.BIGINT);
    private static final Field<OffsetDateTime> APPLIED_AT = DSL.field("applied_at", SQLDataType.TIMESTAMPWITHTIMEZONE);

    private ChangesetRepository() {
    }

    public static ChangesetRepository getInstance() {
        return INSTANCE;
    }

    public long insertPending(String branchName, String ddl) {
        return dsl().insertInto(TABLE, BRANCH_NAME, DDL, STATUS)
                .values(branchName, ddl, ChangesetStatus.PENDING.name())
                .returning(ID)
                .fetchOne(ID);
    }

    public void markApplied(long changesetId) {
        dsl().update(TABLE).set(STATUS, ChangesetStatus.APPLIED.name()).where(ID.eq(changesetId)).execute();
    }

    /** Folds the given, currently APPLIED changesets into {@code commitId}. Ids not currently APPLIED are left untouched. */
    public void markCommitted(List<Long> changesetIds, long commitId) {
        dsl().update(TABLE).set(STATUS, ChangesetStatus.COMMIT.name()).set(COMMIT_ID, commitId)
                .where(ID.in(changesetIds)).and(STATUS.eq(ChangesetStatus.APPLIED.name()))
                .execute();
    }

    /** Every changeset staged for a branch, in staged order, regardless of status. */
    public List<ChangeSet> findByBranch(String branchName) {
        return select().where(BRANCH_NAME.eq(branchName)).orderBy(ID).fetch(ChangesetRepository::toChangeSet);
    }

    /** The changesets folded into each of the given commits, keyed by commit id and in staged order within a commit. */
    public Map<Long, List<ChangeSet>> findGroupedByCommitId(List<Long> commitIds) {
        if (commitIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<ChangeSet>> byCommit = new LinkedHashMap<>();
        select().where(COMMIT_ID.in(commitIds)).orderBy(ID).forEach(row ->
                byCommit.computeIfAbsent(row.get(COMMIT_ID), unused -> new ArrayList<>()).add(toChangeSet(row)));
        return byCommit;
    }

    private static SelectJoinStep<? extends Record> select() {
        return dsl().select(ID, BRANCH_NAME, DDL, STATUS, COMMIT_ID, APPLIED_AT).from(TABLE);
    }

    private static ChangeSet toChangeSet(Record row) {
        return new ChangeSet(row.get(ID), row.get(BRANCH_NAME), row.get(DDL),
                ChangesetStatus.valueOf(row.get(STATUS)), row.get(APPLIED_AT).toInstant());
    }

    private static DSLContext dsl() {
        return MetadataDatabase.getInstance().dsl();
    }
}
