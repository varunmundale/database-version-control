package org.example.unit.core.forker.docker;

import org.example.config.BranchDatabaseConfig;
import org.example.core.forker.docker.ContainerSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What {@link SharedContainer} looks up per dialect to build its {@code docker run} command. Uses the real,
 * shared {@link BranchDatabaseConfig#getInstance()} purely as a source of arbitrary user/password/adminDatabase
 * strings to format - {@code envArgs} doesn't know or care what dialect the config it's handed actually claims to
 * be, so this is exactly as valid a way to verify the mysql entry's env-var names as a mysql-flavored config would
 * be, without needing one (which would collide with ForkerTest/DbGitCommandsTest - see dbgit.json's own comment).
 */
class ContainerSpecTest {
    private static final BranchDatabaseConfig CONFIG = BranchDatabaseConfig.getInstance();
    private static final Map<String, ContainerSpec> SPECS = ContainerSpec.builtins();

    @Test
    void postgresqlSpecUsesThePostgresImageEnvVarNamesAndDefaultPort() {
        ContainerSpec postgres = SPECS.get("postgresql");

        assertEquals("PostgreSQL", postgres.label());
        assertEquals(5432, postgres.containerPort());
        assertEquals(List.of(
                "--env", "POSTGRES_USER=" + CONFIG.user(),
                "--env", "POSTGRES_PASSWORD=" + CONFIG.password(),
                "--env", "POSTGRES_DB=" + CONFIG.adminDatabase()
        ), postgres.envArgs().apply(CONFIG));
    }

    @Test
    void mysqlSpecUsesTheMySqlImageEnvVarNamesAndDefaultPort() {
        ContainerSpec mysql = SPECS.get("mysql");

        assertEquals("MySQL", mysql.label());
        assertEquals(3306, mysql.containerPort());
        assertEquals(List.of(
                "--env", "MYSQL_ROOT_PASSWORD=" + CONFIG.password(),
                "--env", "MYSQL_USER=" + CONFIG.user(),
                "--env", "MYSQL_PASSWORD=" + CONFIG.password(),
                "--env", "MYSQL_DATABASE=" + CONFIG.adminDatabase()
        ), mysql.envArgs().apply(CONFIG));
    }

    /** H2 is in-memory, inside the daemon's own JVM - nothing to start, so it has no spec at all. */
    @Test
    void h2HasNoContainerSpec() {
        assertNull(SPECS.get("h2"));
        assertFalse(SPECS.containsKey("h2"));
    }
}
