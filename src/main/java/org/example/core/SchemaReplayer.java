package org.example.core;

import org.example.models.schema.TableModel;
import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.ChangesetStatus;
import org.example.parsers.DdlParser;
import org.example.parsers.postgres.PostgresDdlParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rebuilds a schema entirely in memory - no database, no scratch connection - by reading each changeset's raw
 * {@code ddl}, extracting its {@link SchemaOperation} via a {@link DdlParser} (PostgreSQL by default), and folding
 * it into a {@link TableModel} via {@link SchemaOperationApplier}. This class owns neither concern itself: parsing
 * is per-vendor and injected, applying is shared and stateless, so this class stays the same regardless of which
 * dialect's DDL grammar is actually being replayed.
 *
 * <p>{@code dbgit add}'s own preview goes through the same {@link DdlParser} and {@link SchemaOperationApplier}
 * (via {@link #tableName} and {@link #apply}), so there is exactly one parser and one applier in play anywhere in
 * the tool.
 */
public final class SchemaReplayer {
    private static final String SCHEMA = "public";

    private final DdlParser ddlParser;
    private final SchemaOperationApplier operationApplier = new SchemaOperationApplier();

    public SchemaReplayer() {
        this(new PostgresDdlParser());
    }

    public SchemaReplayer(DdlParser ddlParser) {
        this.ddlParser = Objects.requireNonNull(ddlParser, "ddlParser must not be null");
    }

    /** Rebuilds a schema by reading {@code changesets} and applying each one's ddl, in order. Pure in-memory. */
    public Map<String, TableModel> replay(List<ChangeSet> changesets) {
        Map<String, TableModel> schema = new LinkedHashMap<>();
        for (ChangeSet changeset : changesets) {
            if (changeset.status() != ChangesetStatus.APPLIED && changeset.status() != ChangesetStatus.COMMIT) {
                continue;
            }
            SchemaOperation operation = ddlParser.parse(changeset.ddl());
            schema.put(operation.tableName(), operationApplier.apply(SCHEMA, operation, schema.get(operation.tableName())));
        }
        return schema;
    }

    /** The table a CREATE or ALTER statement targets. */
    public String tableName(String ddl) {
        return ddlParser.parse(ddl).tableName();
    }

    /**
     * Builds or mutates the internal representation of one table from a DDL statement.
     *
     * @param existing the table's current state, or {@code null} if it does not exist yet
     */
    public TableModel apply(String schema, String ddl, TableModel existing) {
        return operationApplier.apply(schema, ddlParser.parse(ddl), existing);
    }
}
