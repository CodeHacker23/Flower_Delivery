-- История операций по депозиту курьера (пополнения, комиссии, штрафы).

CREATE TABLE IF NOT EXISTS courier_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    courier_id UUID NOT NULL REFERENCES couriers(id) ON DELETE CASCADE,
    type VARCHAR(30) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    order_id UUID REFERENCES orders(id) ON DELETE SET NULL,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_courier_transactions_courier_id ON courier_transactions(courier_id);
CREATE INDEX IF NOT EXISTS idx_courier_transactions_order_id ON courier_transactions(order_id);

COMMENT ON TABLE courier_transactions IS 'История операций по депозиту курьеров (пополнения, комиссии, штрафы и т.п.)';
COMMENT ON COLUMN courier_transactions.courier_id IS 'Курьер, к которому относится операция';
COMMENT ON COLUMN courier_transactions.type IS 'Тип операции: DEPOSIT_TOP_UP, COMMISSION_CHARGE, COMMISSION_REFUND, FINE_500, FINE_1000 и т.п.';
COMMENT ON COLUMN courier_transactions.amount IS 'Сумма операции (плюс = пополнение/возврат, минус = списание)';
COMMENT ON COLUMN courier_transactions.order_id IS 'Заказ, к которому относится операция (если есть)';

