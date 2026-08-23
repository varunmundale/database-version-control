package org.example.unit.client;


import org.example.client.ClientWorkspace;
import org.example.config.ConnectionSettings;
import org.example.protocol.RequestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientWorkspaceTest {
    @TempDir
    Path directory;

    @Test
    void aFreshWorkspaceIsOnMainAndTracksNothing() {
        ClientWorkspace workspace = new ClientWorkspace(directory);

        assertEquals(RequestContext.DEFAULT_BRANCH, workspace.currentBranch());
        assertTrue(workspace.trackedConnection("main").isEmpty());
        assertTrue(workspace.requestContext().trackedDatabaseIfConfigured().isEmpty());
    }

    /** HEAD is per-workspace now, which is what stops one user's checkout moving another's branch. */
    @Test
    void checkoutIsRememberedPerWorkspace() {
        ClientWorkspace mine = new ClientWorkspace(directory.resolve("mine"));
        ClientWorkspace yours = new ClientWorkspace(directory.resolve("yours"));

        mine.checkout("feature/orders");

        assertEquals("feature/orders", mine.currentBranch());
        assertEquals("main", yours.currentBranch());
    }

    @Test
    void theCredentialIsWrittenOnlyToTheLocalWorkspaceAndRidesOnTheRequest() throws IOException {
        ClientWorkspace workspace = new ClientWorkspace(directory);

        workspace.track("main", new ConnectionSettings("db.internal", 6543, "dbgit", "hunter2", "app_prod"));

        String localConfig = Files.readString(directory.resolve(".dbgit/config.json"));
        assertTrue(localConfig.contains("hunter2"), "the password belongs in local .dbgit state");
        assertTrue(localConfig.contains("app_prod"), localConfig);

        ConnectionSettings sent = workspace.requestContext().trackedDatabaseIfConfigured().orElseThrow();
        assertEquals("hunter2", sent.password());
        assertEquals("app_prod", sent.database());
    }

    @Test
    void theRequestNamesTheBranchTheWorkspaceIsOnAndDefaultsTheAuthorToUnknown() {
        ClientWorkspace workspace = new ClientWorkspace(directory);
        workspace.checkout("feature/orders");

        RequestContext request = workspace.requestContext();

        assertEquals("feature/orders", request.branch());
        assertEquals("unknown", request.author());
    }

    @Test
    void theRequestCarriesTheWorkspacesConfiguredAuthorOnceOneIsSet() {
        ClientWorkspace workspace = new ClientWorkspace(directory);

        workspace.trackAuthor("demo-author");

        assertEquals("demo-author", workspace.author().orElseThrow());
        assertEquals("demo-author", workspace.requestContext().author());
    }
}
