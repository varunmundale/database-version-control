#!/usr/bin/env bash
# Demo walkthrough of the dbgit workflow. Requires ./dbService to already be running.
set -euo pipefail
cd "$(dirname "$0")/.."

# Reset local repo state so the branches this script creates start fresh.
rm -rf .dbgit/

# main is no longer an implicit scratchpad database - it tracks a real one, named here. Re-running
# this is harmless: the same target signs the same, so init is idempotent.
./dbgit init --host localhost --port 5543 --database postgres --user postgres --password postgres --author "scratch-demo"

./dbgit checkout -b mybranch


EOF

# CREATE TABLE defines columns only; constraints are their own statements.
./dbgit add <<'EOF'
ALTER TABLE employees ADD CONSTRAINT employees_pkey PRIMARY KEY (id);
EOF
./dbgit add <<'EOF'
ALTER TABLE employees ADD CONSTRAINT employees_email_key UNIQUE (email);
EOF

./dbgit commit

./dbgit checkout -b mybranch2

./dbgit add <<'EOF'
ALTER TABLE employees ADD COLUMN hire_date DATE;
EOF
./dbgit add <<'EOF'
ALTER TABLE employees ALTER COLUMN department TYPE integer USING department::integer
EOF
./dbgit add <<'EOF'
ALTER TABLE employees DROP COLUMN salary;
EOF
./dbgit add <<'EOF'
ALTER TABLE employees ADD COLUMN salary DECIMAL(10, 2);
EOF
./dbgit commit

./dbgit checkout mybranch

./dbgit add <<'EOF'
ALTER TABLE employees ADD COLUMN end_date DATE;
EOF
./dbgit add <<'EOF'
ALTER TABLE employees DROP COLUMN age;
EOF
./dbgit add <<'EOF'
ALTER TABLE employees RENAME COLUMN department TO department1;
EOF
./dbgit add <<'EOF'
ALTER TABLE employees DROP COLUMN salary;
EOF
./dbgit add <<'EOF'
ALTER TABLE employees ADD COLUMN salary DECIMAL(10, 2);
EOF
./dbgit add <<'EOF'
ALTER TABLE employees DROP COLUMN salary;
EOF
./dbgit add <<'EOF'
ALTER TABLE employees ADD COLUMN salary DECIMAL(10, 2);
EOF
./dbgit commit

./dbgit diff mybranch mybranch2


#reset head and clear all DB's
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
#make changes on current
./dbgit add
ALTER TABLE employees ADD COLUMN current_column DATE;
./dbgit commit
./dbgit log

#make changes on other
./dbgit checkout -b other
./dbgit add
ALTER TABLE employees ADD COLUMN other_column DATE;
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

##compensating statements the two-way conflict dtector is insufficient to handle this
#./dbgit add
#ALTER TABLE employees ALTER COLUMN department TYPE varchar(100);
#./dbgit commit
#
#./dbgit diff other current
## merge onto other
#./dbgit merge current


#Reset method to resolve conflict
./dbgit log
./dbgit reset 3
./dbgit merge current



