package org.example.adapters;

import org.example.core.replayer.SchemaOperation;

/**
 * Understands one vendor's DDL grammar well enough to extract a {@link SchemaOperation} from a raw statement.
 * Applying the resulting operation to the internal model is a separate concern - see
 * {@link org.example.core.replayer.SchemaOperationApplier}.
 */
public interface DdlParser {
    /** Extracts the operation a DDL statement performs. */
    SchemaOperation parse(String ddl);
}
