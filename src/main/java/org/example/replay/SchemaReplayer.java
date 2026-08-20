package org.example.replay;

import org.example.ddl.DdlStatementParser;
import org.example.model.schema.TableModel;
import org.example.model.versioning.ChangeSet;
import org.example.model.versioning.ChangesetStatus;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Rebuilds an in-memory schema from scratch by replaying DDL changesets, in order, on top of a starting point. */
public final class SchemaReplayer {
    private final String schema;
    private final DdlStatementParser ddlParser = new DdlStatementParser();

    public SchemaReplayer(String schema) {
        this.schema = Objects.requireNonNull(schema, "schema must not be null");
    }

    public Map<String, TableModel> replay(List<ChangeSet> changesets, Map<String, TableModel> seed) {
        for (ChangeSet changeset : changesets) {
            if (changeset.status() != ChangesetStatus.APPLIED && changeset.status() != ChangesetStatus.COMMIT) {
                continue;
            }
            String tableName = ddlParser.tableName(changeset.ddl());
            seed.put(tableName, ddlParser.apply(schema, changeset.ddl(), seed.get(tableName)));
        }
        return seed;
    }
}
