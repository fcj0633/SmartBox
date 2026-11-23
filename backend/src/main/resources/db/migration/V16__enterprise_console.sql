-- V16: enterprise console first slice. Additive only; V1..V15 remain unchanged.
ALTER TABLE boxes
  ADD COLUMN asset_source VARCHAR(16) NOT NULL DEFAULT 'REAL'
  CHECK (asset_source IN ('REAL','SIMULATED'));

ALTER TABLE transport_tasks
  MODIFY rider_id BIGINT NULL,
  ADD COLUMN operator_user_id BIGINT NULL,
  ADD COLUMN enterprise_id BIGINT NULL,
  ADD COLUMN route_code VARCHAR(32) NULL,
  ADD COLUMN route_snapshot JSON NULL;

-- Existing tasks keep their rider; operator mirrors it for a uniform actor field.
UPDATE transport_tasks SET operator_user_id = rider_id WHERE operator_user_id IS NULL;

-- Existing enterprise tasks inherit the current enterprise ownership.
UPDATE transport_tasks t
  JOIN box_ownerships o ON o.box_id = t.box_id AND o.owner_type = 'ENTERPRISE'
  SET t.enterprise_id = o.enterprise_id
  WHERE t.enterprise_id IS NULL;

ALTER TABLE transport_tasks
  ADD CONSTRAINT fk_task_operator FOREIGN KEY (operator_user_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT fk_task_enterprise FOREIGN KEY (enterprise_id) REFERENCES enterprises(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT chk_task_actor CHECK (rider_id IS NOT NULL OR operator_user_id IS NOT NULL),
  ADD INDEX ix_task_enterprise_created (enterprise_id, created_at, id);
