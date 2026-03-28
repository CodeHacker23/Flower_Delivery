-- ============================================================
-- Сократить адреса забора: убрать "Челябинск, ул." → только "Улица Дом"
-- Чтобы в кнопках было "Труда 72", а не "Челябинск, ул. Труда..."
-- ============================================================

UPDATE orders SET shop_pickup_address_override = 'Тухачевского 10а'
WHERE shop_pickup_address_override ILIKE '%Тухачевского%10%';

UPDATE orders SET shop_pickup_address_override = 'Пирогова 1'
WHERE shop_pickup_address_override ILIKE '%Пирогова%1%';

UPDATE orders SET shop_pickup_address_override = 'Новороссийская 53'
WHERE shop_pickup_address_override ILIKE '%Новороссийск%53%';

UPDATE orders SET shop_pickup_address_override = 'Аральская 168'
WHERE shop_pickup_address_override ILIKE '%Аральск%168%';

UPDATE orders SET shop_pickup_address_override = 'Молодогвардейцев 68в'
WHERE shop_pickup_address_override ILIKE '%Молодогвардейц%68%';
