CREATE TABLE employees (
                           id SERIAL PRIMARY KEY,
                           name VARCHAR(100) NOT NULL,
                           email VARCHAR(150) UNIQUE,
                           age INT,
                           salary DECIMAL(10, 2),
                           department VARCHAR(100)
);