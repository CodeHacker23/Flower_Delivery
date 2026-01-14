-- ============================================
-- Это первая миграция - создает таблицу пользователей
-- Flyway автоматически выполнит этот SQL при запуске приложения
--
-- В PostgreSQL используем UUID для ID (универсально, безопасно)
-- telegram_id - уникальный ID пользователя в Telegram (BigInt, как у Telegram)
-- role - роль пользователя (enum: COURIER, SHOP, ADMIN)
-- is_active - флаг активности (активируется админом)
-- created_at, updated_at - временные метки (когда создан, когда обновлен)


CREATE TABLE IF NOT EXISTS users (
    -- UUID - это уникальный идентификатор (как ID паспорта, только для строки в БД)
    -- PRIMARY KEY - это как уникальный номер паспорта, не может быть двух одинаковых
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- telegram_id - это ID пользователя в Telegram (BigInt = большое число)
    -- UNIQUE - значит не может быть двух пользователей с одинаковым telegram_id
    -- NOT NULL - значит обязательно должен быть (не может быть пустым)
    telegram_id BIGINT UNIQUE NOT NULL,

    -- full_name - ФИО пользователя
    full_name VARCHAR(255) NOT NULL,

    -- phone - телефон (может быть пустым при регистрации, потом заполнится)
    phone VARCHAR(50),

    -- role - роль пользователя (enum = перечисление: только COURIER, SHOP или ADMIN)
    -- По умолчанию будет NULL (неопределена), админ потом назначит роль
    role VARCHAR(20) CHECK (role IN ('COURIER', 'SHOP', 'ADMIN')),

    -- is_active - флаг активности (по умолчанию false = неактивен, админ активирует)
    is_active BOOLEAN DEFAULT FALSE NOT NULL,

    -- created_at - когда создан (автоматически при создании)
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,

    -- updated_at - когда обновлен (будет обновляться при изменении)
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- Создаем индекс на telegram_id для быстрого поиска
-- Индекс = как алфавитный указатель в книге - быстрее находить нужную строку
CREATE INDEX IF NOT EXISTS idx_users_telegram_id ON users(telegram_id);

-- Создаем индекс на role для быстрой фильтрации по ролям
CREATE INDEX IF NOT EXISTS idx_users_role ON users(role);

-- Комментарии к таблице (для документации в БД)
COMMENT ON TABLE users IS 'Таблица пользователей бота (курьеры, магазины, админы)';
COMMENT ON COLUMN users.telegram_id IS 'Уникальный ID пользователя в Telegram';
COMMENT ON COLUMN users.role IS 'Роль пользователя: COURIER, SHOP или ADMIN';
COMMENT ON COLUMN users.is_active IS 'Флаг активности (активируется админом)';
