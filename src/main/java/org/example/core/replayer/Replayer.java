package org.example.core.replayer;

import org.example.config.BranchDatabaseConfig;
import org.example.models.schema.TableModel;
import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.ChangesetStatus;
import org.example.adapters.DdlParser;
import org.example.adapters.spi.DdlParserRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rebuilds a schema entirely in memory - no database - by parsing each changeset's raw {@code ddl} via a
 * {@link DdlParser} (chosen by {@code branchDatabases.dialect}, see {@link DdlParserRegistry}) into a
 * {@link SchemaOperation} and folding it into a {@link TableModel} via {@link SchemaOperationApplier}.
 * {@code dbgit add}'s preview uses this same parser/applier pair, so there is exactly one of each in the tool.
 */
public final class Replayer {
    private static final String SCHEMA = "public";

    private final DdlParser ddlParser;
    private final SchemaOperationApplier operationApplier = new SchemaOperationApplier();

    public Replayer() {
        this(DdlParserRegistry.builtins().get(BranchDatabaseConfig.getInstance().dialect()));
    }

    public Replayer(DdlParser ddlParser) {
        this.ddlParser = Objects.requireNonNull(ddlParser, "ddlParser must not be null");
    }

    /** Applies each changeset's ddl, in order, into a fresh map; {@link #apply} keeps it free of {@code null} values. */
    public Map<String, TableModel> replay(List<ChangeSet> changesets) {
        Map<String, TableModel> schema = new LinkedHashMap<>();
        for (ChangeSet changeset : changesets) {
            if (changeset.status() != ChangesetStatus.APPLIED && changeset.status() != ChangesetStatus.COMMIT) {
                continue;
            }
            apply(schema, changeset.ddl());
        }
        return schema;
    }

    /** The table a CREATE or ALTER statement targets. */
    public String tableName(String ddl) {
        return ddlParser.parse(ddl).tableName();
    }

    /**
     * Applies one DDL statement to {@code schema} in place, returning the table's new state ({@code null} for
     * {@code DROP TABLE}). Moves the result to the right map key (rename/drop included) and, since this is the only
     * place the whole schema is visible, rejects a rename or a fresh {@code CREATE TABLE} that collides with a name
     * or stable id already in use.
     */
    public TableModel apply(Map<String, TableModel> schema, String ddl) {
        SchemaOperation operation = ddlParser.parse(ddl);
        TableModel existing = schema.get(operation.tableName());
        String targetName = operation instanceof SchemaOperation.RenameTable rename ? rename.newName() : operation.tableName();
        if (!targetName.equals(operation.tableName()) && schema.containsKey(targetName)) {
            throw new IllegalArgumentException("Table already exists: " + targetName);
        }
        TableModel updated = operationApplier.apply(SCHEMA, operation, existing);
        if (updated != null && schema.values().stream().anyMatch(table -> table != existing && table.id().equals(updated.id()))) {
            throw new IllegalArgumentException("Table already exists: " + updated.name()
                    + " (a live table already carries this stable id, from an earlier rename)");
        }
        if (updated == null || !updated.name().equals(operation.tableName())) {
            schema.remove(operation.tableName());
        }
        if (updated != null) {
            schema.put(updated.name(), updated);
        }
        return updated;
    }
}
