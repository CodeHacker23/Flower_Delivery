-- Баланс и комиссия курьера: поле баланса в couriers + процент комиссии.
-- Таблица couriers уже существует (создана ранее через Hibernate или отдельной миграцией),
-- здесь только добавляем недостающие колонки.

ALTER TABLE couriers
    ADD COLUMN IF NOT EXISTS balance DECIMAL(10, 2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS commission_percent DECIMAL(5, 2) NOT NULL DEFAULT 20.00;

COMMENT ON COLUMN couriers.balance IS 'Текущий баланс депозита курьера (для комиссий и штрафов)';
COMMENT ON COLUMN couriers.commission_percent IS 'Процент комиссии с доставки (по умолчанию 20%)';

