package org.example.repository;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.util.List;

/** The {@code branch_metadata} table: which branches exist, and each one's current HEAD commit. */
public final class BranchMetadataRepository {
    private static final BranchMetadataRepository INSTANCE = new BranchMetadataRepository();

    private static final Table<Record> TABLE = DSL.table("branch_metadata");
    private static final Field<String> BRANCH_NAME = DSL.field("branch_name", SQLDataType.VARCHAR);
    private static final Field<String> FORKED_FROM = DSL.field("forked_from", SQLDataType.VARCHAR);
    private static final Field<Long> HEAD_COMMIT_ID = DSL.field("head_commit_id", SQLDataType.BIGINT);

    private BranchMetadataRepository() {
    }

    public static BranchMetadataRepository getInstance() {
        return INSTANCE;
    }

    public List<String> findAllNames() {
        return dsl().select(BRANCH_NAME).from(TABLE).orderBy(BRANCH_NAME).fetch(BRANCH_NAME);
    }

    /** Inserts {@code branchName}, copying {@code forkedFrom}'s current HEAD commit if given. Returns false if the branch already existed. */
    public boolean insert(String branchName, String forkedFrom) {
        int inserted = forkedFrom == null
                ? dsl().insertInto(TABLE, BRANCH_NAME, FORKED_FROM)
                        .values(branchName, (String) null)
                        .onConflictDoNothing()
                        .execute()
                : dsl().insertInto(TABLE, BRANCH_NAME, FORKED_FROM, HEAD_COMMIT_ID)
                        .select(DSL.select(DSL.val(branchName), DSL.val(forkedFrom), HEAD_COMMIT_ID)
                                .from(TABLE).where(BRANCH_NAME.eq(forkedFrom)))
                        .onConflictDoNothing()
                        .execute();
        return inserted > 0;
    }

    /** {@code null} both when the branch doesn't exist and when it exists but hasn't committed anything yet. */
    public Long findHeadCommitId(String branchName) {
        return dsl().select(HEAD_COMMIT_ID).from(TABLE).where(BRANCH_NAME.eq(branchName)).fetchOne(HEAD_COMMIT_ID);
    }

    public void updateHeadCommitId(String branchName, long commitId) {
        dsl().update(TABLE).set(HEAD_COMMIT_ID, commitId).where(BRANCH_NAME.eq(branchName)).execute();
    }

    private static DSLContext dsl() {
        return MetadataDatabase.getInstance().dsl();
    }
}
