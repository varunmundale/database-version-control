package org.example.repository;

import org.example.models.tracking.TrackedDatabase;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

import java.util.Optional;

/**
 * The {@code tracked_databases} table: which physical database each branch's DDL is applied to. Holds no password -
 * see {@link TrackedDatabase}.
 */
public final class TrackedDatabaseRepository {
    private static final TrackedDatabaseRepository INSTANCE = new TrackedDatabaseRepository();

    private static final Table<Record> TABLE = DSL.table("tracked_databases");
    private static final Field<String> BRANCH_NAME = DSL.field("branch_name", SQLDataType.VARCHAR);
    private static final Field<String> SIGNATURE = DSL.field("signature", SQLDataType.VARCHAR);
    private static final Field<String> HOST = DSL.field("host", SQLDataType.VARCHAR);
    private static final Field<Integer> PORT = DSL.field("port", SQLDataType.INTEGER);
    private static final Field<String> DATABASE_NAME = DSL.field("database_name", SQLDataType.VARCHAR);
    private static final Field<String> DB_USER = DSL.field("db_user", SQLDataType.VARCHAR);

    private TrackedDatabaseRepository() {
    }

    public static TrackedDatabaseRepository getInstance() {
        return INSTANCE;
    }

    /** Records what a branch tracks, replacing whatever it tracked before - which is what makes re-initialising idempotent. */
    public void upsert(TrackedDatabase tracked) {
        dsl().insertInto(TABLE, BRANCH_NAME, SIGNATURE, HOST, PORT, DATABASE_NAME, DB_USER)
                .values(tracked.branch(), tracked.signature(), tracked.host(), tracked.port(),
                        tracked.database(), tracked.user())
                .onConflict(BRANCH_NAME)
                .doUpdate()
                .set(SIGNATURE, tracked.signature())
                .set(HOST, tracked.host())
                .set(PORT, tracked.port())
                .set(DATABASE_NAME, tracked.database())
                .set(DB_USER, tracked.user())
                .set(DSL.field("updated_at", SQLDataType.TIMESTAMPWITHTIMEZONE), DSL.currentOffsetDateTime())
                .execute();
    }

    public Optional<TrackedDatabase> find(String branch) {
        return dsl().select(BRANCH_NAME, SIGNATURE, HOST, PORT, DATABASE_NAME, DB_USER).from(TABLE)
                .where(BRANCH_NAME.eq(branch))
                .fetchOptional(row -> new TrackedDatabase(row.value1(), row.value2(), row.value3(),
                        row.value4(), row.value5(), row.value6()));
    }

    private static DSLContext dsl() {
        return MetadataDatabase.getInstance().dsl();
    }
}
