-- Исправление координат для адреса "Пирогова 1" (если 2ГИС показывает неверное место).
-- Узнать правильные координаты: https://2gis.ru/chelyabinsk/search/Пирогова%201
-- Затем обновить заказы или магазины с этим адресом.

-- Пример: обновить shop_pickup override для заказов с магазином на Пирогова 1
-- UPDATE orders
-- SET shop_pickup_latitude = 55.XXXXX, shop_pickup_longitude = 61.XXXXX,
--     shop_pickup_address_override = 'Пирогова 1'
-- WHERE shop_id IN (SELECT id FROM shops WHERE pickup_address ILIKE '%Пирогова%');

-- Или обновить сам магазин:
-- UPDATE shops SET latitude = 55.XXXXX, longitude = 61.XXXXX
-- WHERE pickup_address ILIKE '%Пирогова%';
