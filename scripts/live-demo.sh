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



