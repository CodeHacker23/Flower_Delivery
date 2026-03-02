-- ============================================
-- V9: ТОЛЬКО ДЛЯ ТЕСТИРОВАНИЯ — переопределение адреса магазина
-- ============================================
-- В продакшене: 1 аккаунт магазина = 1 адрес забора (shop.pickup_address).
-- Эти колонки позволяют симулировать разные точки забора у одного магазина,
-- чтобы проверить, как OSRM строит связки. В проде должны быть NULL.
--
-- Когда NULL — используется shop.pickup_address (норма)
-- Когда задано — тестовый адрес забора для маршрутов
-- ============================================

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS shop_pickup_address_override VARCHAR(500),
    ADD COLUMN IF NOT EXISTS shop_pickup_latitude DECIMAL(10, 8),
    ADD COLUMN IF NOT EXISTS shop_pickup_longitude DECIMAL(11, 8);

COMMENT ON COLUMN orders.shop_pickup_address_override IS 'Адрес забора для теста (если задан — вместо shop.pickup_address)';
COMMENT ON COLUMN orders.shop_pickup_latitude IS 'Широта адреса забора (override)';
COMMENT ON COLUMN orders.shop_pickup_longitude IS 'Долгота адреса забора (override)';
