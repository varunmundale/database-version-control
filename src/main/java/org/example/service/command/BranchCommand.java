package org.example.service.command;

import org.example.service.DbGitCommandResult;

import java.util.ArrayList;
import java.util.List;

/** {@code dbgit branch} - lists every known branch, marking the currently checked-out one. */
public final class BranchCommand extends Command {
    public BranchCommand(CommandContext context) {
        super(context);
    }

    @Override
    public DbGitCommandResult execute() {
        String currentBranch = context.repository().currentBranch();
        List<String> lines = new ArrayList<>();
        for (String branch : context.versioningService().branches()) {
            lines.add((branch.equals(currentBranch) ? "* " : "  ") + branch);
        }
        return print(lines);
    }
}
