-- =============================================================================
-- ПЕРЕД ПРОДАКШЕНОМ: откат тестовых override адресов забора.
-- После выполнения: 1 магазин в БД = 1 адрес магазина (из shops.pickup_address).
-- =============================================================================
-- Вставь в PostgreSQL и выполни (F5).

UPDATE orders
SET shop_pickup_address_override = NULL,
    shop_pickup_latitude         = NULL,
    shop_pickup_longitude        = NULL
WHERE shop_pickup_address_override IS NOT NULL;
