package org.example.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SocketWriterTest {
    @Test
    void writesAStatusLineThenTheCommandsOutput() {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        try (SocketWriter writer = new SocketWriter(response)) {
            writer.writeOk(List.of("* main", "  feature/orders"));
        }
        assertEquals(List.of("OK", "* main", "  feature/orders"), lines(response));
    }

    @Test
    void writesAStatusLineWithNoOutputAtAll() {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        try (SocketWriter writer = new SocketWriter(response)) {
            writer.writeOk(List.of());
        }
        assertEquals(List.of("OK"), lines(response));
    }

    @Test
    void writesFailuresUnderTheErrorStatus() {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        try (SocketWriter writer = new SocketWriter(response)) {
            writer.writeError("Unknown branch: nope");
        }
        assertEquals(List.of("ERR", "Unknown branch: nope"), lines(response));
    }

    private static List<String> lines(ByteArrayOutputStream response) {
        return List.of(response.toString(StandardCharsets.UTF_8).split("\\R"));
    }
}
