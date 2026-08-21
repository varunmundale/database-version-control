package org.example.parsers.postgres;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import org.example.parsers.DdlParser;
import org.example.core.replayer.SchemaOperation;
import org.example.models.schema.ColumnModel;

import java.util.List;
import java.util.Locale;

/**
 * Extracts a {@link SchemaOperation} from PostgreSQL {@code CREATE TABLE} / {@code ALTER TABLE ...
 * ADD|DROP|RENAME COLUMN} / {@code ALTER TABLE ... ALTER COLUMN ... TYPE ...} statements, using
 * <a href="https://github.com/JSQLParser/JSqlParser">JSqlParser</a> for the actual grammar rather than hand-rolled
 * pattern matching - it already handles quoting, schema-qualified names, and PostgreSQL's own type syntax
 * correctly. Table-level constraints and indexes are recognized and ignored; only tables and columns are modeled.
 */
public final class PostgresDdlParser implements DdlParser {
    @Override
    public SchemaOperation parse(String ddl) {
        Statement statement = parseStatement(ddl);
        if (statement instanceof CreateTable createTable) {
            return toCreateTable(createTable);
        }
        if (statement instanceof Alter alter) {
            return toAlterOperation(alter, ddl);
        }
        throw unsupported(ddl);
    }

    private static Statement parseStatement(String ddl) {
        try {
            return CCJSqlParserUtil.parse(ddl);
        } catch (JSQLParserException exception) {
            throw new IllegalArgumentException("Could not parse DDL statement: " + exception.getMessage(), exception);
        }
    }

    private SchemaOperation toCreateTable(CreateTable createTable) {
        String tableName = normalize(createTable.getTable().getName());
        List<ColumnModel> columns = createTable.getColumnDefinitions().stream()
                .map(PostgresDdlParser::toColumnModel)
                .toList();
        return new SchemaOperation.CreateTable(tableName, createTable.isIfNotExists(), columns);
    }

    private SchemaOperation toAlterOperation(Alter alter, String ddl) {
        List<AlterExpression> expressions = alter.getAlterExpressions();
        if (expressions.size() != 1) {
            throw unsupported(ddl);
        }
        AlterExpression expression = expressions.get(0);
        String tableName = normalize(alter.getTable().getName());

        return switch (expression.getOperation()) {
            case ADD -> new SchemaOperation.AddColumn(tableName, toColumnModel(soleColumn(expression, ddl)));
            case DROP -> new SchemaOperation.DropColumn(tableName, normalize(expression.getColumnName()));
            case RENAME -> new SchemaOperation.RenameColumn(tableName,
                    normalize(expression.getColumnOldName()), normalize(expression.getColumnName()));
            case ALTER -> toAlterColumnType(tableName, soleColumn(expression, ddl), ddl);
            default -> throw unsupported(ddl);
        };
    }

    /**
     * Only {@code ALTER COLUMN ... TYPE ...} (optionally with a {@code USING <expr>} conversion clause, which is
     * accepted but discarded - the internal model only needs the resulting type) is understood. {@code SET}/
     * {@code DROP NOT NULL} and similar leave the type token empty or non-{@code USING} specs, which this rejects
     * rather than silently misreading.
     */
    private SchemaOperation toAlterColumnType(String tableName, net.sf.jsqlparser.statement.create.table.ColumnDefinition column, String ddl) {
        List<String> specs = column.getColumnSpecs();
        String newType = column.getColDataType() == null ? null : column.getColDataType().getDataType();
        boolean hasUnsupportedSpecs = specs != null && !specs.isEmpty() && !specs.getFirst().equalsIgnoreCase("USING");
        if (hasUnsupportedSpecs || newType == null || newType.equalsIgnoreCase("SET")) {
            throw unsupported(ddl);
        }
        return new SchemaOperation.AlterColumnType(tableName, normalize(column.getColumnName()), normalizeType(newType));
    }

    private static AlterExpression.ColumnDataType soleColumn(AlterExpression expression, String ddl) {
        List<AlterExpression.ColumnDataType> columns = expression.getColDataTypeList();
        if (columns == null || columns.size() != 1) {
            throw unsupported(ddl);
        }
        return columns.get(0);
    }

    private static ColumnModel toColumnModel(net.sf.jsqlparser.statement.create.table.ColumnDefinition column) {
        String name = normalize(column.getColumnName());
        String type = normalizeType(column.getColDataType().getDataType());
        List<String> specs = column.getColumnSpecs();
        boolean primaryKey = containsSequence(specs, "PRIMARY", "KEY");
        boolean notNull = primaryKey || containsSequence(specs, "NOT", "NULL");
        return ColumnModel.unassigned(name, type, !notNull, defaultValueOf(specs));
    }

    private static boolean containsSequence(List<String> tokens, String... sequence) {
        if (tokens == null) {
            return false;
        }
        for (int start = 0; start + sequence.length <= tokens.size(); start++) {
            boolean matches = true;
            for (int offset = 0; offset < sequence.length; offset++) {
                if (!tokens.get(start + offset).equalsIgnoreCase(sequence[offset])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    /** {@code DEFAULT} is always followed by exactly the one token representing the default expression. */
    private static String defaultValueOf(List<String> specs) {
        if (specs == null) {
            return null;
        }
        for (int index = 0; index < specs.size() - 1; index++) {
            if (specs.get(index).equalsIgnoreCase("DEFAULT")) {
                return specs.get(index + 1);
            }
        }
        return null;
    }

    private static String normalize(String identifier) {
        return identifier.toLowerCase(Locale.ROOT);
    }

    /** JSqlParser renders e.g. {@code NUMERIC(10,2)} as {@code "NUMERIC (10, 2)"}; this collapses that back to a canonical, whitespace-free form around parens without touching multi-word type names like {@code DOUBLE PRECISION}. */
    private static String normalizeType(String dataType) {
        return dataType.strip()
                .replaceAll("\\s*\\(\\s*", "(")
                .replaceAll("\\s*,\\s*", ",")
                .replaceAll("\\s*\\)", ")");
    }

    private static IllegalArgumentException unsupported(String statement) {
        return new IllegalArgumentException(
                "Unsupported DDL statement (only CREATE TABLE and ALTER TABLE ADD|DROP|RENAME COLUMN|ALTER COLUMN TYPE are supported): " + statement);
    }
}
