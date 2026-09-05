-- Roles
INSERT INTO roles (name) VALUES ('USER'), ('MANAGER'), ('ADMIN');

-- Accounts. Every password below is BCrypt("Secret123!") - development credentials only.
INSERT INTO app_users (username, password_hash, display_name, enabled) VALUES
  ('alice', '$2a$10$ec1T/0OoSd204tE5C6F1Punde1FHsIuEeu6hq07RZxzUkKqDzCfD.', 'Alice Fernandes', true),
  ('bob',   '$2a$10$ec1T/0OoSd204tE5C6F1Punde1FHsIuEeu6hq07RZxzUkKqDzCfD.', 'Bob Dsouza',      true),
  ('carol', '$2a$10$ec1T/0OoSd204tE5C6F1Punde1FHsIuEeu6hq07RZxzUkKqDzCfD.', 'Carol Menon',     true),
  ('dave',  '$2a$10$ec1T/0OoSd204tE5C6F1Punde1FHsIuEeu6hq07RZxzUkKqDzCfD.', 'Dave Pillai',     true);

-- alice and bob are plain users, carol manages, dave administers
INSERT INTO user_roles (user_id, role_id) VALUES
  (1, 1),
  (2, 1),
  (3, 2),
  (4, 3);

INSERT INTO tasks (title, description, status, assignee, created_by, created_at) VALUES
  ('Rotate API keys',       'Quarterly credential rotation', 'ASSIGNED', 'alice', 'carol', '2026-02-01 09:00:00'),
  ('Upgrade MySQL',         'Move to 8.0.36',                'OPEN',     NULL,    'carol', '2026-02-02 11:20:00'),
  ('Write onboarding docs', 'For the new joiners',           'ASSIGNED', 'bob',   'carol', '2026-02-03 15:45:00'),
  ('Review access list',    'Half-yearly audit',             'OPEN',     NULL,    'dave',  '2026-02-04 08:10:00'),
  ('Fix flaky test',        'OrderServiceIT is flaky',       'ASSIGNED', 'alice', 'bob',   '2026-02-05 17:30:00');
