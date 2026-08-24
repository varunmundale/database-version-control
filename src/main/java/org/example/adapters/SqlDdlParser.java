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
import java.util.Objects;

/**
 * Parses DDL for one SQL dialect using <a href="https://github.com/JSQLParser/JSqlParser">JSqlParser</a> for the
 * grammar, routing each statement to whichever of {@link ColumnMapper}, {@link ConstraintMapper} or
 * {@link SqlIdentifiers} owns that piece. Per-dialect vocabulary (retype syntax, identity spec) is supplied via
 * {@link DialectGrammar} rather than subclassing, since only the vocabulary differs across dialects, never the
 * parsing logic. See CLAUDE.md for what's accepted and why (inline constraints, {@code IF EXISTS}, {@code CASCADE}
 * are all rejected here rather than reaching the database).
 */
public final class SqlDdlParser implements DdlParser {
    private static final String UNIQUE = "UNIQUE";

    private final ColumnMapper columnMapper = new ColumnMapper();
    private final ConstraintMapper constraintMapper = new ConstraintMapper();
    private final DialectGrammar grammar;

    public SqlDdlParser(DialectGrammar grammar) {
        this.grammar = Objects.requireNonNull(grammar, "grammar must not be null");
    }

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
        if (statement instanceof Drop drop) {
            return toDrop(drop, ddl);
        }
        throw unsupported(ddl);
    }

    private SchemaOperation toDrop(Drop drop, String ddl) {
        if ("INDEX".equalsIgnoreCase(drop.getType())) {
            throw new IllegalArgumentException("DROP INDEX is not supported: " + ddl
                    + ". A dropped index names no table, and dbgit replays history one table at a time, so it cannot"
                    + " tell which table the index belonged to. Drop the constraint that owns it instead, e.g."
                    + " ALTER TABLE <table> DROP CONSTRAINT <name>.");
        }
        if (!"TABLE".equalsIgnoreCase(drop.getType())) {
            throw unsupported(ddl);
        }
        if (drop.isIfExists()) {
            throw new IllegalArgumentException("DROP TABLE IF EXISTS is not supported: " + ddl
                    + ". A statement must mean the same thing every time dbgit replays it, and IF EXISTS means one"
                    + " thing on a branch that has the table and another on one that doesn't. Write DROP TABLE"
                    + " <table>.");
        }
        if (drop.isUsingTemporary()) {
            throw new IllegalArgumentException("DROP TEMPORARY TABLE is not supported: " + ddl
                    + ". A temporary table is not part of a versioned schema.");
        }
        List<String> parameters = drop.getParameters();
        if (parameters != null && !parameters.isEmpty()) {
            throw new IllegalArgumentException("DROP TABLE " + String.join(" ", parameters) + " is not supported: "
                    + ddl + ". CASCADE can drop constraints on other tables that replay never sees and dbgit diff"
                    + " can never report. Drop the referencing constraint first, e.g."
                    + " ALTER TABLE <other table> DROP CONSTRAINT <name>.");
        }
        return new SchemaOperation.DropTable(SqlIdentifiers.normalize(drop.getName().getName()));
    }

    private static Statement parseStatement(String ddl) {
        try {
            return CCJSqlParserUtil.parse(ddl);
        } catch (JSQLParserException exception) {
            throw new IllegalArgumentException("Could not parse DDL statement: " + exception.getMessage(), exception);
        }
    }

    private SchemaOperation toCreateTable(CreateTable createTable, String ddl) {
        if (createTable.isIfNotExists()) {
            throw new IllegalArgumentException("CREATE TABLE IF NOT EXISTS is not supported: " + ddl
                    + ". A statement must mean the same thing every time dbgit replays it, and IF NOT EXISTS means"
                    + " one thing on a branch that already has the table and another on one that doesn't. Write"
                    + " CREATE TABLE <table> (...).");
        }
        String tableName = SqlIdentifiers.normalize(createTable.getTable().getName());
        constraintMapper.rejectTableLevelConstraints(createTable, tableName);
        List<ColumnModel> columns = createTable.getColumnDefinitions().stream()
                .map(column -> columnMapper.toColumnModel(column, tableName, grammar.identitySpecs()))
                .toList();
        return new SchemaOperation.CreateTable(tableName, columns);
    }

    private SchemaOperation toAlterOperation(Alter alter, String ddl) {
        if (alter.isUseTableIfExists()) {
            throw new IllegalArgumentException("ALTER TABLE IF EXISTS is not supported: " + ddl
                    + ". A statement must mean the same thing every time dbgit replays it, and IF EXISTS means one"
                    + " thing on a branch that has the table and another on one that doesn't. Write ALTER TABLE"
                    + " <table> ...");
        }
        List<AlterExpression> expressions = alter.getAlterExpressions();
        if (expressions.size() != 1) {
            throw unsupported(ddl);
        }
        AlterExpression expression = expressions.get(0);
        String tableName = SqlIdentifiers.normalize(alter.getTable().getName());

        if (expression.getOperation() == AlterOperation.RENAME_TABLE) {
            return new SchemaOperation.RenameTable(tableName, SqlIdentifiers.normalize(expression.getNewTableName()));
        }
        if (grammar.isRetypeOperation(expression.getOperation())) {
            return toAlterColumnType(tableName, soleColumn(expression, ddl), ddl);
        }
        return switch (expression.getOperation()) {
            case ADD -> expression.getIndex() == null
                    ? new SchemaOperation.AddColumn(tableName, columnMapper.toColumnModel(soleColumn(expression, ddl), tableName, grammar.identitySpecs()))
                    : constraintMapper.toAddConstraint(tableName, expression.getIndex(), ddl);
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

    /** A trailing {@code USING <expr>} conversion clause is accepted and discarded; anything else unrecognized is rejected. */
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
                + ". Supported: CREATE TABLE (columns only); DROP TABLE; ALTER TABLE ADD|DROP|RENAME COLUMN;"
                + " " + grammar.retypeSyntax() + "; ALTER TABLE ADD CONSTRAINT ...; ALTER TABLE DROP CONSTRAINT;"
                + " CREATE [UNIQUE] INDEX; ALTER TABLE <table> RENAME TO <table> (RENAME TABLE ... TO ... is not"
                + " supported - write it as ALTER TABLE <table> RENAME TO <table>).");
    }
}
