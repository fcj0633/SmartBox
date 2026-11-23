CREATE TABLE transport_tasks (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  transport_no VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  box_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  rider_id BIGINT NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'READY',
  item_name VARCHAR(50) NULL,
  item_description VARCHAR(200) NULL,
  started_at BIGINT NULL,
  ended_at BIGINT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  active_box_key VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (
      CASE WHEN status='ACTIVE' THEN box_id ELSE NULL END
    ) STORED,
  CONSTRAINT fk_transport_box FOREIGN KEY (box_id) REFERENCES boxes(box_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT fk_transport_rider FOREIGN KEY (rider_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT chk_transport_status CHECK (status IN ('READY','ACTIVE','COMPLETED')),
  CONSTRAINT chk_transport_times CHECK (
    (status='READY' AND started_at IS NULL AND ended_at IS NULL)
    OR (status='ACTIVE' AND started_at IS NOT NULL AND ended_at IS NULL AND item_name IS NOT NULL)
    OR (status='COMPLETED' AND started_at IS NOT NULL AND ended_at IS NOT NULL
        AND ended_at>=started_at AND item_name IS NOT NULL)
  ),
  UNIQUE KEY uk_transport_no (transport_no),
  UNIQUE KEY uk_transport_active_box (active_box_key),
  INDEX ix_transport_rider_created (rider_id,created_at,id),
  INDEX ix_transport_box_created (box_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
