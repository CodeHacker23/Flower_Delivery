-- Запрос магазину: «Курьер забрал заказ?» ДА/Нет. Когда курьер переводит в «В путь», магазину уходит сообщение с кнопками.
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS shop_pickup_confirmation_requested_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS shop_pickup_confirmed BOOLEAN,
    ADD COLUMN IF NOT EXISTS shop_pickup_confirmed_at TIMESTAMP;

COMMENT ON COLUMN orders.shop_pickup_confirmation_requested_at IS 'Когда отправлен запрос магазину подтвердить передачу заказа курьеру';
COMMENT ON COLUMN orders.shop_pickup_confirmed IS 'Ответ магазина: true = ДА, false = Нет, NULL = ещё не ответил';
COMMENT ON COLUMN orders.shop_pickup_confirmed_at IS 'Когда магазин нажал ДА/Нет';
