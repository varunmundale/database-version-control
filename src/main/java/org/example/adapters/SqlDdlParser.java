package org.example.adapters;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.alter.Alter;
import net.sf.jsqlparser.statement.alter.AlterExpression;
import net.sf.jsqlparser.statement.alter.AlterOperation;
import net.sf.jsqlparser.statement.create.index.CreateIndex;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.Index;
import net.sf.jsqlparser.statement.drop.Drop;
import org.example.core.replayer.SchemaOperation;
import org.example.models.schema.ColumnModel;

import java.util.List;

/**
 * Dispatches DDL parsing for every SQL dialect dbgit understands, using
 * <a href="https://github.com/JSQLParser/JSqlParser">JSqlParser</a> for the actual grammar rather than hand-rolled
 * pattern matching - it already handles quoting, schema-qualified names, and each vendor's own type syntax
 * correctly. {@code CREATE TABLE} (columns only), {@code ALTER TABLE ADD|DROP|RENAME COLUMN},
 * {@code ALTER TABLE ADD|DROP CONSTRAINT} and {@code CREATE [UNIQUE] INDEX} are identical across Postgres, MySQL
 * and H2's grammars, so none of that belongs to any one vendor - this class routes each statement to whichever of
 * {@link ColumnParser} (column definitions), {@link ConstraintParser} (constraints, wherever declared) or
 * {@link SqlIdentifiers} (case-folding, type-name cleanup) actually owns it, rather than doing all of it itself.
 *
 * <p>The one thing that genuinely differs per dialect is how a column's type change is spelled: Postgres and H2
 * both use {@code ALTER COLUMN c TYPE t}, MySQL uses {@code MODIFY COLUMN c t}. JSqlParser tells the two apart as
 * distinct {@link AlterOperation}s but populates the column/type identically for either, so
 * {@link #isRetypeOperation} is the one hook a subclass implements to say which operation(s) its dialect spells a
 * retype with; {@link #retypeSyntax()} names that spelling for the "unsupported statement" error message. Every
 * subclass - {@code PostgresDdlParser}, {@code MySqlDdlParser}, {@code H2DdlParser} - is otherwise this same class.
 *
 * <p>Constraints and indexes must be declared as their own statements: a {@code CREATE TABLE} that carries a
 * constraint - whether written on a column ({@code id INT PRIMARY KEY}) or as a table-level clause
 * ({@code , PRIMARY KEY (id)}) - is rejected, rather than silently ignored as it once was. A constraint dbgit
 * cannot see is a constraint it cannot diff, merge or replay onto a forked branch. {@code NOT NULL} and
 * {@code DEFAULT} are exempt: they are properties of the column itself, and the model already carries them.
 */
public abstract class SqlDdlParser implements DdlParser {
    private static final String UNIQUE = "UNIQUE";

    private final ColumnParser columnParser = new ColumnParser();
    private final ConstraintParser constraintParser = new ConstraintParser();

