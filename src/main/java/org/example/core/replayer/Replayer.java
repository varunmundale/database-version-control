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
 * Rebuilds a schema entirely in memory - no database, no scratch connection - by reading each changeset's raw
 * {@code ddl}, extracting its {@link SchemaOperation} via a {@link DdlParser} (chosen by
 * {@code branchDatabases.dialect} - see {@link DdlParserRegistry} - by default), and folding it into a
 * {@link TableModel} via {@link SchemaOperationApplier}. This class owns neither concern itself: parsing is
 * per-vendor and injected, applying is shared and stateless, so this class stays the same regardless of which
 * dialect's DDL grammar is actually being replayed.
 *
 * <p>{@code dbgit add}'s own preview goes through the same {@link DdlParser} and {@link SchemaOperationApplier}
 * (via {@link #tableName} and {@link #apply}), so there is exactly one parser and one applier in play anywhere in
 * the tool.
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

    /**
     * Rebuilds a schema by reading {@code changesets} and applying each one's ddl, in order, into a fresh map. Pure
     * in-memory. The map never holds a {@code null} value: {@link #apply} removes a dropped or renamed-away table's
     * key rather than storing one, so nothing downstream has to guard against it.
     */
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
     * Applies one DDL statement to {@code schema}, mutating it in place, and returns the table's new state (or
     * {@code null} if the statement was a {@code DROP TABLE}).
     *
     * <p>{@link SchemaOperationApplier} edits one {@link TableModel} it is handed and knows nothing of the schema
     * as a whole, so moving its answer to the right map key - the same key for most operations, a different one
     * for a rename, no key at all for a drop - is this method's job, and the reason it takes the whole map rather
     * than one table. It is also the only place two things can be checked, since only here is the rest of the
     * schema visible: a rename onto a name already taken, and a fresh table minting the stable id a still-live
     * table already carries under a different name - which {@code CREATE TABLE orders} can do the moment something
     * else has been renamed away from {@code orders}, since a table's id is derived from its name (see
     * {@link TableModel#create}) and nothing else stops the same id being minted twice.
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
