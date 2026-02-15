-- ============================================
-- Сброс тестового пользователя (telegram_id = 642867793)
-- Замени 642867793 на свой telegram_id при необходимости.
-- ============================================

-- ============================================
-- ВАРИАНТ 1: МЯГКИЙ СБРОС (рекомендуется для проверки курьера с нуля)
-- Результат: ты без роли, записи курьера нет, заказы которые ты вёл — снова NEW и без курьера.
-- Магазин и юзер остаются.
-- ============================================

-- 1) Удалить снимки геолокации по заказам, которые ты вёл как курьер
DELETE FROM order_status_geo_snapshots
WHERE order_id IN (
    SELECT id FROM orders
    WHERE courier_id = (SELECT id FROM users WHERE telegram_id = 642867793)
);

-- 2) В точках мультиадресных заказов этих заказов — сбросить статус точки в PENDING
UPDATE order_stops
SET stop_status = 'PENDING',
    delivered_at = NULL
WHERE order_id IN (
    SELECT id FROM orders
    WHERE courier_id = (SELECT id FROM users WHERE telegram_id = 642867793)
);

-- 3) В заказах, где ты был курьером: вернуть в состояние "только создан" (NEW, без курьера)
UPDATE orders
SET courier_id = NULL,
    status = 'NEW',
    accepted_at = NULL,
    picked_up_at = NULL,
    delivered_at = NULL
WHERE courier_id = (SELECT id FROM users WHERE telegram_id = 642867793);

-- 4) Удалить запись курьера
DELETE FROM couriers
WHERE user_id = (SELECT id FROM users WHERE telegram_id = 642867793);

-- 5) Сбросить роль и активность у юзера
UPDATE users
SET role = NULL,
    is_active = FALSE
WHERE telegram_id = 642867793;

-- Готово. Дальше: /start → выбрать «Курьер» → регистрация → /k → взять заказ.


-- ============================================
-- ВАРИАНТ 2: ТОТАЛЬНОЕ УДАЛЕНИЕ СЕБЯ ИЗ БД
-- Результат: юзера с этим telegram_id нет, нет магазина, нет курьера, нет заказов этого магазина.
-- Выполняй по шагам в указанном порядке (из-за внешних ключей).
-- ============================================

/*
-- Шаг 1: Удалить снимки гео по заказам твоего магазина и по заказам, где ты курьер
DELETE FROM order_status_geo_snapshots
WHERE order_id IN (
    SELECT id FROM orders
    WHERE shop_id IN (SELECT id FROM shops WHERE user_id = (SELECT id FROM users WHERE telegram_id = 642867793))
       OR courier_id = (SELECT id FROM users WHERE telegram_id = 642867793)
);

-- Шаг 2: Удалить курьера (если есть)
DELETE FROM couriers
WHERE user_id = (SELECT id FROM users WHERE telegram_id = 642867793);

-- Шаг 3: Удалить магазин (каскадом удалятся заказы этого магазина и их order_stops)
DELETE FROM shops
WHERE user_id = (SELECT id FROM users WHERE telegram_id = 642867793);

-- Шаг 4: Обнулить курьера в заказах, где ты был курьером (если заказы от другого магазина)
UPDATE orders
SET courier_id = NULL,
    status = 'NEW',
    accepted_at = NULL,
    picked_up_at = NULL,
    delivered_at = NULL
WHERE courier_id = (SELECT id FROM users WHERE telegram_id = 642867793);

-- Шаг 5: Удалить юзера
DELETE FROM users
WHERE telegram_id = 642867793;
*/
