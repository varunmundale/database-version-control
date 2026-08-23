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
 * Every concrete {@link Command} is expected to carry its own documentation - a {@code public static final
 * CommandUsage USAGE} field, the same way every one implements {@link Command#execute()}. Java has no such thing
 * as an abstract static member, so unlike {@code execute()} that contract can't be enforced by the compiler; this
 * test is what actually enforces it. Add a new {@code Command} subclass without a {@code USAGE} constant and this
 * fails, rather than {@code dbgit help} silently omitting it.
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

    /**
     * Every {@code .class} file the compiler emitted for {@link #COMMAND_PACKAGE}, found by walking the compiled
     * directory rather than a hand-maintained list of classes - so a newly added command is picked up automatically
     * instead of silently escaping this check.
     */
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
