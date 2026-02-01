-- ============================================
-- Миграция V3: Создание таблицы заказов (orders)
-- ============================================

CREATE TABLE IF NOT EXISTS orders (
    -- Первичный ключ
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    -- Связь с магазином (кто создал заказ)
    shop_id UUID NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
    
    -- Связь с курьером (кто выполняет заказ)
    -- NULL = заказ ещё не взят курьером
    courier_id UUID REFERENCES users(id) ON DELETE SET NULL,
    
    -- Информация о получателе
    recipient_name VARCHAR(255) NOT NULL,
    recipient_phone VARCHAR(50) NOT NULL,
    delivery_address VARCHAR(500) NOT NULL,
    
    -- Координаты доставки (для карт)
    -- NULL = адрес ещё не геокодирован
    delivery_latitude DECIMAL(10, 8),
    delivery_longitude DECIMAL(11, 8),
    
    -- Стоимость доставки (минимум 300₽)
    delivery_price DECIMAL(10, 2) NOT NULL CHECK (delivery_price >= 300),
    
    -- Комментарий к заказу (домофон, этаж и т.д.)
    comment TEXT,
    
    -- Статус заказа
    status VARCHAR(20) NOT NULL DEFAULT 'NEW'
        CHECK (status IN ('NEW', 'ACCEPTED', 'IN_SHOP', 'PICKED_UP', 'ON_WAY', 'DELIVERED', 'RETURNED', 'CANCELLED')),
    
    -- Дата доставки (сегодня или завтра)
    delivery_date DATE NOT NULL DEFAULT CURRENT_DATE,
    
    -- Временные метки
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    accepted_at TIMESTAMP,    -- Когда курьер принял заказ
    picked_up_at TIMESTAMP,   -- Когда курьер забрал букет из магазина
    delivered_at TIMESTAMP    -- Когда заказ доставлен
);

-- ============================================
-- Индексы для быстрого поиска
-- ============================================

-- Индекс по магазину (для "Мои заказы")
CREATE INDEX idx_orders_shop_id ON orders(shop_id);

-- Индекс по курьеру (для заказов курьера)
CREATE INDEX idx_orders_courier_id ON orders(courier_id);

-- Индекс по статусу (для поиска новых/активных заказов)
CREATE INDEX idx_orders_status ON orders(status);

-- Индекс по дате создания (для сортировки)
CREATE INDEX idx_orders_created_at ON orders(created_at DESC);

-- Составной индекс: магазин + статус (частый запрос)
CREATE INDEX idx_orders_shop_status ON orders(shop_id, status);

-- Индекс по дате доставки (для фильтрации заказов на сегодня/завтра)
CREATE INDEX idx_orders_delivery_date ON orders(delivery_date);

-- ============================================
-- Комментарии к таблице
-- ============================================

COMMENT ON TABLE orders IS 'Заказы на доставку цветов';
COMMENT ON COLUMN orders.id IS 'Уникальный идентификатор заказа';
COMMENT ON COLUMN orders.shop_id IS 'Магазин, создавший заказ';
COMMENT ON COLUMN orders.courier_id IS 'Курьер, выполняющий заказ (NULL = не взят)';
COMMENT ON COLUMN orders.recipient_name IS 'Имя получателя';
COMMENT ON COLUMN orders.recipient_phone IS 'Телефон получателя';
COMMENT ON COLUMN orders.delivery_address IS 'Полный адрес доставки (улица, дом, подъезд, кв.)';
COMMENT ON COLUMN orders.delivery_price IS 'Стоимость доставки (минимум 300₽)';
COMMENT ON COLUMN orders.status IS 'Статус заказа: NEW, ACCEPTED, IN_SHOP, PICKED_UP, ON_WAY, DELIVERED, RETURNED, CANCELLED';
COMMENT ON COLUMN orders.delivery_date IS 'Дата доставки (сегодня или завтра)';
COMMENT ON COLUMN orders.accepted_at IS 'Время принятия заказа курьером (дедлайн = accepted_at + 30 мин)';
