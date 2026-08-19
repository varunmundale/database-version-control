CREATE TABLE employees (
                           id SERIAL PRIMARY KEY,
                           name VARCHAR(100) NOT NULL,
                           email VARCHAR(150) UNIQUE,
                           age INT,
                           salary DECIMAL(10, 2),
                           department VARCHAR(100)
);

ALTER TABLE employees ADD COLUMN hire_date DATE;
ALTER TABLE employees ADD COLUMN end_date DATE;

ALTER TABLE employees DROP COLUMN age;

ALTER TABLE employees RENAME COLUMN department TO department1;

ALTER TABLE employees DROP COLUMN department;