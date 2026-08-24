package org.example.service;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Reads one client's request off a socket a piece at a time, the way {@link java.util.Scanner} does - the caller
 * asks for what it needs next, since only {@code dbgit add}'s handler knows there's a body to read.
 */
public final class SocketReader implements Closeable {
    private final BufferedReader reader;

    public SocketReader(Socket socket) throws IOException {
        this(Objects.requireNonNull(socket, "socket must not be null").getInputStream());
    }

    public SocketReader(InputStream input) {
        this.reader = new BufferedReader(
                new InputStreamReader(Objects.requireNonNull(input, "input must not be null"), StandardCharsets.UTF_8));
    }

    /** The next line, without its terminator, or {@code null} once the client has sent everything it is going to. */
    public String nextLine() throws IOException {
        return reader.readLine();
    }

    /** Everything still unread, up to the client's end of input, with the line separators between lines preserved. */
    public String remaining() throws IOException {
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!body.isEmpty()) {
                body.append(System.lineSeparator());
            }
            body.append(line);
        }
        return body.toString();
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
