-- Причина отмены/возврата от курьера (передаётся в поддержку/админам)
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS courier_cancel_reason TEXT;

COMMENT ON COLUMN orders.courier_cancel_reason IS 'Причина отмены или возврата заказа, указанная курьером (передаётся админам)';
