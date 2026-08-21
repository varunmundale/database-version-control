package org.example.service;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * Writes one response back to a client: a status line, then the command's output. The other half of
 * {@link SocketReader}, and the only place the {@code OK}/{@code ERR} wire format is spelled out - the client's
 * {@code DbGitClient} reads exactly this shape back.
 */
public final class SocketWriter implements Closeable {
    private static final String OK = "OK";
    private static final String ERROR = "ERR";

    private final PrintWriter writer;

    public SocketWriter(Socket socket) throws IOException {
        this(Objects.requireNonNull(socket, "socket must not be null").getOutputStream());
    }

    public SocketWriter(OutputStream output) {
        this.writer = new PrintWriter(Objects.requireNonNull(output, "output must not be null"), true, StandardCharsets.UTF_8);
    }

    public void writeOk(List<String> lines) {
        writer.println(OK);
        lines.forEach(writer::println);
    }

    public void writeError(String message) {
        writer.println(ERROR);
        writer.println(message);
    }

    @Override
    public void close() {
        writer.close();
    }
}
