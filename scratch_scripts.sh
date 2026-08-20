#!/usr/bin/env bash
# Demo walkthrough of the dbgit workflow. Requires ./dbService to already be running.
set -euo pipefail
cd "$(dirname "$0")"

# Reset local repo state so the branches this script creates start fresh.
rm -rf .dbgit/

./dbgit checkout -b mybranch

./dbgit add <<'EOF'
CREATE TABLE employees (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(150) UNIQUE,
    age INT,
    salary DECIMAL(10, 2),
    department VARCHAR(100)
);
EOF

./dbgit commit

./dbgit checkout -b mybranch2

./dbgit add <<'EOF'
ALTER TABLE employees ADD COLUMN hire_date DATE;
EOF

./dbgit commit

./dbgit checkout mybranch

./dbgit add <<'EOF'
ALTER TABLE employees ADD COLUMN end_date DATE;
EOF

./dbgit commit

./dbgit diff mybranch mybranch2
