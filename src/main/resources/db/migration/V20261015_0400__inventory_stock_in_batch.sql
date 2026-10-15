-- A stock-in books one ledger line per card. These tie the lines of one stock-in
-- together and keep the average it left behind, so the seller's restock history
-- reads back as lots [TCG-502]. Null on lines written before this, and on every
-- outbound line.
ALTER TABLE inventory_movement
    ADD COLUMN stock_in_batch_uid uuid,
    ADD COLUMN average_cost_after decimal(14,4);

COMMENT ON COLUMN inventory_movement.stock_in_batch_uid IS
    'Same value on every line of one stock-in; null for older lines and anything outbound.';
COMMENT ON COLUMN inventory_movement.average_cost_after IS
    'The seller''s average unit cost for this printing and condition right after the stock-in.';
