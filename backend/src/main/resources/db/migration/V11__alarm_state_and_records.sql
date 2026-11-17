CREATE TABLE alarms (
  box_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  alarm_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  active BOOLEAN NOT NULL CHECK(active IN (0,1)),
  level VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK(level IN ('INFO','WARNING','CRITICAL')),
  started_at BIGINT NOT NULL CHECK(started_at>=0),
  updated_at BIGINT NOT NULL CHECK(updated_at>=0),
  source_event_id BIGINT NOT NULL,
  recovered_by_event_id BIGINT NULL,
  details JSON NOT NULL,
  PRIMARY KEY(box_id,alarm_key),
  FOREIGN KEY(box_id) REFERENCES boxes(box_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  FOREIGN KEY(source_event_id,box_id) REFERENCES events(id,box_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  FOREIGN KEY(recovered_by_event_id,box_id) REFERENCES events(id,box_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  INDEX ix_alarms_active(box_id,active,level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE alarm_state (
  box_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  assessment VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'UNKNOWN' CHECK(assessment IN ('UNKNOWN','EVALUATED')),
  active BOOLEAN NULL,
  level VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL CHECK(level IS NULL OR level IN ('INFO','WARNING','CRITICAL')),
  updated_at BIGINT NOT NULL CHECK(updated_at>=0),
  CHECK((assessment='UNKNOWN' AND active IS NULL AND level IS NULL) OR (assessment='EVALUATED' AND active IS NOT NULL AND (active=1 AND level IS NOT NULL OR active=0 AND level IS NULL))),
  FOREIGN KEY(box_id) REFERENCES boxes(box_id) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
