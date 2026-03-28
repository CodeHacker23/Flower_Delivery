-- ============================================================
-- Пополнение депозита курьера (PostgreSQL)
-- РЕАЛИЗОВАНО: простой SQL без DO-блока (работает во всех клиентах)
-- Замени 642867793 на свой telegram_id, 6000 на нужную сумму
-- ============================================================

-- Шаг 1: пополнить баланс
UPDATE couriers
SET balance = COALESCE(balance, 0) + 6000
WHERE user_id = (SELECT id FROM users WHERE telegram_id = 642867793);

-- Шаг 2: записать транзакцию
INSERT INTO courier_transactions (id, courier_id, type, amount, description)
SELECT gen_random_uuid(), c.id, 'DEPOSIT_TOP_UP', 6000, 'Ручное пополнение'
FROM couriers c
JOIN users u ON c.user_id = u.id
WHERE u.telegram_id = 642867793;
