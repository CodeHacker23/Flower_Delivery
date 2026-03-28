-- ============================================================
-- ТОЛЬКО ДЛЯ ТЕСТИРОВАНИЯ: колонки переопределения адреса магазина
-- В продакшене: 1 магазин = 1 адрес. Это для проверки маршрутов OSRM.
-- Выполни ЭТОТ скрипт ПЕРВЫМ в DBeaver (до shop_pickup_override.sql)
-- ============================================================

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS shop_pickup_address_override VARCHAR(500),
    ADD COLUMN IF NOT EXISTS shop_pickup_latitude DECIMAL(10, 8),
    ADD COLUMN IF NOT EXISTS shop_pickup_longitude DECIMAL(11, 8);

COMMENT ON COLUMN orders.shop_pickup_address_override IS 'Адрес забора для теста (если задан — вместо shop.pickup_address)';
COMMENT ON COLUMN orders.shop_pickup_latitude IS 'Широта адреса забора (override)';
COMMENT ON COLUMN orders.shop_pickup_longitude IS 'Долгота адреса забора (override)';
