-- =============================================================================
-- Ровно 15 адресов «магазинов» (цветочные/здания в ЧЛБ) с точным геокодированием (OSM).
-- Тимирязева 42 нет в OSM — используем Тимирязева 46 (точка на ул. Тимирязева в ЧЛБ).
-- Остальные 14 — реальные здания по Nominatim. Перед продакшеном: revert_shop_pickup_override.sql
-- =============================================================================

WITH addresses(n, addr, lat, lon) AS (
  VALUES
    (1,  'Цвиллинга 36',          55.161113, 61.403656),
    (2,  'Худякова 4',            55.147926, 61.378808),
    (3,  'Молодогвардейцев 70',   55.179431, 61.327614),
    (4,  'Энтузиастов 14',        55.155843, 61.375355),
    (5,  'Бейвеля 6',             55.193071, 61.280771),
    (6,  'Братьев Кашириных 152', 55.166539, 61.294155),
    (7,  'Красная 38',            55.159024, 61.392802),
    (8,  'Проспект Ленина 83',    55.158297, 61.371382),
    (9,  'Воровского 28',         55.150823, 61.389017),
    (10, 'Свободы 97',            55.161929, 61.411979),
    (11, 'Кирова 84',             55.167059, 61.400038),
    (12, 'Свердловский пр. 59',   55.164701, 61.390954),
    (13, 'Худякова 12',           55.148105, 61.371656),
    (14, 'Цвиллинга 25',          55.164807, 61.405594),
    (15, 'Тимирязева 46',         55.158000, 61.409000)
),
orders_shop_hudyakova AS (
  SELECT o.id AS order_id, ROW_NUMBER() OVER (ORDER BY o.id) AS rn
  FROM orders o
  JOIN shops s ON s.id = o.shop_id
  WHERE s.pickup_address ILIKE '%худякова%13%' OR s.pickup_address ILIKE '%Худякова%13%'
),
keep_two AS (
  SELECT order_id FROM orders_shop_hudyakova ORDER BY order_id LIMIT 2
),
to_update AS (
  SELECT osh.order_id, ROW_NUMBER() OVER (ORDER BY osh.order_id) AS rn
  FROM orders_shop_hudyakova osh
  WHERE osh.order_id NOT IN (SELECT order_id FROM keep_two)
),
assigned AS (
  SELECT t.order_id, a.addr, CAST(a.lat AS DECIMAL(10,8)) AS lat, CAST(a.lon AS DECIMAL(11,8)) AS lon
  FROM to_update t
  JOIN addresses a ON a.n = ((t.rn - 1) % 15) + 1
)
UPDATE orders o
SET shop_pickup_address_override = a.addr,
    shop_pickup_latitude         = a.lat,
    shop_pickup_longitude        = a.lon
FROM assigned a
WHERE o.id = a.order_id;
