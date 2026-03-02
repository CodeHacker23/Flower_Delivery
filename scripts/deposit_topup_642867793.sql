-- Пополнение баланса курьера telegram_id = 642867793. Выполнить в PostgreSQL.

DO $$
DECLARE
    v_courier_id UUID;
    v_amount DECIMAL(10,2) := 6000;
    v_telegram_id BIGINT := 642867793;
BEGIN
    SELECT c.id INTO v_courier_id
    FROM couriers c
    JOIN users u ON c.user_id = u.id
    WHERE u.telegram_id = v_telegram_id;

    IF v_courier_id IS NULL THEN
        RAISE EXCEPTION 'Курьер с telegram_id=% не найден', v_telegram_id;
    END IF;

    UPDATE couriers SET balance = COALESCE(balance, 0) + v_amount WHERE id = v_courier_id;
    INSERT INTO courier_transactions (courier_id, type, amount, description)
    VALUES (v_courier_id, 'DEPOSIT_TOP_UP', v_amount, 'Ручное пополнение');

    RAISE NOTICE 'Депозит пополнен на % руб. Курьер id=%', v_amount, v_courier_id;
END $$;
