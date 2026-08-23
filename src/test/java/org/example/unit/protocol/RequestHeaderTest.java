package org.example.unit.protocol;


import org.example.protocol.RequestContext;
import org.example.protocol.RequestHeader;
import org.example.config.ConnectionSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestHeaderTest {

    @Test
    void aRequestWithoutATrackedDatabaseCarriesJustTheCallerAndTheirBranch() {
        RequestContext request = new RequestContext("varun", "feature/orders", null);

        RequestContext parsed = RequestHeader.parse(RequestHeader.render(request));

        assertEquals("varun", parsed.author());
        assertEquals("feature/orders", parsed.branch());
        assertTrue(parsed.trackedDatabaseIfConfigured().isEmpty());
    }

    @Test
    void theTrackedConnectionSurvivesTheRoundTripInFull() {
        ConnectionSettings tracked = new ConnectionSettings("db.internal", 6543, "dbgit", "hunter2", "app_prod");

        RequestContext parsed = RequestHeader.parse(
                RequestHeader.render(new RequestContext("varun", "main", tracked)));

        assertEquals(tracked, parsed.trackedDatabaseIfConfigured().orElseThrow());
    }

    /** The header is split on spaces and on {@code =}, so a password containing either must survive encoding. */
    @Test
    void aPasswordContainingSpacesAndEqualsSignsSurvives() {
        ConnectionSettings awkward = new ConnectionSettings("localhost", 5432, "a user", "p a s s = w+o%rd", "app");

        String rendered = RequestHeader.render(new RequestContext("varun", "main", awkward));

        assertEquals(1, rendered.lines().count(), "the header must stay one line");
        assertEquals(awkward, RequestHeader.parse(rendered).trackedDatabaseIfConfigured().orElseThrow());
    }

    @Test
    void aBranchNameWithASlashIsNotMangled() {
        assertEquals("feature/orders-2.0",
                RequestHeader.parse(RequestHeader.render(
                        new RequestContext("varun", "feature/orders-2.0", null))).branch());
    }

    @Test
    void aRequestWithoutAHeaderIsRefusedRatherThanDefaulted() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> RequestHeader.parse("dbgit branch"));

        assertTrue(exception.getMessage().contains("upgrade your dbgit client"), exception.getMessage());
        assertTrue(exception.getMessage().contains("DBGIT/1"), exception.getMessage());
    }

    @Test
    void missingFieldsFallBackRatherThanFailing() {
        RequestContext parsed = RequestHeader.parse("DBGIT/1");

        assertEquals("unknown", parsed.author());
        assertEquals(RequestContext.DEFAULT_BRANCH, parsed.branch());
    }

    @Test
    void aMalformedFieldIsNamed() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> RequestHeader.parse("DBGIT/1 author=varun branch"))
                .getMessage().contains("branch"));
    }
}
