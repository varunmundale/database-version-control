package org.example.core.log;

import org.example.core.versioning.VersioningService;
import org.example.models.versioning.CommitEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Reads a branch's history out of the {@link VersioningService} in the order a log wants it: the commits reversed
 * to newest-first, plus whatever is still uncommitted. Reversal is all this adds - the ancestry walk itself belongs
 * to the versioning service, so the log shows exactly the history a replay would use, just the other way round.
 */
public final class HistoryLog {
    private final VersioningService versioningService;

    public HistoryLog(VersioningService versioningService) {
        this.versioningService = Objects.requireNonNull(versioningService, "versioningService must not be null");
    }

    public BranchLog of(String branch) {
        List<CommitEntry> commits = new ArrayList<>(versioningService.commits(branch));
        Collections.reverse(commits);
        return new BranchLog(branch, versioningService.workingSet(branch), commits);
    }
}
