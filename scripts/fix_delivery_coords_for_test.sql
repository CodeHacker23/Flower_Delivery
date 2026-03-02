-- =============================================================================
-- ТЕСТ: проставить правильные координаты доставки для заказов с типичными адресами.
-- Иначе в 2ГИС/Яндексе показывается не та точка (Комсомольский пр-кт 41 иногда
-- попадал на Свердловский проспект — здесь принудительно ЧЛБ, Комсомольский 41).
-- Запускать после run_replace_shop_pickup.sql или отдельно.
-- =============================================================================

-- Комсомольский проспект 41 (КМСУ и т.п.) — Курчатовский район
UPDATE orders
SET delivery_latitude = 55.194091, delivery_longitude = 61.338985
WHERE delivery_address ILIKE '%комсомольск%41%'
   OR delivery_address ILIKE '%кмсу%41%';

-- Если доставка в order_stops (мультиадресный заказ) — обновить первый stop
UPDATE order_stops os
SET delivery_latitude = 55.194091, delivery_longitude = 61.338985
FROM orders o
WHERE os.order_id = o.id
  AND o.is_multi_stop = true
  AND (os.delivery_address ILIKE '%комсомольск%41%' OR os.delivery_address ILIKE '%кмсу%41%')
  AND os.stop_number = (SELECT MIN(stop_number) FROM order_stops WHERE order_id = o.id);
