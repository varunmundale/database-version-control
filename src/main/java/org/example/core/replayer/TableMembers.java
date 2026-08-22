package org.example.core.replayer;

import org.example.models.schema.SchemaElement;
import org.example.models.schema.StableId;
import org.example.models.schema.TableModel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * One kind of member of one table - its columns, its constraints or its indexes - as something a
 * {@link SchemaOperation} can look up by name and rewrite. All three kinds are edited identically, so this is
 * written once over {@link SchemaElement} rather than three times over the concrete models.
 *
 * <p>Every mutator returns the whole rewritten {@link TableModel} rather than a new member list, so the caller
 * never has to remember to fold the result back into its table. Each also carries the rule that goes with it -
 * you cannot add a member whose name is taken, or remove one that is not there - which is why the operations in
 * {@link SchemaOperationApplier} are single expressions with no separate guard step.
 *
 * @param <S> the kind of member being edited
 */
final class TableMembers<S extends SchemaElement<S>> {
    private final String kind;
    private final String tableName;
    private final List<S> members;
    private final Function<List<S>, TableModel> rewrite;

    TableMembers(String kind, String tableName, List<S> members, Function<List<S>, TableModel> rewrite) {
        this.kind = kind;
        this.tableName = tableName;
        this.members = members;
        this.rewrite = rewrite;
    }

    /** The member DDL called {@code name}, or a failure naming the table it was looked for on. */
    S require(String name) {
        return members.stream().filter(member -> member.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown " + kind + " '" + name + "' on table '" + tableName + "'"));
    }

    private void requireAbsent(String name) {
        if (members.stream().anyMatch(member -> member.name().equals(name))) {
            throw new IllegalArgumentException(capitalizedKind() + " already exists: " + name);
        }
    }

    /**
     * Resolves the names a constraint or index covers to the stable ids of the members they name - which is what
     * gets stored, so that covering a column survives a later {@code RENAME COLUMN} of it.
     */
    List<StableId> idsOf(List<String> names) {
        return names.stream().map(name -> require(name).id()).toList();
    }

    TableModel add(S member) {
        requireAbsent(member.name());
        List<S> updated = new ArrayList<>(members);
        updated.add(member);
        return rewrite.apply(updated);
    }

    TableModel remove(String name) {
        S target = require(name);
        return rewrite.apply(members.stream().filter(member -> member != target).toList());
    }

    /**
     * Swaps one member for a rewritten version of itself, keeping its position. The replacement's name is checked
     * for a clash only when it actually changed, so renaming into a taken name fails while rewriting a member
     * under its own name does not.
     */
    TableModel replace(S target, S replacement) {
        if (!replacement.name().equals(target.name())) {
            requireAbsent(replacement.name());
        }
        return rewrite.apply(members.stream().map(member -> member == target ? replacement : member).toList());
    }

    private String capitalizedKind() {
        return Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
    }
}
