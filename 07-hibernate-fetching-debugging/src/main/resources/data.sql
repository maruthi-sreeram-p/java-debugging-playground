INSERT INTO courses (code, title, credits) VALUES
  ('CS101', 'Introduction to Programming', 4),
  ('CS201', 'Data Structures',             4),
  ('CS310', 'Databases',                   3),
  ('CS340', 'Operating Systems',           3),
  ('CS420', 'Distributed Systems',         4);

INSERT INTO modules (course_id, title, position) VALUES
  (1, 'Getting Started',        1),
  (1, 'Control Flow',           2),
  (1, 'Functions',              3),
  (2, 'Arrays and Lists',       1),
  (2, 'Trees',                  2),
  (2, 'Graphs',                 3),
  (3, 'Relational Model',       1),
  (3, 'SQL',                    2),
  (4, 'Processes',              1),
  (4, 'Memory',                 2),
  (4, 'Scheduling',             3),
  (5, 'Consensus',              1),
  (5, 'Replication',            2);

INSERT INTO lessons (module_id, title, duration_minutes) VALUES
  (1, 'Hello World',            25), (1, 'Variables',            30),
  (2, 'If and Else',            28), (2, 'Loops',                35),
  (3, 'Declaring Functions',    30), (3, 'Recursion',            45),
  (4, 'Array Basics',           30), (4, 'Linked Lists',         40),
  (5, 'Binary Trees',           45), (5, 'Balancing',            50),
  (6, 'Traversal',              40), (6, 'Shortest Paths',       55),
  (7, 'Tables and Keys',        35), (7, 'Normalisation',        45),
  (8, 'Select',                 30), (8, 'Joins',                40),
  (9, 'Process States',         30), (9, 'Context Switching',    35),
  (10, 'Paging',                40), (10, 'Virtual Memory',      45),
  (11, 'Round Robin',           30), (11, 'Priority Scheduling', 35),
  (12, 'Paxos',                 60), (12, 'Raft',                55),
  (13, 'Leader Election',       45), (13, 'Quorums',             40);

INSERT INTO students (name, email) VALUES
  ('Aarav Sharma', 'aarav.sharma@campus.edu'),
  ('Divya Nair',   'divya.nair@campus.edu'),
  ('Rohan Mehta',  'rohan.mehta@campus.edu');

INSERT INTO enrolments (student_id, course_id) VALUES
  (1, 1), (1, 2), (1, 3),
  (2, 2), (2, 5),
  (3, 1), (3, 4);
