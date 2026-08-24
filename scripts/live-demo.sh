
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

./dbgit diff other current
# merge onto other
./dbgit merge current # ----- here merge will fail because of department------

# compensating statement - a two-way schema diff alone can't tell who moved the column
./dbgit add
ALTER TABLE employees ALTER COLUMN department TYPE varchar(100);
./dbgit commit

./dbgit diff other current
# merge onto other
./dbgit merge current

########################## renames ##########################
# A column's stable id survives RENAME COLUMN, so a diff reports ONE column that moved, not a drop
# plus an add. Tables are matched by name, so renaming one does read as a drop plus an add.

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
# The index still covers the same column too - IndexModel holds stable ids, not names.

./dbgit checkout other
./dbgit merge current  # goes through: a one-sided rename is just a change the other branch lacks

# a rename racing a change to the SAME column is a genuine conflict
./dbgit add
ALTER TABLE employees RENAME COLUMN name TO full_name;
./dbgit commit

./dbgit checkout current
./dbgit add
ALTER TABLE employees ALTER COLUMN name TYPE varchar(200);
./dbgit commit

./dbgit diff other current  # (conflicting): both sides moved the same stable id, under two names.
./dbgit checkout other
./dbgit merge current  # ----- refused, because of name/full_name -----

# Compensate on other: settles the conflict (other is back where the shared history left it) and
# gives current's statement, which names 'name', a column of that name to land on when replayed.
./dbgit add
ALTER TABLE employees RENAME COLUMN full_name TO name;
./dbgit commit

./dbgit diff other current  # no longer conflicting
./dbgit merge current
./dbgit log
