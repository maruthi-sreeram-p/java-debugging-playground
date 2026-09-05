-- Accounts. Every password below is BCrypt("Secret123!") - development credentials only.
INSERT INTO app_users (username, password_hash, display_name, role) VALUES
  ('asha',  '$2a$10$ec1T/0OoSd204tE5C6F1Punde1FHsIuEeu6hq07RZxzUkKqDzCfD.', 'Asha Kulkarni', 'USER'),
  ('ravi',  '$2a$10$ec1T/0OoSd204tE5C6F1Punde1FHsIuEeu6hq07RZxzUkKqDzCfD.', 'Ravi Sundaram', 'USER'),
  ('nadia', '$2a$10$ec1T/0OoSd204tE5C6F1Punde1FHsIuEeu6hq07RZxzUkKqDzCfD.', 'Nadia Sheikh',  'ADMIN');

INSERT INTO tickets (subject, body, status, priority, reported_by, created_at) VALUES
  ('Cannot reset my password', 'The reset email never arrives',        'OPEN',   'HIGH',   'asha',  '2026-02-10 09:05:00'),
  ('Printer offline',          'Third floor printer is unreachable',   'OPEN',   'LOW',    'asha',  '2026-02-11 10:40:00'),
  ('VPN drops every hour',     'Reconnects but drops again',           'OPEN',   'HIGH',   'ravi',  '2026-02-12 14:15:00'),
  ('Laptop replacement',       'Battery no longer holds charge',       'CLOSED', 'NORMAL', 'ravi',  '2026-02-13 16:00:00'),
  ('Access to reporting DB',   'Need read-only access for month end',  'OPEN',   'NORMAL', 'nadia', '2026-02-14 08:30:00');
