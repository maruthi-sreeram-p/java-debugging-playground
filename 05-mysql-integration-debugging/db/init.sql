-- Schema and seed data for the Employee Directory.
-- This script is executed once, by the MySQL container, on first start.

CREATE TABLE IF NOT EXISTS employees (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    first_name      VARCHAR(60)  NOT NULL,
    last_name       VARCHAR(60)  NOT NULL,
    email           VARCHAR(120) NOT NULL,
    department      VARCHAR(60)  NOT NULL,
    designation     VARCHAR(80),
    salary          DECIMAL(12,2),
    joining_date    DATE,
    active          BIT(1)       NOT NULL DEFAULT b'1',
    PRIMARY KEY (id),
    UNIQUE KEY uk_employees_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO employees (first_name, last_name, email, department, designation, salary, joining_date, active) VALUES
  ('Aarav',  'Sharma', 'aarav.sharma@company.com',  'Engineering', 'Senior Engineer',   1850000.00, '2021-06-14', b'1'),
  ('Divya',  'Nair',   'divya.nair@company.com',    'Engineering', 'Engineer',          1250000.00, '2023-01-09', b'1'),
  ('Rohan',  'Mehta',  'rohan.mehta@company.com',   'Engineering', 'Principal Engineer',2600000.00, '2019-03-25', b'1'),
  ('Priya',  'Iyer',   'priya.iyer@company.com',    'Finance',     'Financial Analyst',  980000.00, '2022-08-01', b'1'),
  ('Kabir',  'Khanna', 'kabir.khanna@company.com',  'Finance',     'Controller',        1750000.00, '2018-11-12', b'1'),
  ('Meera',  'Rao',    'meera.rao@company.com',     'People',      'HR Business Partner',1100000.00,'2022-02-21', b'1'),
  ('Vikram', 'Bose',   'vikram.bose@company.com',   'People',      'Recruiter',          760000.00, '2024-05-06', b'1'),
  ('Ananya', 'Desai',  'ananya.desai@company.com',  'Sales',       'Account Executive', 1400000.00, '2023-09-18', b'0');
