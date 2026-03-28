-- ============================================================
-- ТОЛЬКО ДЛЯ ТЕСТИРОВАНИЯ: разные адреса магазина у одного аккаунта
-- В продакшене такого не бывает — 1 магазин = 1 адрес забора.
-- Нужно, чтобы посмотреть, как OSRM строит связки.
-- ============================================================
-- 1) Сначала: apply_shop_pickup_override_columns.sql
-- 2) Перезапусти бота
-- 3) Выполни этот скрипт
-- 4) Проверка: SELECT id, delivery_address, shop_pickup_address_override FROM orders WHERE status = 'NEW' LIMIT 10;
-- ============================================================

-- Заказ 1 → забор с Тухачевского 10а
UPDATE orders SET
    shop_pickup_address_override = 'Челябинск, ул. Тухачевского, 10а',
    shop_pickup_latitude = 55.153200,
    shop_pickup_longitude = 61.422100
WHERE id = (SELECT id FROM orders WHERE status = 'NEW' ORDER BY created_at LIMIT 1 OFFSET 0);

-- Заказ 2 → забор с Пирогова 1
UPDATE orders SET
    shop_pickup_address_override = 'Челябинск, ул. Пирогова, 1',
    shop_pickup_latitude = 55.161000,
    shop_pickup_longitude = 61.430000
WHERE id = (SELECT id FROM orders WHERE status = 'NEW' ORDER BY created_at LIMIT 1 OFFSET 1);

-- Заказ 3 → забор с Новороссийская 53
UPDATE orders SET
    shop_pickup_address_override = 'Челябинск, ул. Новороссийская, 53',
    shop_pickup_latitude = 55.152000,
    shop_pickup_longitude = 61.408000
WHERE id = (SELECT id FROM orders WHERE status = 'NEW' ORDER BY created_at LIMIT 1 OFFSET 2);

-- Заказ 4 → забор с Аральская 168
UPDATE orders SET
    shop_pickup_address_override = 'Челябинск, ул. Аральская, 168',
    shop_pickup_latitude = 55.178000,
    shop_pickup_longitude = 61.368000
WHERE id = (SELECT id FROM orders WHERE status = 'NEW' ORDER BY created_at LIMIT 1 OFFSET 3);

-- Заказ 5 → забор с Молодогвардейцев 68в
UPDATE orders SET
    shop_pickup_address_override = 'Челябинск, ул. Молодогвардейцев, 68в',
    shop_pickup_latitude = 55.165000,
    shop_pickup_longitude = 61.382000
WHERE id = (SELECT id FROM orders WHERE status = 'NEW' ORDER BY created_at LIMIT 1 OFFSET 4);
