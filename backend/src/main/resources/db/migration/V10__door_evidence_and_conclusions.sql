CREATE TABLE event_evidence (
  event_id BIGINT NOT NULL PRIMARY KEY,
  box_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  node_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  kind VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK(kind IN ('LIGHT','TILT')),
  action VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK(action IN ('OPEN','CLOSE')),
  observed_at BIGINT NULL CHECK(observed_at>=0),
  ts_src VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK(ts_src IN ('SNTP','UNSYNCED')),
  time_uncertainty_ms BIGINT NULL CHECK(time_uncertainty_ms>=0),
  received_at BIGINT NOT NULL CHECK(received_at>=0),
  replayed BOOLEAN NOT NULL CHECK(replayed IN (0,1)),
  data JSON NOT NULL,
  conclusion_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  created_at BIGINT NOT NULL CHECK(created_at>=0),
  CHECK((ts_src='SNTP' AND observed_at IS NOT NULL AND time_uncertainty_ms IS NOT NULL) OR (ts_src='UNSYNCED' AND observed_at IS NULL AND time_uncertainty_ms IS NULL)),
  FOREIGN KEY(event_id,box_id) REFERENCES events(id,box_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  FOREIGN KEY(node_id,box_id) REFERENCES nodes(node_id,box_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  INDEX ix_evidence_match(box_id,action,received_at,kind,conclusion_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE door_conclusions (
  conclusion_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  box_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK(state IN ('OPEN','CLOSED','UNKNOWN')),
  confidence VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL CHECK(confidence IN ('HIGH','MEDIUM','LOW','INSUFFICIENT')),
  reason VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  observed_at BIGINT NULL CHECK(observed_at>=0),
  received_at BIGINT NOT NULL CHECK(received_at>=0),
  historical BOOLEAN NOT NULL CHECK(historical IN (0,1)),
  unfused BOOLEAN NOT NULL CHECK(unfused IN (0,1)),
  source_event_ids JSON NOT NULL,
  created_at BIGINT NOT NULL CHECK(created_at>=0),
  FOREIGN KEY(box_id) REFERENCES boxes(box_id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  INDEX ix_door_box(box_id,historical,received_at,conclusion_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
