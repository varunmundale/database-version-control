package org.example.adapters;

import org.example.core.replayer.SchemaOperation;

/**
 * Understands one vendor's DDL grammar well enough to extract a {@link SchemaOperation} from a raw statement.
 * Each database dialect has its own syntax quirks, so parsing is owned per vendor; applying the resulting
 * operation to the internal model is not - see {@link org.example.core.replayer.SchemaOperationApplier}.
 *
 * <p>Onboarding a new dialect means implementing this interface and wiring the implementation in wherever a
 * {@link DdlParser} is constructed - no other code needs to change.
 */
public interface DdlParser {
    /** Extracts the operation a DDL statement performs. */
    SchemaOperation parse(String ddl);
}
