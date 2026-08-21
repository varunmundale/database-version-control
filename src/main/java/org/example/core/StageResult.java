package org.example.core;

/** Details of one DDL statement staged and applied via {@link ChangesetStager}. */
public record StageResult(long changesetId, String tableName, int columnCount) {
}
