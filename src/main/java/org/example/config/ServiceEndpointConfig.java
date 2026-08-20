package org.example.config;

/** Configuration shared by the {@code dbService} daemon and the {@code dbgit} client so they agree on the service port. */
public final class ServiceEndpointConfig {
    private static final ServiceEndpointConfig INSTANCE =
            new ServiceEndpointConfig(Integer.parseInt(DbGitProperties.required("service.port")));

    private final int port;

    private ServiceEndpointConfig(int port) {
        this.port = port;
    }

    public static ServiceEndpointConfig getInstance() {
        return INSTANCE;
    }

    public int port() {
        return port;
    }
}
