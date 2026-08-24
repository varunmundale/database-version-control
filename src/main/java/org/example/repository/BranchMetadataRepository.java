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

    /**
     * Removes a branch that was claimed but never finished being built. Only ever right for a branch with no
     * changesets of its own - the foreign keys from {@code branch_changesets} and {@code tracked_databases}
     * would refuse otherwise, which is the safety net rather than the mechanism.
     */
    public void delete(String branchName) {
        dsl().deleteFrom(TABLE).where(BRANCH_NAME.eq(branchName)).execute();
    }

    /** {@code null} both when the branch doesn't exist and when it exists but hasn't committed anything yet. */
    public Long findHeadCommitId(String branchName) {
        return dsl().select(HEAD_COMMIT_ID).from(TABLE).where(BRANCH_NAME.eq(branchName)).fetchOne(HEAD_COMMIT_ID);
    }

    /**
     * Compare-and-set: moves a branch's HEAD only if it is still where the caller last read it. Returns the number
     * of rows changed - zero meaning someone else moved it first. Insurance against a missed branch lock, since a
     * blind write could otherwise strand a commit unreachable while its changesets are already marked {@code COMMIT}.
     *
     * @param expectedCommitId the HEAD the caller based its work on; {@code null} for a branch with no commits yet
     */
    public int updateHeadCommitId(String branchName, Long expectedCommitId, long commitId) {
        return dsl().update(TABLE).set(HEAD_COMMIT_ID, commitId)
                .where(BRANCH_NAME.eq(branchName))
                .and(expectedCommitId == null ? HEAD_COMMIT_ID.isNull() : HEAD_COMMIT_ID.eq(expectedCommitId))
                .execute();
    }

    private static DSLContext dsl() {
        return MetadataDatabase.getInstance().dsl();
    }
}
