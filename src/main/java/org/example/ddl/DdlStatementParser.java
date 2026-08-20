package org.example.ddl;

import org.example.model.schema.ColumnModel;
import org.example.model.schema.StableId;
import org.example.model.schema.TableModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts raw {@code CREATE TABLE} / {@code ALTER TABLE ... ADD|DROP [COLUMN]} / {@code ALTER TABLE ... RENAME
 * [COLUMN] ... TO ...} statements directly into the internal {@link TableModel} representation, using the same
 * stable-id scheme as {@code AbstractJdbcSchemaParser}. Table-level constraints and indexes are not modeled; only
 * tables and columns are. Note a rename changes a column's stable id (it is derived from the column's name), so a
 * diff sees a rename as the old column disappearing and a new one appearing, not as one column changing identity.
 */
public final class DdlStatementParser {
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?\"?([\\w.]+)\"?\\s*\\((.*)\\)\\s*;?\\s*$");
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "(?is)^ALTER\\s+TABLE\\s+\"?([\\w.]+)\"?\\s+(ADD|DROP)\\s+(?:COLUMN\\s+)?\"?(\\w+)\"?\\s*(.*?)\\s*;?\\s*$");
    private static final Pattern RENAME_COLUMN = Pattern.compile(
            "(?is)^ALTER\\s+TABLE\\s+\"?([\\w.]+)\"?\\s+RENAME\\s+(?:COLUMN\\s+)?\"?(\\w+)\"?\\s+TO\\s+\"?(\\w+)\"?\\s*;?\\s*$");
    private static final Pattern TABLE_CONSTRAINT = Pattern.compile(
            "(?i)^\\s*(PRIMARY\\s+KEY|FOREIGN\\s+KEY|UNIQUE|CONSTRAINT|CHECK)\\b.*");
    private static final Pattern COLUMN_NAME = Pattern.compile("^\"?([A-Za-z_][A-Za-z0-9_]*)\"?");
    private static final Pattern MODIFIER_BOUNDARY = Pattern.compile(
            "(?i)\\b(NOT\\s+NULL|PRIMARY\\s+KEY|UNIQUE|DEFAULT|REFERENCES|CHECK)\\b");
    private static final Pattern NOT_NULL = Pattern.compile("(?i)\\bNOT\\s+NULL\\b");
    private static final Pattern PRIMARY_KEY = Pattern.compile("(?i)\\bPRIMARY\\s+KEY\\b");
    private static final Pattern DEFAULT_VALUE = Pattern.compile(
            "(?is)\\bDEFAULT\\s+(.+?)(?=\\s+(NOT\\s+NULL|PRIMARY\\s+KEY|UNIQUE|REFERENCES|CHECK)\\b|$)");

    /** The table a CREATE or ALTER statement targets. */
    public String tableName(String ddl) {
        String statement = ddl.strip();
        Matcher createMatcher = CREATE_TABLE.matcher(statement);
        if (createMatcher.matches()) {
            return bareName(createMatcher.group(2));
        }
        Matcher renameMatcher = RENAME_COLUMN.matcher(statement);
        if (renameMatcher.matches()) {
            return bareName(renameMatcher.group(1));
        }
        Matcher alterMatcher = ALTER_TABLE.matcher(statement);
        if (alterMatcher.matches()) {
            return bareName(alterMatcher.group(1));
        }
        throw unsupported(statement);
    }

    /**
     * Builds or mutates the internal representation of one table from a DDL statement.
     *
     * @param existing the table's current internal representation, or {@code null} if none exists yet
     */
    public TableModel apply(String schema, String ddl, TableModel existing) {
        String statement = ddl.strip();
        Matcher createMatcher = CREATE_TABLE.matcher(statement);
        if (createMatcher.matches()) {
            return createTable(schema, createMatcher, existing);
        }
        Matcher renameMatcher = RENAME_COLUMN.matcher(statement);
        if (renameMatcher.matches()) {
            if (existing == null) {
                throw new IllegalArgumentException("Unknown table: " + bareName(renameMatcher.group(1)));
            }
            return renameColumn(existing, renameMatcher);
        }
        Matcher alterMatcher = ALTER_TABLE.matcher(statement);
        if (alterMatcher.matches()) {
            if (existing == null) {
                throw new IllegalArgumentException("Unknown table: " + bareName(alterMatcher.group(1)));
            }
            return alterTable(existing, alterMatcher);
        }
        throw unsupported(statement);
    }

    private TableModel createTable(String schema, Matcher matcher, TableModel existing) {
        boolean ifNotExists = matcher.group(1) != null;
        String tableName = bareName(matcher.group(2));
        if (existing != null) {
            if (ifNotExists) {
                return existing;
            }
            throw new IllegalArgumentException("Table already exists: " + tableName);
        }

        StableId tableId = StableId.of("table", schema + "." + tableName);
        List<ColumnModel> columns = new ArrayList<>();
        int ordinal = 1;
        for (String definition : splitTopLevel(matcher.group(3))) {
            if (TABLE_CONSTRAINT.matcher(definition).matches()) {
                continue;
            }
            columns.add(parseColumn(tableId, definition, ordinal++));
        }
        return new TableModel(tableId, schema, tableName, columns, List.of(), List.of());
    }

    private TableModel alterTable(TableModel existing, Matcher matcher) {
        String action = matcher.group(2).toUpperCase(Locale.ROOT);
        String columnName = matcher.group(3).toLowerCase(Locale.ROOT);
        if (action.equals("ADD")) {
            if (existing.columns().stream().anyMatch(column -> column.name().equals(columnName))) {
                throw new IllegalArgumentException("Column already exists: " + columnName);
            }
            int ordinal = existing.columns().stream().mapToInt(ColumnModel::ordinal).max().orElse(0) + 1;
            ColumnModel column = parseColumn(existing.id(), columnName + " " + matcher.group(4).trim(), ordinal);
            List<ColumnModel> columns = new ArrayList<>(existing.columns());
            columns.add(column);
            return new TableModel(existing.id(), existing.schema(), existing.name(), columns, existing.indexes(), existing.constraints());
        }

        ColumnModel target = existing.columns().stream().filter(column -> column.name().equals(columnName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown column '" + columnName + "' on table '" + existing.name() + "'"));
        List<ColumnModel> columns = existing.columns().stream().filter(column -> column != target).toList();
        return new TableModel(existing.id(), existing.schema(), existing.name(), columns, existing.indexes(), existing.constraints());
    }

    private TableModel renameColumn(TableModel existing, Matcher matcher) {
        String oldName = matcher.group(2).toLowerCase(Locale.ROOT);
        String newName = matcher.group(3).toLowerCase(Locale.ROOT);
        ColumnModel target = existing.columns().stream().filter(column -> column.name().equals(oldName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown column '" + oldName + "' on table '" + existing.name() + "'"));
        if (existing.columns().stream().anyMatch(column -> column.name().equals(newName))) {
            throw new IllegalArgumentException("Column already exists: " + newName);
        }
        StableId renamedId = StableId.of("column", existing.id().value() + "." + newName);
        ColumnModel renamed = new ColumnModel(renamedId, newName, target.nativeType(), target.ordinal(), target.nullable(), target.defaultValue());
        List<ColumnModel> columns = existing.columns().stream().map(column -> column == target ? renamed : column).toList();
        return new TableModel(existing.id(), existing.schema(), existing.name(), columns, existing.indexes(), existing.constraints());
    }

    private ColumnModel parseColumn(StableId tableId, String definition, int ordinal) {
        Matcher nameMatcher = COLUMN_NAME.matcher(definition.trim());
        if (!nameMatcher.find()) {
            throw new IllegalArgumentException("Could not parse column definition: " + definition);
        }
        String name = nameMatcher.group(1).toLowerCase(Locale.ROOT);
        String rest = definition.trim().substring(nameMatcher.end()).trim();
        if (rest.isEmpty()) {
            throw new IllegalArgumentException("Column '" + name + "' is missing a type: " + definition);
        }

        boolean primaryKey = PRIMARY_KEY.matcher(rest).find();
        boolean notNull = primaryKey || NOT_NULL.matcher(rest).find();
        String defaultValue = extractDefault(rest);
        String type = extractType(rest);

        StableId columnId = StableId.of("column", tableId.value() + "." + name);
        return new ColumnModel(columnId, name, type, ordinal, !notNull, defaultValue);
    }

    private static String extractType(String rest) {
        Matcher boundary = MODIFIER_BOUNDARY.matcher(rest);
        String type = boundary.find() ? rest.substring(0, boundary.start()).trim() : rest.trim();
        if (type.isEmpty()) {
            throw new IllegalArgumentException("Could not determine a type in: " + rest);
        }
        return type;
    }

    private static String extractDefault(String rest) {
        Matcher matcher = DEFAULT_VALUE.matcher(rest);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static List<String> splitTopLevel(String body) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char character : body.toCharArray()) {
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
            }
            if (character == ',' && depth == 0) {
                parts.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (!current.toString().isBlank()) {
            parts.add(current.toString().trim());
        }
        return parts;
    }

    private static String bareName(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        String name = lastDot >= 0 ? qualifiedName.substring(lastDot + 1) : qualifiedName;
        return name.toLowerCase(Locale.ROOT);
    }

    private static IllegalArgumentException unsupported(String statement) {
        return new IllegalArgumentException(
                "Unsupported DDL statement (only CREATE TABLE and ALTER TABLE ADD|DROP|RENAME COLUMN are supported): " + statement);
    }
}
