-- V17: demo asset metadata and scenario switching. Additive only; V1..V16 unchanged.
ALTER TABLE boxes
  ADD COLUMN purpose VARCHAR(120) NULL,
  ADD COLUMN notes VARCHAR(500) NULL,
  ADD COLUMN demo_scenario VARCHAR(16) NULL,
  ADD COLUMN demo_enabled BOOLEAN NOT NULL DEFAULT 1;

UPDATE boxes SET demo_scenario='normal'
  WHERE asset_source='SIMULATED' AND demo_scenario IS NULL;

ALTER TABLE boxes ADD CONSTRAINT chk_demo_scenario CHECK (
  (asset_source='REAL' AND demo_scenario IS NULL)
  OR (asset_source='SIMULATED' AND demo_scenario IN ('normal','env_high','offline','stale','impact'))
);
