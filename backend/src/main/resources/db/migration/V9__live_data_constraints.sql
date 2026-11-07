ALTER TABLE node_runtime
 ADD CONSTRAINT ck_runtime_uptime CHECK(uptime_ms BETWEEN 0 AND 18446744073709551615),
 ADD CONSTRAINT ck_runtime_seen CHECK(last_seen>=0),
 ADD CONSTRAINT ck_runtime_online CHECK(online IN (0,1)),
 ADD CONSTRAINT ck_runtime_time_quality CHECK(ts_src IN ('SNTP','UNSYNCED'));
ALTER TABLE location_samples
 ADD CONSTRAINT ck_location_time CHECK((ts_src='SNTP' AND device_ts IS NOT NULL AND device_ts>=0) OR (ts_src='UNSYNCED' AND device_ts IS NULL)),
 ADD CONSTRAINT ck_location_received CHECK(received_at>=0);
ALTER TABLE command_receipts ADD CONSTRAINT ck_receipt_received CHECK(received_at>=0);
