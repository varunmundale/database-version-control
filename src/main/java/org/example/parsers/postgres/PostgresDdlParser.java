package org.example.parsers.postgres;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.drop.Drop;
import org.example.core.replayer.SchemaOperation;
import org.example.models.schema.ColumnModel;
import org.example.models.schema.ConstraintType;
import org.example.parsers.DdlParser;

import java.util.List;
import java.util.Locale;

/**
 * Extracts a {@link SchemaOperation} from PostgreSQL DDL, using
 * <a href="https://github.com/JSQLParser/JSqlParser">JSqlParser</a> for the actual grammar rather than hand-rolled
 * pattern matching - it already handles quoting, schema-qualified names, and PostgreSQL's own type syntax
 * correctly.
 *
 * <p>Understood statements:
 * <ul>
 *   <li>{@code CREATE TABLE} - columns only</li>
 *   <li>{@code ALTER TABLE ... ADD|DROP|RENAME COLUMN}, {@code ALTER TABLE ... ALTER COLUMN ... TYPE ...} or
 *       MySQL's {@code ALTER TABLE ... MODIFY COLUMN ...} - both spellings of a retype funnel into the same
 *       {@link SchemaOperation.AlterColumnType}, since the model only cares that the column's type changed, not
 *       which dialect's syntax said so</li>
 *   <li>{@code ALTER TABLE ... ADD CONSTRAINT <name> PRIMARY KEY|UNIQUE|FOREIGN KEY (...)}</li>
 *   <li>{@code ALTER TABLE ... DROP CONSTRAINT <name>}</li>
 *   <li>{@code CREATE [UNIQUE] INDEX <name> ON <table> (...)}</li>
 * </ul>
 *
 * <p>Constraints and indexes must be declared as their own statements: a {@code CREATE TABLE} that carries a
 * constraint - whether written on a column ({@code id INT PRIMARY KEY}) or as a table-level clause
 * ({@code , PRIMARY KEY (id)}) - is rejected, rather than silently ignored as it once was. A constraint dbgit
 * cannot see is a constraint it cannot diff, merge or replay onto a forked branch. {@code NOT NULL} and
 * {@code DEFAULT} are exempt: they are properties of the column itself, and the model already carries them.
 */
public final class PostgresDdlParser implements DdlParser {
    private static final String PRIMARY_KEY = "PRIMARY KEY";
    private static final String UNIQUE = "UNIQUE";
    private static final String FOREIGN_KEY = "FOREIGN KEY";

