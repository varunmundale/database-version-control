package org.example.unit.service;


import org.example.service.SocketReader;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SocketReaderTest {
    @Test
    void readsTheRequestOneLineAtATime() throws Exception {
        try (SocketReader reader = read("dbgit branch\n")) {
            assertEquals("dbgit branch", reader.nextLine());
            assertNull(reader.nextLine(), "nothing follows the command line");
        }
    }

    @Test
    void leavesTheBodyUntilItIsAskedFor() throws Exception {
        try (SocketReader reader = read("dbgit add\nALTER TABLE orders\n  ADD COLUMN total INT;\n")) {
            assertEquals("dbgit add", reader.nextLine());
            assertEquals("ALTER TABLE orders" + System.lineSeparator() + "  ADD COLUMN total INT;", reader.remaining());
        }
    }

    @Test
    void remainingIsEmptyWhenTheClientSentNoBody() throws Exception {
        try (SocketReader reader = read("dbgit commit\n")) {
            reader.nextLine();
            assertEquals("", reader.remaining());
        }
    }

    @Test
    void readsUtf8BeyondAscii() throws Exception {
        try (SocketReader reader = read("dbgit add\nCOMMENT ON TABLE orders IS 'παραγγελίες';\n")) {
            assertEquals("dbgit add", reader.nextLine());
            assertEquals("COMMENT ON TABLE orders IS 'παραγγελίες';", reader.remaining());
        }
    }

    @Test
    void nextLineIsNullWhenTheClientSentNothing() throws Exception {
        try (SocketReader reader = read("")) {
            assertNull(reader.nextLine());
        }
    }

    private static SocketReader read(String request) {
        return new SocketReader(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)));
    }
}
