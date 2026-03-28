-- ============================================================
-- Сброс профиля курьера (PostgreSQL)
-- РЕАЛИЗОВАНО: порядок учитывает FK (courier_transactions перед couriers)
-- Замени 642867793 на свой telegram_id курьера
-- ============================================================

-- Выполняй команды по порядку:

-- 1) Снимки гео по заказам курьера
DELETE FROM order_status_geo_snapshots
WHERE order_id IN (
    SELECT id FROM orders
    WHERE courier_id = (SELECT id FROM users WHERE telegram_id = 642867793)
);

-- 2) Точки мультиадресов — сброс в PENDING
UPDATE order_stops
SET stop_status = 'PENDING', delivered_at = NULL
WHERE order_id IN (
    SELECT id FROM orders
    WHERE courier_id = (SELECT id FROM users WHERE telegram_id = 642867793)
);

-- 3) Заказы → снова NEW, без курьера
UPDATE orders
SET courier_id = NULL, status = 'NEW', accepted_at = NULL,
    picked_up_at = NULL, delivered_at = NULL, courier_cancel_reason = NULL
WHERE courier_id = (SELECT id FROM users WHERE telegram_id = 642867793);

-- 4) Удалить транзакции курьера (FK не позволяет удалить couriers без этого)
DELETE FROM courier_transactions
WHERE courier_id IN (
    SELECT id FROM couriers
    WHERE user_id = (SELECT id FROM users WHERE telegram_id = 642867793)
);

-- 5) Удалить курьера
DELETE FROM couriers
WHERE user_id = (SELECT id FROM users WHERE telegram_id = 642867793);

-- 6) Сбросить роль пользователя (НЕ удаляем users!)
UPDATE users
SET role = NULL, is_active = FALSE
WHERE telegram_id = 642867793;
