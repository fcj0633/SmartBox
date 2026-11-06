-- MySQL CHECK accepts UNKNOWN; explicitly require the acknowledgement time source.
-- V1-V3 have been applied and are intentionally not rewritten.
ALTER TABLE node_commands
  DROP CHECK node_commands_chk_14,
  ADD CONSTRAINT ck_node_ack_complete CHECK (
    (ack_received_at IS NULL AND ack_boot_id IS NULL AND ack_ts IS NULL
      AND ack_ts_src IS NULL AND ack_uptime_ms IS NULL AND ack_time_uncertainty_ms IS NULL
      AND result IS NULL AND code IS NULL AND actual_state IS NULL)
    OR
    (ack_received_at IS NOT NULL AND ack_boot_id IS NOT NULL AND ack_uptime_ms IS NOT NULL
      AND ack_ts_src IS NOT NULL AND result IS NOT NULL AND code IS NOT NULL AND actual_state IS NOT NULL
      AND ((ack_ts_src='SNTP' AND ack_ts IS NOT NULL AND ack_time_uncertainty_ms IS NOT NULL)
        OR (ack_ts_src='UNSYNCED' AND ack_ts IS NULL AND ack_time_uncertainty_ms IS NULL)))
  );
