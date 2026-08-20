package org.example.service.command;

import org.example.service.DbGitCommandResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** {@code dbgit branch} - lists every known branch, marking the currently checked-out one. */
public final class BranchCommand extends Command {
    public BranchCommand(CommandContext context) {
        super(context);
    }

    @Override
    public DbGitCommandResult execute() throws IOException {
        String currentBranch = context.repository().currentBranch();
        List<String> lines = new ArrayList<>();
        for (String branch : context.branchFork().metadataStore().branches()) {
            lines.add((branch.equals(currentBranch) ? "* " : "  ") + branch);
        }
        return print(lines);
    }
}
