package org.example.flower_delivery.model;

/**
 * Состояния заказа магазина.
 * <p>
 * Каждый шаг — это один вопрос от бота.
 * Юзер отвечает → переходим на следующий шаг.
 */
public enum OrderCreationState {
    //— не в процессе создания
    NONE,

    // ждём дату доставки (сегодня/завтра)
    WAITING_DELIVERY_DATE,

    // ждём имя
    WAITING_RECIPIENT_NAME,

    //ждем тел
    WAITING_RECIPIENT_PHONE,

    //ждем адрес (полный: улица, дом, подъезд, квартира)
    WAITING_DELIVERY_ADDRESS,

    // ждём подтверждения автоматической цены или ввода своей
    WAITING_PRICE_CONFIRMATION,

    //ждем цену (ручной ввод, если геокодирование не удалось)
    WAITING_DELIVERY_PRICE,

    //комментариий к заказу
    WAITING_COMMENT,

    //подтверждение заказа кнопка что точно создать.
    WAITING_CONFIRMATION

}
