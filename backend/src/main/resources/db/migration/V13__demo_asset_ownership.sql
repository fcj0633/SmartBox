CREATE TABLE enterprises (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  admin_user_id BIGINT NOT NULL,
  created_at BIGINT NOT NULL CHECK (created_at >= 0),
  UNIQUE KEY uk_enterprise_demo_admin (admin_user_id),
  FOREIGN KEY (admin_user_id) REFERENCES users(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE box_ownerships (
  box_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  owner_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  enterprise_id BIGINT NULL,
  owner_user_id BIGINT NULL,
  created_at BIGINT NOT NULL CHECK (created_at >= 0),
  FOREIGN KEY (box_id) REFERENCES boxes(box_id) ON DELETE RESTRICT,
  FOREIGN KEY (enterprise_id) REFERENCES enterprises(id) ON DELETE RESTRICT,
  FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE RESTRICT,
  CHECK ((owner_type='ENTERPRISE' AND enterprise_id IS NOT NULL AND owner_user_id IS NULL)
      OR (owner_type='PERSONAL' AND enterprise_id IS NULL AND owner_user_id IS NOT NULL)),
  INDEX ix_ownership_enterprise (enterprise_id,box_id),
  INDEX ix_ownership_person (owner_user_id,box_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE box_assignments (
  box_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  rider_user_id BIGINT NOT NULL,
  assigned_by BIGINT NOT NULL,
  assigned_at BIGINT NOT NULL CHECK (assigned_at >= 0),
  FOREIGN KEY (box_id) REFERENCES box_ownerships(box_id) ON DELETE RESTRICT,
  FOREIGN KEY (rider_user_id) REFERENCES users(id) ON DELETE RESTRICT,
  FOREIGN KEY (assigned_by) REFERENCES users(id) ON DELETE RESTRICT,
  INDEX ix_assignment_rider (rider_user_id,box_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
