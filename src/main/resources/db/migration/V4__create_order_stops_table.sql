-- ============================================
-- Миграция V4: Создание таблицы точек доставки (order_stops)
-- ============================================
-- Позволяет создавать мультиадресные заказы:
-- один заказ → несколько точек доставки
-- Пример: магазин создаёт 2 букета на соседние дома
-- ============================================

CREATE TABLE IF NOT EXISTS order_stops (
    -- Первичный ключ
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Связь с заказом
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    
    -- Номер точки в маршруте (1, 2, 3...)
    -- 1 = первая точка (основная), 2+ = дополнительные
    stop_number INT NOT NULL CHECK (stop_number >= 1),
    
    -- Информация о получателе этой точки
    recipient_name VARCHAR(255) NOT NULL,
    recipient_phone VARCHAR(50) NOT NULL,
    
    -- Адрес доставки
    delivery_address VARCHAR(500) NOT NULL,
    
    -- Координаты (для расчёта расстояния между точками)
    delivery_latitude DECIMAL(10, 8),
    delivery_longitude DECIMAL(11, 8),
    
    -- Стоимость доставки ДО ЭТОЙ точки
    -- Точка 1: от магазина до точки 1
    -- Точка 2: от точки 1 до точки 2
    delivery_price DECIMAL(10, 2) NOT NULL CHECK (delivery_price >= 0),
    
    -- Расстояние до этой точки (км)
    -- Для точки 1: от магазина
    -- Для точки 2+: от предыдущей точки
    distance_km DECIMAL(6, 2),
    
    -- Комментарий к этой точке
    comment TEXT,
    
    -- Статус доставки этой точки
    -- PENDING = ещё не доставлено
    -- DELIVERED = доставлено
    -- FAILED = не удалось доставить
    stop_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (stop_status IN ('PENDING', 'DELIVERED', 'FAILED')),
    
    -- Когда доставлено (для каждой точки отдельно)
    delivered_at TIMESTAMP,
    
    -- Временные метки
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Уникальность: один номер точки на заказ
    UNIQUE (order_id, stop_number)
);

-- ============================================
-- Индексы
-- ============================================

-- Индекс по заказу (для получения всех точек заказа)
CREATE INDEX idx_order_stops_order_id ON order_stops(order_id);

-- Индекс по статусу (для поиска недоставленных точек)
CREATE INDEX idx_order_stops_status ON order_stops(stop_status);

-- Составной индекс: заказ + номер точки (для сортировки)
CREATE INDEX idx_order_stops_order_number ON order_stops(order_id, stop_number);

-- ============================================
-- Добавляем поле в orders для отметки мультиадресных заказов
-- ============================================

-- Флаг: это мультиадресный заказ?
ALTER TABLE orders ADD COLUMN IF NOT EXISTS is_multi_stop BOOLEAN NOT NULL DEFAULT FALSE;

-- Общая стоимость доставки (сумма всех точек)
-- Для мультиадресных заказов delivery_price в orders = сумма всех stop.delivery_price
ALTER TABLE orders ADD COLUMN IF NOT EXISTS total_stops INT NOT NULL DEFAULT 1;

-- ============================================
-- Комментарии
-- ============================================

COMMENT ON TABLE order_stops IS 'Точки доставки для мультиадресных заказов';
COMMENT ON COLUMN order_stops.id IS 'Уникальный идентификатор точки';
COMMENT ON COLUMN order_stops.order_id IS 'Заказ, к которому относится точка';
COMMENT ON COLUMN order_stops.stop_number IS 'Порядковый номер точки в маршруте (1, 2, 3...)';
COMMENT ON COLUMN order_stops.recipient_name IS 'Имя получателя на этой точке';
COMMENT ON COLUMN order_stops.recipient_phone IS 'Телефон получателя на этой точке';
COMMENT ON COLUMN order_stops.delivery_address IS 'Адрес доставки';
COMMENT ON COLUMN order_stops.delivery_price IS 'Стоимость доставки до этой точки';
COMMENT ON COLUMN order_stops.distance_km IS 'Расстояние до этой точки (от предыдущей)';
COMMENT ON COLUMN order_stops.stop_status IS 'Статус доставки: PENDING, DELIVERED, FAILED';
COMMENT ON COLUMN order_stops.delivered_at IS 'Время доставки на эту точку';

COMMENT ON COLUMN orders.is_multi_stop IS 'Флаг мультиадресного заказа (несколько точек)';
COMMENT ON COLUMN orders.total_stops IS 'Количество точек доставки в заказе';
