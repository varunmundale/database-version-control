package org.example.unit.models.schema;


import org.example.models.schema.ColumnModel;
import org.example.models.schema.StableId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ColumnModelTest {

    @Test
    void columnsWithTheSameTypeNullabilityAndDefaultHaveTheSameDefinitionRegardlessOfNameOrId() {
        ColumnModel a = column("a", "NUMERIC(10,2)", true, "0");
        ColumnModel b = column("b", "NUMERIC(10,2)", true, "0");

        assertTrue(a.sameDefinitionAs(b));
    }

    @Test
    void aDifferentTypeNullabilityOrDefaultMakesTheDefinitionDifferent() {
        ColumnModel base = column("a", "NUMERIC(10,2)", true, "0");

        assertFalse(base.sameDefinitionAs(column("a", "BIGINT", true, "0")));
        assertFalse(base.sameDefinitionAs(column("a", "NUMERIC(10,2)", false, "0")));
        assertFalse(base.sameDefinitionAs(column("a", "NUMERIC(10,2)", true, "1")));
    }

    @Test
    void definitionRendersTypeThenNotNullThenDefaultOnlyWhenPresent() {
        assertEquals("TEXT", column("a", "TEXT", true, null).definition());
        assertEquals("TEXT NOT NULL", column("a", "TEXT", false, null).definition());
        assertEquals("NUMERIC(10,2) NOT NULL DEFAULT 0", column("a", "NUMERIC(10,2)", false, "0").definition());
    }

    @Test
    void aDifferentGeneratedAsSpecMakesTheDefinitionDifferent() {
        ColumnModel plain = column("a", "INT", false, null);
        ColumnModel autoIncrement = columnWithGeneratedAs("a", "INT", false, null, "AUTO_INCREMENT");
        ColumnModel identity = columnWithGeneratedAs("a", "INT", false, null, "GENERATED ALWAYS AS IDENTITY");

        assertFalse(plain.sameDefinitionAs(autoIncrement));
        assertFalse(autoIncrement.sameDefinitionAs(identity));
        assertTrue(autoIncrement.sameDefinitionAs(columnWithGeneratedAs("b", "INT", false, null, "AUTO_INCREMENT")));
    }

    @Test
    void definitionRendersTheGeneratedAsSpecLast() {
        assertEquals("INT NOT NULL AUTO_INCREMENT",
                columnWithGeneratedAs("a", "INT", false, null, "AUTO_INCREMENT").definition());
    }

    @Test
    void differsFromIsFalseOnlyWhenNameAndDefinitionBothMatch() {
        ColumnModel base = column("a", "NUMERIC(10,2)", true, "0");

        assertFalse(base.differsFrom(column("a", "NUMERIC(10,2)", true, "0")));
        assertTrue(base.differsFrom(column("b", "NUMERIC(10,2)", true, "0")));
        assertTrue(base.differsFrom(column("a", "BIGINT", true, "0")));
    }

    private static ColumnModel column(String name, String type, boolean nullable, String defaultValue) {
        return columnWithGeneratedAs(name, type, nullable, defaultValue, null);
    }

    private static ColumnModel columnWithGeneratedAs(String name, String type, boolean nullable, String defaultValue, String generatedAs) {
        return new ColumnModel(StableId.of("column", "public.orders." + name), name, type, nullable, defaultValue, generatedAs);
    }
}
