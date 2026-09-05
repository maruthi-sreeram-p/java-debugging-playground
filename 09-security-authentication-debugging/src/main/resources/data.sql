-- Seed accounts. All three passwords are BCrypt hashes of the values noted below;
-- they exist so that you have working accounts before registering your own.
--   aarav / Secret123!
--   divya / Secret123!
--   rohan / Passw0rd!
INSERT INTO app_users (username, email, password_hash, display_name, enabled, created_at) VALUES
  ('aarav', 'aarav.sharma@corp.com', '$2a$10$ec1T/0OoSd204tE5C6F1Punde1FHsIuEeu6hq07RZxzUkKqDzCfD.', 'Aarav Sharma', true, '2026-01-05 09:00:00'),
  ('divya', 'divya.nair@corp.com',   '$2a$10$ec1T/0OoSd204tE5C6F1Punde1FHsIuEeu6hq07RZxzUkKqDzCfD.', 'Divya Nair',   true, '2026-01-06 10:30:00'),
  ('rohan', 'rohan.mehta@corp.com',  '$2a$10$vYjj8nTRE5/PVSknzQI0IeF2mgHBXCd8BNpvEfT3SH08KOIPvs4qO', 'Rohan Mehta',  true, '2026-01-07 14:15:00');