    @Override
    public final SchemaOperation parse(String ddl) {
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

    /** Whether {@code operation} is this dialect's spelling of a column retype - e.g. {@code ALTER} for Postgres/H2, {@code MODIFY} for MySQL. */
    protected abstract boolean isRetypeOperation(AlterOperation operation);

    /** How this dialect spells a column retype, named for the "unsupported statement" error message. */
    protected abstract String retypeSyntax();

    private static Statement parseStatement(String ddl) {
        try {
            return CCJSqlParserUtil.parse(ddl);
        } catch (JSQLParserException exception) {
            throw new IllegalArgumentException("Could not parse DDL statement: " + exception.getMessage(), exception);
        }
    }

    private SchemaOperation toCreateTable(CreateTable createTable, String ddl) {
        String tableName = SqlIdentifiers.normalize(createTable.getTable().getName());
        constraintParser.rejectTableLevelConstraints(createTable, tableName);
        List<ColumnModel> columns = createTable.getColumnDefinitions().stream()
                .map(column -> columnParser.toColumnModel(column, tableName))
                .toList();
        return new SchemaOperation.CreateTable(tableName, createTable.isIfNotExists(), columns);
    }

    private SchemaOperation toAlterOperation(Alter alter, String ddl) {
        List<AlterExpression> expressions = alter.getAlterExpressions();
        if (expressions.size() != 1) {
            throw unsupported(ddl);
        }
        AlterExpression expression = expressions.get(0);
        String tableName = SqlIdentifiers.normalize(alter.getTable().getName());

        if (isRetypeOperation(expression.getOperation())) {
            return toAlterColumnType(tableName, soleColumn(expression, ddl), ddl);
        }
        return switch (expression.getOperation()) {
            case ADD -> expression.getIndex() == null
                    ? new SchemaOperation.AddColumn(tableName, columnParser.toColumnModel(soleColumn(expression, ddl), tableName))
                    : constraintParser.toAddConstraint(tableName, expression.getIndex(), ddl);
            case DROP -> expression.getConstraintName() == null
                    ? new SchemaOperation.DropColumn(tableName, SqlIdentifiers.normalize(expression.getColumnName()))
                    : new SchemaOperation.DropConstraint(tableName, SqlIdentifiers.normalize(expression.getConstraintName()));
            case RENAME -> new SchemaOperation.RenameColumn(tableName,
                    SqlIdentifiers.normalize(expression.getColumnOldName()), SqlIdentifiers.normalize(expression.getColumnName()));
            default -> throw unsupported(ddl);
        };
    }

    private SchemaOperation toCreateIndex(CreateIndex createIndex, String ddl) {
        Index index = createIndex.getIndex();
        String type = index.getType();
        if (type != null && !UNIQUE.equalsIgnoreCase(type)) {
            throw new IllegalArgumentException("Unsupported index type '" + type + "': " + ddl
                    + ". Only plain and UNIQUE indexes are modeled.");
        }
        return new SchemaOperation.CreateIndex(SqlIdentifiers.normalize(createIndex.getTable().getName()),
                SqlIdentifiers.normalize(index.getName()), UNIQUE.equalsIgnoreCase(type),
                SqlIdentifiers.normalizeAll(index.getColumnsNames()));
    }

    /**
     * Reached whenever {@link #isRetypeOperation} says so. The {@code USING <expr>} conversion clause Postgres
     * allows is accepted but discarded - the internal model only needs the resulting type. {@code SET}/
     * {@code DROP NOT NULL} and similar leave the type token empty or non-{@code USING} specs, which this rejects
     * rather than silently misreading.
     */
    private SchemaOperation toAlterColumnType(String tableName, ColumnDefinition column, String ddl) {
        List<String> specs = column.getColumnSpecs();
        String newType = column.getColDataType() == null ? null : column.getColDataType().getDataType();
        boolean hasUnsupportedSpecs = specs != null && !specs.isEmpty() && !specs.getFirst().equalsIgnoreCase("USING");
        if (hasUnsupportedSpecs || newType == null || newType.equalsIgnoreCase("SET")) {
            throw unsupported(ddl);
        }
        return new SchemaOperation.AlterColumnType(tableName, SqlIdentifiers.normalize(column.getColumnName()),
                SqlIdentifiers.normalizeType(newType));
    }

    private ColumnDefinition soleColumn(AlterExpression expression, String ddl) {
        List<AlterExpression.ColumnDataType> columns = expression.getColDataTypeList();
        if (columns == null || columns.size() != 1) {
            throw unsupported(ddl);
        }
        return columns.get(0);
    }

    private IllegalArgumentException unsupported(String statement) {
        return new IllegalArgumentException("Unsupported DDL statement: " + statement
                + ". Supported: CREATE TABLE (columns only); ALTER TABLE ADD|DROP|RENAME COLUMN;"
                + " " + retypeSyntax() + "; ALTER TABLE ADD CONSTRAINT ...; ALTER TABLE DROP CONSTRAINT;"
                + " CREATE [UNIQUE] INDEX.");
    }
}
