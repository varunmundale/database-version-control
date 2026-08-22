#!/usr/bin/env bash
# Demo walkthrough of dbgit's constraint and index support. Requires ./dbService to already be running.
#
# The rule this demonstrates: CREATE TABLE defines columns and nothing else. Primary keys, unique
# keys, foreign keys and indexes are added as their own statements, so dbgit can see them - and
# therefore diff them, merge them, and replay them onto a forked branch.
#
# Story:
#   - mybranch: two tables (columns only), then a primary key, a unique key, a foreign key and an index
#   - reporting: forked from mybranch, adds an index on a different column
#   - pricing:   forked from mybranch, adds a UNIQUE constraint of the SAME name over a different column
#   - dbgit diff shows constraints and indexes as their own nodes, and flags the genuine clash
set -euo pipefail
cd "$(dirname "$0")/.."

# Reset local repo state so the branches this script creates start fresh.
rm -rf .dbgit/

# main is no longer an implicit scratchpad database - it tracks a real one, named here, and the
# branches below are forked from it. Re-running this is harmless: the same target signs the same.
./dbgit init --host localhost --port 55432 --database postgres --user postgres --password postgres

echo "=== Starting on mybranch ==="
./dbgit checkout -b mybranch

echo
echo "=== mybranch: tables first - columns only, no constraints inline ==="
./dbgit add <<'EOF'
CREATE TABLE customers (
    id INT NOT NULL,
    region TEXT
);
EOF

./dbgit add <<'EOF'
CREATE TABLE orders (
    id INT NOT NULL,
    customer_id INT,
    email TEXT,
    total NUMERIC(10, 2)
);
EOF

echo
echo "=== mybranch: now the constraints, each as its own statement ==="
./dbgit add <<'EOF'
ALTER TABLE customers ADD CONSTRAINT customers_pkey PRIMARY KEY (id);
EOF

./dbgit add <<'EOF'
ALTER TABLE orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id);
EOF

./dbgit add <<'EOF'
ALTER TABLE orders ADD CONSTRAINT orders_email_key UNIQUE (email);
EOF

./dbgit add <<'EOF'
ALTER TABLE orders ADD CONSTRAINT orders_customer_fkey FOREIGN KEY (customer_id) REFERENCES customers (id);
EOF

echo
echo "=== mybranch: and an index ==="
./dbgit add <<'EOF'
CREATE INDEX idx_orders_total ON orders (total);
EOF

./dbgit commit

echo
echo "=== Fork 'reporting' from mybranch, index a different column ==="
./dbgit checkout -b reporting
./dbgit add <<'EOF'
CREATE INDEX idx_orders_customer ON orders (customer_id);
EOF
./dbgit commit

echo
echo "=== Fork 'pricing' from mybranch too, add a UNIQUE constraint of the same name over a different column ==="
./dbgit checkout mybranch
./dbgit checkout -b pricing
./dbgit add <<'EOF'
ALTER TABLE orders ADD CONSTRAINT orders_region_key UNIQUE (total);
EOF
./dbgit commit

echo
echo "=== Diff: constraints and indexes appear as their own nodes, alongside columns ==="
./dbgit diff reporting pricing

echo
echo "=== A constraint can be dropped by name, since ALTER TABLE says which table it belongs to ==="
./dbgit add <<'EOF'
ALTER TABLE orders DROP CONSTRAINT orders_region_key;
EOF
./dbgit commit

echo
echo "=== Diff again - pricing no longer carries that constraint ==="
./dbgit diff reporting pricing

echo
echo "=== An index survives a rename of the column it covers (it is held by stable id, not by name) ==="
./dbgit add <<'EOF'
ALTER TABLE orders RENAME COLUMN total TO amount;
EOF
./dbgit commit
./dbgit diff mybranch pricing
