package org.example.service.command;

import org.example.core.log.HistoryLog;
import org.example.core.log.HistoryLogFormatter;
import org.example.service.DbGitCommandResult;

/**
 * {@code dbgit log} - the current branch's commits, newest first, each with who made it, when, why and which
 * changesets it folded in, preceded by whatever is staged but not committed yet.
 */
public final class LogCommand extends Command {
    private final HistoryLog historyLog;
    private final HistoryLogFormatter formatter = new HistoryLogFormatter();

    public LogCommand(CommandContext context) {
        super(context);
        this.historyLog = new HistoryLog(context.versioningService());
    }

    @Override
    public DbGitCommandResult execute() {
        return print(formatter.format(historyLog.of(context.repository().currentBranch())));
    }
}
