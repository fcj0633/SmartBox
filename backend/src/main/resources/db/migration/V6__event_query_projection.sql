CREATE TABLE event_views (
 event_id BIGINT PRIMARY KEY, box_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 received_at BIGINT NOT NULL, event_type VARCHAR(32) NOT NULL, level VARCHAR(16) NOT NULL,
 payload JSON NOT NULL,
 FOREIGN KEY(event_id,box_id) REFERENCES events(id,box_id) ON DELETE RESTRICT,
 INDEX ix_view_time(box_id,received_at,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
