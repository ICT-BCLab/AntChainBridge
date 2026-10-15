-- Operator-installed extension. Does not change coordinator schema version 2.
-- Recovery tooling only INSERTs/SELECTs; grant no UPDATE/DELETE on this table
-- to the recovery role. Keep signed_tx and credentials out of evidence_json.
CREATE TABLE IF NOT EXISTS bridge_tx_reconciliation (
  recovery_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  network_id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  account VARCHAR(160) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  old_observed_isn BIGINT NOT NULL,
  old_next_isn BIGINT NOT NULL,
  new_isn BIGINT NOT NULL,
  manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  operator_name VARCHAR(128) NOT NULL,
  evidence_json MEDIUMTEXT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (recovery_id),
  KEY ix_bridge_tx_reconciliation_account (network_id,account,created_at)
) ENGINE=InnoDB;
