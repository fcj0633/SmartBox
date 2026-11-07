ALTER TABLE command_operations ADD deadline_at BIGINT NULL, ADD finalized_at BIGINT NULL;
ALTER TABLE node_commands ADD target_boot_id BIGINT NULL CHECK(target_boot_id BETWEEN 0 AND 4294967295);
CREATE TABLE command_dispatch (
 request_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin PRIMARY KEY,
 topic VARCHAR(128) CHARACTER SET ascii NOT NULL, payload JSON NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 next_attempt_at BIGINT NOT NULL, locked_until BIGINT NULL, lock_owner VARCHAR(64) NULL,
 FOREIGN KEY(request_id) REFERENCES node_commands(request_id) ON DELETE RESTRICT,
 INDEX ix_dispatch_due(status,next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
CREATE TABLE command_receipts (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 request_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
 content_hash BINARY(32) NOT NULL, received_at BIGINT NOT NULL, payload JSON NOT NULL,
 UNIQUE KEY uk_receipt(request_id,content_hash),
 FOREIGN KEY(request_id) REFERENCES node_commands(request_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
