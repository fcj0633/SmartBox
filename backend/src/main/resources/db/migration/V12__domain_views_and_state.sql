CREATE TABLE box_domain_state (
  box_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  door_state VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNKNOWN' CHECK(door_state IN ('OPEN','CLOSED','UNKNOWN')),
  door_confidence VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'INSUFFICIENT' CHECK(door_confidence IN ('HIGH','MEDIUM','LOW','INSUFFICIENT')),
  door_conclusion_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  door_observed_at BIGINT NULL CHECK(door_observed_at>=0),
  door_historical BOOLEAN NOT NULL DEFAULT FALSE CHECK(door_historical IN (0,1)),
  door_received_at BIGINT NOT NULL DEFAULT 0 CHECK(door_received_at>=0),
  updated_at BIGINT NOT NULL CHECK(updated_at>=0),
  FOREIGN KEY(box_id) REFERENCES boxes(box_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  FOREIGN KEY(door_conclusion_id) REFERENCES door_conclusions(conclusion_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE box_domain_views (
  view_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  box_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  received_at BIGINT NOT NULL CHECK(received_at>=0),
  event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK(event_type IN ('DOOR_CONCLUSION','ALARM')),
  level VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK(level IN ('INFO','WARNING','CRITICAL')),
  payload JSON NOT NULL,
  created_at BIGINT NOT NULL CHECK(created_at>=0),
  FOREIGN KEY(box_id) REFERENCES boxes(box_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  INDEX ix_domain_view_box(box_id,received_at,view_id),
  INDEX ix_domain_view_filter(box_id,event_type,level,received_at,view_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

ALTER TABLE processing_outbox ADD INDEX ix_outbox_task_due(task_type,status,next_attempt_at,id);
