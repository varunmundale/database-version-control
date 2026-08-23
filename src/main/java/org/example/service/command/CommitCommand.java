package org.example.service.command;

import org.example.core.committer.Committer;
import org.example.core.committer.CommitResult;
import org.example.models.versioning.CommitMetadata;
import org.example.service.DbGitCommandResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code dbgit commit [-m <message>] [--author <name>]} - delegates folding the current branch's APPLIED
 * changesets into one new commit to {@link Committer}, attributing it to whoever asked for it.
 *
 * <p>The message is deliberately the rest of the line rather than a single argument: a command line arrives here
 * already split on whitespace, so the quotes a shell strips from {@code -m "add total column"} are long gone by
 * the time the daemon sees it.
 */
public final class CommitCommand extends Command {
    public static final CommandUsage USAGE = new CommandUsage("commit",
            "dbgit commit [-m <message>] [--author <name>]",
            "Folds the current branch's applied changesets into one new commit, chained onto the branch's HEAD. "
                    + "--author overrides the default author (whoever the daemon runs as).");

    private final Committer committer;
    private final List<String> arguments;

    public CommitCommand(CommandContext context, List<String> arguments) {
        super(context);
        this.committer = new Committer(context.versioningService(), context.locks());
        this.arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments must not be null"));
    }

    @Override
    public DbGitCommandResult execute() {
        String branch = context.branch();
        CommitResult result = committer.commit(branch, parse());
        return switch (result) {
            case CommitResult.NothingToCommit ignored -> print(List.of("Nothing to commit for branch '" + branch + "'."));
            case CommitResult.Success success -> print(List.of("Created commit #" + success.commitId() + " for branch '"
                    + branch + "' with " + success.changesetCount() + " changeset(s)."));
        };
    }

    private CommitMetadata parse() {
        String author = null;
        List<String> message = new ArrayList<>();
        for (int index = 0; index < arguments.size(); index++) {
            switch (arguments.get(index)) {
                case "-m", "--message" -> {
                    while (index + 1 < arguments.size() && !isFlag(arguments.get(index + 1))) {
                        message.add(arguments.get(++index));
                    }
                }
                case "--author" -> author = value("--author", ++index);
                default -> throw new IllegalArgumentException("Unknown option '" + arguments.get(index) + "'. "
                        + "Usage: dbgit commit [-m <message>] [--author <name>]");
            }
        }
        return CommitMetadata.by(author, String.join(" ", message));
    }

    private static boolean isFlag(String argument) {
        return argument.equals("-m") || argument.equals("--message") || argument.equals("--author");
    }

    private String value(String flag, int index) {
        if (index >= arguments.size()) {
            throw new IllegalArgumentException(flag + " needs a value. Usage: dbgit commit [-m <message>] [--author <name>]");
        }
        return arguments.get(index);
    }
}
