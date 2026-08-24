package org.example.unit.service.command;

import org.example.service.command.Command;
import org.example.service.command.CommandUsage;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every concrete {@link Command} must carry a {@code public static final CommandUsage USAGE} field. Java can't
 * enforce an abstract static member at compile time, so this test enforces it instead.
 */
class CommandUsageTest {
    private static final String COMMAND_PACKAGE = "org.example.service.command";

    @Test
    void everyConcreteCommandDeclaresAUsageConstant() throws Exception {
        List<String> missing = new ArrayList<>();
        for (Class<?> type : commandClasses()) {
            boolean isConcreteCommand = Command.class.isAssignableFrom(type) && type != Command.class
                    && !Modifier.isAbstract(type.getModifiers());
            if (isConcreteCommand && !hasUsageConstant(type)) {
                missing.add(type.getSimpleName());
            }
        }
        assertTrue(missing.isEmpty(), "Missing 'public static final CommandUsage USAGE' on: " + missing);
    }

    private static boolean hasUsageConstant(Class<?> type) {
        try {
            Field field = type.getDeclaredField("USAGE");
            int modifiers = field.getModifiers();
            return field.getType() == CommandUsage.class && Modifier.isPublic(modifiers)
                    && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers);
        } catch (NoSuchFieldException exception) {
            return false;
        }
    }

    /** Walks the compiled directory rather than a hand-maintained list, so a new command is picked up automatically. */
    private static List<Class<?>> commandClasses() throws Exception {
        URL packageUrl = Thread.currentThread().getContextClassLoader().getResource(COMMAND_PACKAGE.replace('.', '/'));
        assertNotNull(packageUrl, "Expected " + COMMAND_PACKAGE + " to be on the test classpath.");
        File directory = new File(packageUrl.toURI());
        File[] classFiles = directory.listFiles((dir, name) -> name.endsWith(".class"));
        assertNotNull(classFiles, directory + " is not a directory of compiled classes.");

        List<Class<?>> classes = new ArrayList<>();
        for (File file : classFiles) {
            String simpleName = file.getName().substring(0, file.getName().length() - ".class".length());
            if (!simpleName.contains("$")) {
                classes.add(Class.forName(COMMAND_PACKAGE + "." + simpleName));
            }
        }
        return classes;
    }
}
