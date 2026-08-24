package org.example.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads {@code dbgit.json} once from the classpath and hands out the typed sections of it. JSON rather than a flat
 * properties file because the configuration is genuinely nested - a service port, a metadata server, and the
 * scratchpad container - and dotted key prefixes were only simulating that structure.
 */
final class DbGitConfig {
    private static final JsonNode ROOT = load();

    private DbGitConfig() {
    }

    static JsonNode section(String name) {
        JsonNode section = ROOT.get(name);
        if (section == null || !section.isObject()) {
            throw new IllegalStateException("Missing required configuration section: " + name);
        }
        return section;
    }

    static String requiredText(JsonNode section, String field) {
        JsonNode value = section.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new IllegalStateException("Missing required configuration key: " + field);
        }
        return value.asText();
    }

    static int requiredInt(JsonNode section, String field) {
        JsonNode value = section.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw new IllegalStateException("Missing or non-numeric configuration key: " + field);
        }
        return value.asInt();
    }

    /** A section that need not be there at all; an empty object stands in so every field falls back to its default. */
    static JsonNode optionalSection(String name) {
        JsonNode section = ROOT.get(name);
        return section == null || !section.isObject() ? JsonNodeFactory.instance.objectNode() : section;
    }

    static String optionalText(JsonNode section, String field, String defaultValue) {
        JsonNode value = section.get(field);
        return value == null || value.asText().isBlank() ? defaultValue : value.asText();
    }

    static int optionalInt(JsonNode section, String field, int defaultValue) {
        JsonNode value = section.get(field);
        if (value == null) {
            return defaultValue;
        }
        if (!value.canConvertToInt()) {
            throw new IllegalStateException("Non-numeric configuration key: " + field);
        }
        return value.asInt();
    }

    private static JsonNode load() {
        try (InputStream input = DbGitConfig.class.getClassLoader().getResourceAsStream("dbgit.json")) {
            if (input == null) {
                throw new IllegalStateException("Missing dbgit.json on the classpath");
            }
            return new ObjectMapper().readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read dbgit.json", exception);
        }
    }
}
