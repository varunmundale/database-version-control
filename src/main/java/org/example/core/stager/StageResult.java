package org.example.core.stager;

/** Details of one DDL statement staged and applied via {@link Stager}. */
public record StageResult(long changesetId, String tableName, int columnCount) {
}
