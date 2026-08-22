package org.example.core.forker.docker;

import org.example.config.BranchDatabaseConfig;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * What it takes to bring up one dialect's server as a Docker container: a label for log/error text, the port it
 * listens on inside the container, and the image's own env vars for the admin user, password and database -
 * different per vendor (e.g. {@code POSTGRES_USER} vs {@code MYSQL_USER}). A dialect with no server to start at all
 * (H2 - in-memory, inside the daemon's own JVM) simply has no entry in {@link #builtins()}, which is what lets
 * {@link SharedContainer} skip it by absence rather than a hardcoded dialect check.
 */
public record ContainerSpec(String label, int containerPort, Function<BranchDatabaseConfig, List<String>> envArgs) {
    public static Map<String, ContainerSpec> builtins() {
        return Map.of(
                "postgresql", new ContainerSpec("PostgreSQL", 5432, config -> List.of(
                        "--env", "POSTGRES_USER=" + config.user(),
                        "--env", "POSTGRES_PASSWORD=" + config.password(),
                        "--env", "POSTGRES_DB=" + config.adminDatabase())),
                "mysql", new ContainerSpec("MySQL", 3306, config -> List.of(
                        // The official image refuses to start without one of these; the configured user is not
                        // necessarily root, so both the root password and a dedicated user/database are set.
                        "--env", "MYSQL_ROOT_PASSWORD=" + config.password(),
                        "--env", "MYSQL_USER=" + config.user(),
                        "--env", "MYSQL_PASSWORD=" + config.password(),
                        "--env", "MYSQL_DATABASE=" + config.adminDatabase())));
    }
}
