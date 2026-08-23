
#reset head and clear all DB's
rm -rf .dbgit/
./dbgit init --host localhost --port 5433 --database postgres --user postgres --password postgres --author "varun"
./dbgit log
./dbgit branch
./dbgit checkout -b current
./dbgit add
CREATE TABLE employees (
    id SERIAL,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(150),
    age INT,
    salary DECIMAL(10, 2),
    department VARCHAR(100)
);
./dbgit commit
#Show postgres state

#make changes on other
./dbgit checkout -b other
./dbgit add
ALTER TABLE employees ADD COLUMN other_column DATE;
./dbgit commit
./dbgit log

#make changes on current
./dbgit checkout current
./dbgit add
ALTER TABLE employees ADD COLUMN current_column DATE;
./dbgit commit
./dbgit log



#Show postgres state
#merge onto current
./dbgit checkout current
./dbgit merge other
./dbgit log


# again make changes on current
./dbgit add
ALTER TABLE employees ALTER COLUMN department TYPE varchar(10);
./dbgit commit

#make changes on other
./dbgit checkout other
./dbgit add
ALTER TABLE employees ALTER COLUMN department TYPE varchar(20);
./dbgit commit

./dbgit diff other current #merge other-way round caused problems because the commit histories were LCA based. Converted this to Set-based filtering
# merge onto other
./dbgit merge current # ----- here merge will fail because of department------

#compensating statements the two-way conflict dtector is insufficient to handle this
./dbgit add
ALTER TABLE employees ALTER COLUMN department TYPE varchar(100);
./dbgit commit

./dbgit diff other current
# merge onto other
./dbgit merge current


##Reset method to resolve conflict
#./dbgit log
#./dbgit reset 3
#./dbgit merge current




########################## renames ##########################
# StableId is what makes a rename visible AS a rename: a column's id is assigned when the column is
# first created and carried through RENAME COLUMN (SchemaOperationApplier.renameColumn keeps the id
# and swaps only the name), so a diff matches the two sides by id and reports ONE column that moved -
# not a column dropped on one side and a different one added on the other. Tables get no such
# treatment: they are matched by name, so renaming a table does read as a drop plus an add.

# an index over the column that is about to be renamed
./dbgit checkout current
./dbgit add
CREATE INDEX employees_email_idx ON employees (email);
./dbgit commit

# the rename itself, on current only
./dbgit add
ALTER TABLE employees RENAME COLUMN email TO contact_email;
./dbgit commit

./dbgit diff other current  # ONE node for that column, not two, and no (conflicting): only current moved it
# The index is not reported as changed either. IndexModel holds the stable ids of the columns it
# covers rather than their names, so it still covers the same column - now called contact_email.

./dbgit checkout other
./dbgit merge current  # goes through: a one-sided rename is just a change the other branch lacks
# Show postgres state - other's employees now has contact_email, with employees_email_idx over it.
# The merge replayed current's raw statements in order, CREATE INDEX ... (email) before the rename,
# so the real database ends where the model says it does.

# a rename racing a change to the SAME column is a genuine conflict
./dbgit add
ALTER TABLE employees RENAME COLUMN name TO full_name;
./dbgit commit

./dbgit checkout current
./dbgit add
ALTER TABLE employees ALTER COLUMN name TYPE varchar(200);
./dbgit commit

./dbgit diff other current  # (conflicting): both sides moved the same stable id, under two names.
# Without the carried id these would look like two unrelated columns and the merge would happily
# produce both - which is the same reason ColumnDiff.isRename exists.
./dbgit checkout other
./dbgit merge current  # ----- refused, because of name/full_name -----

# Compensate on other, not on current: a merge replays the other branch's raw DDL, and current's
# statement names 'name', which no longer exists on other. Renaming back both settles the conflict -
# other is where the shared history left it, so only current has moved the column - and gives that
# statement a column to land on.
./dbgit add
ALTER TABLE employees RENAME COLUMN full_name TO name;
./dbgit commit

./dbgit diff other current  # no longer conflicting
./dbgit merge current
./dbgit log
