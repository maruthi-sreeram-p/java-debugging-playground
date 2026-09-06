CREATE TABLE IF NOT EXISTS app_user (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(50)  NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  display_name  VARCHAR(100) NOT NULL,
  role          VARCHAR(20)  NOT NULL
);

CREATE TABLE IF NOT EXISTS product (
  id     BIGINT AUTO_INCREMENT PRIMARY KEY,
  sku    VARCHAR(40)    NOT NULL UNIQUE,
  name   VARCHAR(150)   NOT NULL,
  price  DECIMAL(12, 2) NOT NULL,
  stock  INT            NOT NULL
);

CREATE TABLE IF NOT EXISTS customer_order (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_ref         VARCHAR(40)    NOT NULL UNIQUE,
  customer_username VARCHAR(50)    NOT NULL,
  sku               VARCHAR(40)    NOT NULL,
  quantity          INT            NOT NULL,
  total_amount      DECIMAL(12, 2) DEFAULT NULL,
  status            VARCHAR(20)    NOT NULL,
  placed_at         DATETIME       NOT NULL
);
