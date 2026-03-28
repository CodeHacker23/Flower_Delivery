-- ============================================================
-- ТОЛЬКО ДЛЯ ТЕСТИРОВАНИЯ: переопределение адреса магазина
-- В продакшене: 1 магазин = 1 адрес. Это для проверки маршрутов OSRM.
-- ============================================================
-- Сначала выполни: scripts/apply_shop_pickup_override_columns.sql
-- (или запусти приложение — Flyway применит V9)
--
-- Колонки: shop_pickup_address_override, shop_pickup_latitude, shop_pickup_longitude
-- Когда заданы — бот использует их вместо shop.pickup_address
--
-- Координаты — примерные для Челябинска. Точные можно взять из 2ГИС (долгий тап по точке → координаты)
-- ============================================================

-- 1) Посмотреть текущие NEW заказы (раскомментируй и выполни)
-- SELECT id, delivery_address, delivery_date, shop_pickup_address_override
-- FROM orders WHERE status = 'NEW' ORDER BY created_at;

-- 2) Пример: заказ с доставкой на Пирогова 1 — забирать с Тухачевского 10а
--    (подставь свой order id из шага 1)
/*
UPDATE orders
SET shop_pickup_address_override = 'Челябинск, ул. Тухачевского, 10а',
    shop_pickup_latitude = 55.153200,
    shop_pickup_longitude = 61.422100
WHERE delivery_address ILIKE '%Пирогова%1%' AND status = 'NEW'
  AND shop_id = (SELECT id FROM shops LIMIT 1);  -- только заказы твоего магазина
*/

-- 3) Задать адрес забора для заказа с доставкой на Тухачевского 10а
/*
UPDATE orders
SET shop_pickup_address_override = 'Челябинск, ул. Пирогова, 1',
    shop_pickup_latitude = 55.161000,
    shop_pickup_longitude = 61.430000
WHERE delivery_address ILIKE '%Тухачевского%' AND status = 'NEW';
*/

-- 4) Задать адрес забора для заказа с доставкой на Новороссийскую 53
/*
UPDATE orders
SET shop_pickup_address_override = 'Челябинск, ул. Худякова, 13',
    shop_pickup_latitude = 55.148000,
    shop_pickup_longitude = 61.398000
WHERE delivery_address ILIKE '%Новороссийск%53%' AND status = 'NEW';
*/

-- 5) Задать адрес забора для заказа с доставкой на Аральскую 168
/*
UPDATE orders
SET shop_pickup_address_override = 'Челябинск, ул. Молодогвардейцев, 50',
    shop_pickup_latitude = 55.165000,
    shop_pickup_longitude = 61.385000
WHERE delivery_address ILIKE '%Аральск%168%' AND status = 'NEW';
*/

-- ============================================================
-- Универсальный шаблон (скопируй и подставь свои значения):
-- ============================================================
/*
UPDATE orders
SET shop_pickup_address_override = 'Челябинск, ул. XXX, N',
    shop_pickup_latitude = 55.XXXXXX,
    shop_pickup_longitude = 61.XXXXXX
WHERE id = 'UUID-ЗАКАЗА';   -- или: WHERE delivery_address ILIKE '%улица%дом%'
*/

-- ============================================================
-- Сбросить override (вернуть адрес магазина из shops)
-- ============================================================
/*
UPDATE orders
SET shop_pickup_address_override = NULL,
    shop_pickup_latitude = NULL,
    shop_pickup_longitude = NULL
WHERE status = 'NEW';   -- или конкретный id / delivery_address
*/
