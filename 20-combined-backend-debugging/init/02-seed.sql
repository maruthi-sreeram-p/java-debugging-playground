-- Development accounts. Every password below is BCrypt("Secret123!").
INSERT INTO app_user (username, password_hash, display_name, role) VALUES
  ('arjun', '$2a$10$ec1T/0OoSd204tE5C6F1Punde1FHsIuEeu6hq07RZxzUkKqDzCfD.', 'Arjun Mehta',   'CUSTOMER'),
  ('meena', '$2a$10$ec1T/0OoSd204tE5C6F1Punde1FHsIuEeu6hq07RZxzUkKqDzCfD.', 'Meena Iyer',    'CUSTOMER'),
  ('priya', '$2a$10$ec1T/0OoSd204tE5C6F1Punde1FHsIuEeu6hq07RZxzUkKqDzCfD.', 'Priya Raghavan', 'ADMIN');

INSERT INTO product (sku, name, price, stock) VALUES
  ('SKU-1001', 'Aluminium Laptop Stand',        2499.00, 40),
  ('SKU-1002', 'Mechanical Keyboard 87-key',    5899.00, 25),
  ('SKU-1003', 'Noise Cancelling Headphones',  12499.00, 12),
  ('SKU-1004', 'USB-C Docking Station',         8750.00,  8),
  ('SKU-1005', '27-inch 4K Monitor',           31990.00,  5),
  ('SKU-1006', 'Ergonomic Office Chair',       18450.00,  3);

-- Orders migrated from the previous platform.
INSERT INTO customer_order (order_ref, customer_username, sku, quantity, total_amount, status, placed_at) VALUES
  ('ORD-20250901-0001', 'arjun', 'SKU-1001', 2,  4998.00, 'FULFILLED', '2026-08-01 10:12:00'),
  ('ORD-20250901-0002', 'meena', 'SKU-1003', 1, 12499.00, 'FULFILLED', '2026-08-02 11:40:00'),
  ('ORD-20250901-0003', 'arjun', 'SKU-1005', 1, 31990.00, 'FULFILLED', '2026-08-05 09:05:00'),
  ('ORD-20250901-0004', 'meena', 'SKU-1002', 3, 17697.00, 'FULFILLED', '2026-08-11 16:22:00'),
  ('ORD-20250901-0005', 'arjun', 'SKU-1004', 1,  8750.00, 'FULFILLED', '2026-08-19 13:00:00'),
  ('ORD-20250901-0006', 'meena', 'SKU-1006', 1, 18450.00, 'FULFILLED', '2026-08-28 18:45:00');
