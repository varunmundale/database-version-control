package org.example.schema;

import org.example.database.H2Connector;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2SchemaParserTest {
    @Test
    void convertsH2MetadataIntoTheCommonSchemaModelWithStableIds() throws SQLException {
        try (H2Connector database = H2Connector.inMemory("schema_parser_test")) {
            database.execute("""
                    CREATE TABLE parent (
                        id INT NOT NULL,
                        code VARCHAR(20) DEFAULT 'new',
                        CONSTRAINT pk_parent PRIMARY KEY (id),
                        CONSTRAINT uq_parent_code UNIQUE (code)
                    )
                    """);
            database.execute("""
                    CREATE TABLE child (
                        id INT PRIMARY KEY,
                        parent_id INT NOT NULL,
                        CONSTRAINT fk_child_parent FOREIGN KEY (parent_id) REFERENCES parent(id)
                    )
                    """);
            database.execute("CREATE INDEX idx_child_parent ON child(parent_id)");

            DatabaseSchema firstRead = database.inspectSchema();
            DatabaseSchema secondRead = database.inspectSchema();
            TableModel parent = table(firstRead, "PARENT");
            TableModel child = table(firstRead, "CHILD");
            ColumnModel parentId = parent.columns().stream().filter(column -> column.name().equals("ID")).findFirst().orElseThrow();
            ColumnModel parentCode = parent.columns().stream().filter(column -> column.name().equals("CODE")).findFirst().orElseThrow();
            ConstraintModel foreignKey = child.constraints().stream()
                    .filter(constraint -> constraint.type() == ConstraintType.FOREIGN_KEY).findFirst().orElseThrow();

            assertEquals("h2", firstRead.dialect());
            assertEquals("PUBLIC", firstRead.schema());
            assertEquals(firstRead, secondRead);
            assertFalse(parentId.nullable());
            assertEquals("CHARACTER VARYING", parentCode.nativeType());
            assertEquals("'new'", parentCode.defaultValue());
            assertTrue(parent.constraints().stream().anyMatch(constraint -> constraint.type() == ConstraintType.PRIMARY_KEY));
            assertTrue(parent.constraints().stream().anyMatch(constraint -> constraint.type() == ConstraintType.UNIQUE));
            assertTrue(child.indexes().stream().anyMatch(index -> index.name().equals("IDX_CHILD_PARENT")));
            assertEquals(parent.id(), foreignKey.referencedTableId());
            assertEquals(parentId.id(), foreignKey.referencedColumnIds().getFirst());
            assertNotEquals(parent.id(), child.id());
        }
    }

    private static TableModel table(DatabaseSchema schema, String name) {
        return schema.tables().stream().filter(table -> table.name().equals(name)).findFirst().orElseThrow();
    }
}
