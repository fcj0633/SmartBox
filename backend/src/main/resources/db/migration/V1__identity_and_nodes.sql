CREATE TABLE roles (
  role_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  display_name VARCHAR(64) NOT NULL,
  CHECK (role_code IN ('LOGISTICS_VIEWER','LOGISTICS_OPERATOR','DELIVERY_USER','COURIER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE users (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(128) COLLATE utf8mb4_0900_bin NOT NULL UNIQUE,
  password_hash VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  role_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at BIGINT NOT NULL CHECK (created_at >= 0),
  updated_at BIGINT NOT NULL CHECK (updated_at >= 0),
  CHECK (CHAR_LENGTH(username) BETWEEN 1 AND 128),
  CHECK (enabled IN (0,1)),
  FOREIGN KEY (role_code) REFERENCES roles(role_code) ON DELETE RESTRICT ON UPDATE RESTRICT,
  INDEX ix_users_role (role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE boxes (
  box_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  CHECK (box_id REGEXP '^[A-Za-z0-9_-]+$'),
  display_name VARCHAR(128) NOT NULL,
  profile_config JSON NULL,
  sentry_revision DECIMAL(20,0) NOT NULL DEFAULT 0 CHECK (sentry_revision BETWEEN 0 AND 18446744073709551615),
  created_at BIGINT NOT NULL CHECK (created_at >= 0),
  updated_at BIGINT NOT NULL CHECK (updated_at >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE nodes (
  node_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  CHECK (node_id REGEXP '^[A-Za-z0-9_-]+$'),
  box_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  role VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  caps SMALLINT NOT NULL,
  required BOOLEAN NOT NULL DEFAULT TRUE,
  CHECK (required IN (0,1)),
  current_boot_id BIGINT NULL DEFAULT NULL CHECK (current_boot_id BETWEEN 0 AND 4294967295),
  last_received_at BIGINT NULL DEFAULT NULL CHECK (last_received_at >= 0),
  created_at BIGINT NOT NULL CHECK (created_at >= 0),
  updated_at BIGINT NOT NULL CHECK (updated_at >= 0),
  CHECK ((role='ENV' AND caps=71) OR (role='POSE' AND caps=120)),
  UNIQUE KEY uk_node_box (node_id,box_id),
  UNIQUE KEY uk_box_role (box_id,role),
  INDEX ix_nodes_required (box_id,required),
  FOREIGN KEY (box_id) REFERENCES boxes(box_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE user_box_access (
  user_id BIGINT NOT NULL,
  box_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at BIGINT NOT NULL CHECK (created_at >= 0),
  PRIMARY KEY (user_id,box_id),
  INDEX ix_box_user (box_id,user_id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  FOREIGN KEY (box_id) REFERENCES boxes(box_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
INSERT INTO roles(role_code,display_name) VALUES ('LOGISTICS_VIEWER','物流寄收件人'),('LOGISTICS_OPERATOR','物流操作者'),('DELIVERY_USER','外卖用户'),('COURIER','骑手');