    @Override
    public SchemaOperation parse(String ddl) {
        Statement statement = parseStatement(ddl);
        if (statement instanceof CreateTable createTable) {
            return toCreateTable(createTable, ddl);
        }
        if (statement instanceof Alter alter) {
            return toAlterOperation(alter, ddl);
        }
        if (statement instanceof CreateIndex createIndex) {
            return toCreateIndex(createIndex, ddl);
        }
        if (statement instanceof Drop drop && "INDEX".equalsIgnoreCase(drop.getType())) {
            throw new IllegalArgumentException("DROP INDEX is not supported: " + ddl
                    + ". A dropped index names no table, and dbgit replays history one table at a time, so it cannot"
                    + " tell which table the index belonged to. Drop the constraint that owns it instead, e.g."
                    + " ALTER TABLE <table> DROP CONSTRAINT <name>.");
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

    private SchemaOperation toCreateTable(CreateTable createTable, String ddl) {
        String tableName = normalize(createTable.getTable().getName());
        rejectTableLevelConstraints(createTable, tableName);
        List<ColumnModel> columns = createTable.getColumnDefinitions().stream()
                .map(column -> toColumnModel(column, tableName))
                .toList();
        return new SchemaOperation.CreateTable(tableName, createTable.isIfNotExists(), columns);
    }

    /** A {@code PRIMARY KEY (...)}, {@code UNIQUE (...)}, {@code FOREIGN KEY (...)} or {@code CHECK (...)} clause in the table body. */
    private static void rejectTableLevelConstraints(CreateTable createTable, String tableName) {
        List<Index> tableLevel = createTable.getIndexes();
        if (tableLevel == null || tableLevel.isEmpty()) {
            return;
        }
        String type = tableLevel.getFirst().getType();
        throw new IllegalArgumentException("CREATE TABLE defines columns only, but this one declares a "
                + (type == null ? "constraint" : type + " constraint") + " on '" + tableName + "'."
                + " Add it separately, e.g. ALTER TABLE " + tableName + " ADD CONSTRAINT " + tableName
                + "_pkey PRIMARY KEY (<column>), or CREATE INDEX <name> ON " + tableName + " (<column>).");
    }

    private SchemaOperation toAlterOperation(Alter alter, String ddl) {
        List<AlterExpression> expressions = alter.getAlterExpressions();
        if (expressions.size() != 1) {
            throw unsupported(ddl);
        }
        AlterExpression expression = expressions.get(0);
        String tableName = normalize(alter.getTable().getName());

        return switch (expression.getOperation()) {
            case ADD -> expression.getIndex() == null
                    ? new SchemaOperation.AddColumn(tableName, toColumnModel(soleColumn(expression, ddl), tableName))
                    : toAddConstraint(tableName, expression.getIndex(), ddl);
            case DROP -> expression.getConstraintName() == null
                    ? new SchemaOperation.DropColumn(tableName, normalize(expression.getColumnName()))
                    : new SchemaOperation.DropConstraint(tableName, normalize(expression.getConstraintName()));
            case RENAME -> new SchemaOperation.RenameColumn(tableName,
                    normalize(expression.getColumnOldName()), normalize(expression.getColumnName()));
            case ALTER, MODIFY -> toAlterColumnType(tableName, soleColumn(expression, ddl), ddl);
            default -> throw unsupported(ddl);
        };
    }

    /**
     * The constraint's type is settled first: an unsupported one (a {@code CHECK}, say) must be rejected before
     * anything tries to read the columns it covers, which JSqlParser does not populate for those.
     */
    private SchemaOperation toAddConstraint(String tableName, Index constraint, String ddl) {
        ConstraintType type = constraint instanceof ForeignKeyIndex
                ? ConstraintType.FOREIGN_KEY
                : constraintType(constraint.getType(), ddl);
        if (constraint.getName() == null) {
            throw new IllegalArgumentException("A constraint must be given a name so dbgit can track it across"
                    + " branches: " + ddl + ". Write it as ALTER TABLE " + tableName + " ADD CONSTRAINT <name> ...");
        }
        String name = normalize(constraint.getName());
        List<String> columns = normalizeAll(constraint.getColumnsNames());

        if (constraint instanceof ForeignKeyIndex foreignKey) {
            return new SchemaOperation.AddConstraint(tableName, name, type, columns,
                    normalize(foreignKey.getTable().getName()), normalizeAll(foreignKey.getReferencedColumnNames()));
        }
        return new SchemaOperation.AddConstraint(tableName, name, type, columns, null, List.of());
    }

    private static ConstraintType constraintType(String type, String ddl) {
        if (PRIMARY_KEY.equalsIgnoreCase(type)) {
            return ConstraintType.PRIMARY_KEY;
        }
        if (UNIQUE.equalsIgnoreCase(type)) {
            return ConstraintType.UNIQUE;
        }
        if (FOREIGN_KEY.equalsIgnoreCase(type)) {
            return ConstraintType.FOREIGN_KEY;
        }
        throw new IllegalArgumentException("Unsupported constraint type "
                + (type == null ? "(CHECK or similar)" : "'" + type + "'") + ": " + ddl
                + ". Only PRIMARY KEY, UNIQUE and FOREIGN KEY constraints are modeled.");
    }

    private SchemaOperation toCreateIndex(CreateIndex createIndex, String ddl) {
        Index index = createIndex.getIndex();
        String type = index.getType();
        if (type != null && !UNIQUE.equalsIgnoreCase(type)) {
            throw new IllegalArgumentException("Unsupported index type '" + type + "': " + ddl
                    + ". Only plain and UNIQUE indexes are modeled.");
        }
        return new SchemaOperation.CreateIndex(normalize(createIndex.getTable().getName()),
                normalize(index.getName()), UNIQUE.equalsIgnoreCase(type), normalizeAll(index.getColumnsNames()));
    }

    /**
     * Reached from either {@code ALTER COLUMN c TYPE t} (optionally with a {@code USING <expr>} conversion clause,
     * which is accepted but discarded - the internal model only needs the resulting type) or MySQL's
     * {@code MODIFY COLUMN c t} - JSqlParser tells the two apart via {@link net.sf.jsqlparser.statement.alter.AlterOperation},
     * but both describe a column definition the same way, so one method builds the same
     * {@link SchemaOperation.AlterColumnType} from either. {@code SET}/{@code DROP NOT NULL} and similar leave the
     * type token empty or non-{@code USING} specs, which this rejects rather than silently misreading.
     */
    private SchemaOperation toAlterColumnType(String tableName, ColumnDefinition column, String ddl) {
        List<String> specs = column.getColumnSpecs();
        String newType = column.getColDataType() == null ? null : column.getColDataType().getDataType();
        boolean hasUnsupportedSpecs = specs != null && !specs.isEmpty() && !specs.getFirst().equalsIgnoreCase("USING");
        if (hasUnsupportedSpecs || newType == null || newType.equalsIgnoreCase("SET")) {
            throw unsupported(ddl);
        }
        return new SchemaOperation.AlterColumnType(tableName, normalize(column.getColumnName()), normalizeType(newType));
    }

    private static ColumnDefinition soleColumn(AlterExpression expression, String ddl) {
        List<AlterExpression.ColumnDataType> columns = expression.getColDataTypeList();
        if (columns == null || columns.size() != 1) {
            throw unsupported(ddl);
        }
        return columns.get(0);
    }

    private static ColumnModel toColumnModel(ColumnDefinition column, String tableName) {
        String name = normalize(column.getColumnName());
        String type = normalizeType(column.getColDataType().getDataType());
        ColumnSpecs specs = ColumnSpecs.of(column.getColumnSpecs(), name, tableName);
        return ColumnModel.unassigned(name, type, !specs.notNull(), specs.defaultValue());
    }

    /**
     * The two column specs the model actually carries. Anything else a column declares - {@code PRIMARY KEY},
     * {@code UNIQUE}, {@code REFERENCES}, {@code CHECK} - is a constraint wearing a column's clothing, and is
     * rejected here rather than quietly dropped.
     */
    private record ColumnSpecs(boolean notNull, String defaultValue) {
        private static ColumnSpecs of(List<String> specs, String columnName, String tableName) {
            if (specs == null || specs.isEmpty()) {
                return new ColumnSpecs(false, null);
            }
            boolean notNull = false;
            String defaultValue = null;
            for (int index = 0; index < specs.size(); index++) {
                String token = specs.get(index);
                if (token.equalsIgnoreCase("NOT") && index + 1 < specs.size() && specs.get(index + 1).equalsIgnoreCase("NULL")) {
                    notNull = true;
                    index++;
                } else if (token.equalsIgnoreCase("NULL")) {
                    continue;
                } else if (token.equalsIgnoreCase("DEFAULT") && index + 1 < specs.size()) {
                    defaultValue = specs.get(index + 1);
                    index++;
                } else {
                    throw inlineConstraint(specs, index, columnName, tableName);
                }
            }
            return new ColumnSpecs(notNull, defaultValue);
        }

        /** Names the constraint the column tried to declare, and suggests the statement that should carry it instead. */
        private static IllegalArgumentException inlineConstraint(List<String> specs, int index, String columnName, String tableName) {
            String token = specs.get(index).toUpperCase(Locale.ROOT);
            boolean primaryKey = token.equals("PRIMARY");
            String kind = primaryKey ? "PRIMARY KEY" : token;
            String suggestion = switch (kind) {
                case "PRIMARY KEY" -> "ALTER TABLE " + tableName + " ADD CONSTRAINT " + tableName + "_pkey PRIMARY KEY ("
                        + columnName + ")";
                case "UNIQUE" -> "ALTER TABLE " + tableName + " ADD CONSTRAINT " + tableName + "_" + columnName
                        + "_key UNIQUE (" + columnName + ")";
                case "REFERENCES" -> "ALTER TABLE " + tableName + " ADD CONSTRAINT " + tableName + "_" + columnName
                        + "_fkey FOREIGN KEY (" + columnName + ") REFERENCES <table> (<column>)";
                default -> "ALTER TABLE " + tableName + " ADD CONSTRAINT <name> " + kind + " (...)";
            };
            return new IllegalArgumentException("Column '" + columnName + "' declares " + kind
                    + ", which is a constraint rather than a property of the column itself."
                    + " Define the column by itself and add the constraint separately, e.g. " + suggestion + "."
                    + " Only NOT NULL and DEFAULT may be written on a column.");
        }
    }

    private static List<String> normalizeAll(List<String> identifiers) {
        return identifiers == null ? List.of() : identifiers.stream().map(PostgresDdlParser::normalize).toList();
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
        return new IllegalArgumentException("Unsupported DDL statement: " + statement
                + ". Supported: CREATE TABLE (columns only); ALTER TABLE ADD|DROP|RENAME COLUMN;"
                + " ALTER TABLE ALTER COLUMN ... TYPE (or MySQL's ALTER TABLE ... MODIFY COLUMN ...);"
                + " ALTER TABLE ADD CONSTRAINT ...; ALTER TABLE DROP CONSTRAINT; CREATE [UNIQUE] INDEX.");
    }
}
