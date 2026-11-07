CREATE TABLE node_runtime (
 node_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 boot_id BIGINT NOT NULL CHECK(boot_id BETWEEN 0 AND 4294967295),
 online BOOLEAN NOT NULL, last_seen BIGINT NOT NULL, uptime_ms DECIMAL(20,0) NOT NULL,
 ts_src VARCHAR(16) NOT NULL, heartbeat JSON NULL, actual_state JSON NULL,
 applied_profile JSON NULL, applied_profile_request VARCHAR(64) NULL,
 FOREIGN KEY(node_id) REFERENCES nodes(node_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE location_samples (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 node_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 box_id VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 boot_id BIGINT NOT NULL CHECK(boot_id BETWEEN 0 AND 4294967295),
 sample_seq BIGINT NOT NULL CHECK(sample_seq BETWEEN 1 AND 4294967295),
 device_ts BIGINT NULL, ts_src VARCHAR(16) NOT NULL,
 uptime_ms DECIMAL(20,0) NOT NULL CHECK(uptime_ms BETWEEN 0 AND 18446744073709551615),
 received_at BIGINT NOT NULL, payload JSON NOT NULL,
 UNIQUE KEY uk_location(node_id,boot_id,sample_seq),
 INDEX ix_location_time(box_id,received_at,id),
 FOREIGN KEY(node_id,box_id) REFERENCES nodes(node_id,box_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
