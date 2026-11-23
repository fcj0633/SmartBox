CREATE TABLE transport_guest_access (
 task_id BIGINT NOT NULL PRIMARY KEY,
 code_hash VARCHAR(100) NOT NULL,
 issued_at BIGINT NOT NULL CHECK (issued_at>=0),
 session_hash BINARY(32) NULL,
 session_expires_at BIGINT NULL CHECK (session_expires_at>=0),
 FOREIGN KEY (task_id) REFERENCES transport_tasks(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
 UNIQUE KEY uk_guest_session_hash (session_hash),
 CHECK ((session_hash IS NULL AND session_expires_at IS NULL) OR (session_hash IS NOT NULL AND session_expires_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
