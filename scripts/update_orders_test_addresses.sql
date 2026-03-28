-- ============================================================
-- ТОЛЬКО ДЛЯ ТЕСТИРОВАНИЯ: симуляция разных магазинов у одного аккаунта
-- В продакшене: 1 магазин = 1 адрес. Это для проверки маршрутов OSRM.
-- ============================================================
-- Адрес магазина (забор) = бывший адрес получателя (Тухачевского, Пирогова и т.д.)
-- Адрес получателя = новые адреса (Ленина 83, Труда 72, Цвиллинга 45, Комсомольский пр-кт 41)
--
-- Сначала выполни: scripts/apply_shop_pickup_override_columns.sql
-- Координаты — примерные для Челябинска (уточни в 2ГИС при необходимости)
-- ============================================================

-- 1) Заказ: забор Тухачевского 10а → доставка Ленина 83
UPDATE orders SET
    delivery_address = 'Проспект Ленина 83',
    delivery_latitude = 55.167000,
    delivery_longitude = 61.400000,
    shop_pickup_address_override = 'Челябинск, ул. Тухачевского, 10а',
    shop_pickup_latitude = 55.153200,
    shop_pickup_longitude = 61.422100
WHERE id = (SELECT id FROM orders WHERE delivery_address ILIKE '%Тухачевского%10%' AND status = 'NEW' LIMIT 1);

-- 2) Заказ: забор Пирогова 1 → доставка Труда 72
UPDATE orders SET
    delivery_address = 'Труда 72',
    delivery_latitude = 55.155000,
    delivery_longitude = 61.390000,
    shop_pickup_address_override = 'Челябинск, ул. Пирогова, 1',
    shop_pickup_latitude = 55.161000,
    shop_pickup_longitude = 61.430000
WHERE id = (SELECT id FROM orders WHERE delivery_address ILIKE '%Пирогова%1%' AND status = 'NEW' LIMIT 1);

-- 3) Заказ: забор Новороссийская 53 → доставка Цвиллинга 45
UPDATE orders SET
    delivery_address = 'Цвиллинга 45',
    delivery_latitude = 55.162000,
    delivery_longitude = 61.412000,
    shop_pickup_address_override = 'Челябинск, ул. Новороссийская, 53',
    shop_pickup_latitude = 55.152000,
    shop_pickup_longitude = 61.408000
WHERE id = (SELECT id FROM orders WHERE delivery_address ILIKE '%Новороссийск%53%' AND status = 'NEW' LIMIT 1);

-- 4) Заказ: забор Аральская 168 → доставка Комсомольский пр-кт 41
UPDATE orders SET
    delivery_address = 'Комсомольский пр-кт 41',
    delivery_latitude = 55.170000,
    delivery_longitude = 61.385000,
    shop_pickup_address_override = 'Челябинск, ул. Аральская, 168',
    shop_pickup_latitude = 55.178000,
    shop_pickup_longitude = 61.368000
WHERE id = (SELECT id FROM orders WHERE delivery_address ILIKE '%Аральск%168%' AND status = 'NEW' LIMIT 1);

-- 5) Заказ: забор Молодогвардейцев 68в → доставка Калинина 20
UPDATE orders SET
    delivery_address = 'Калинина 20',
    delivery_latitude = 55.158000,
    delivery_longitude = 61.405000,
    shop_pickup_address_override = 'Челябинск, ул. Молодогвардейцев, 68в',
    shop_pickup_latitude = 55.165000,
    shop_pickup_longitude = 61.382000
WHERE id = (SELECT id FROM orders WHERE delivery_address ILIKE '%Молодогвардейц%68%' AND status = 'NEW' LIMIT 1);
