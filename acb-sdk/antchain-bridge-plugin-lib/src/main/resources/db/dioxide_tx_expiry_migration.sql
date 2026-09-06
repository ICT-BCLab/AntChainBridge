-- Back up first, pause all writers, then install the current dioxide_tx_coordinator.sql.
-- Preserve every old row. Expired signatures may share an ISN with a new intent;
-- account locks, the high-water mark and one-use witnessed grants control allocation.
ALTER TABLE bridge_tx_submission DROP INDEX uq_bridge_tx_isn,
  ADD INDEX ix_bridge_tx_isn (network_id, account, isn);
