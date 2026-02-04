package org.example.flower_delivery.model;

/**
 * Состояния заказа магазина.
 * <p>
 * Каждый шаг — это один вопрос от бота.
 * Юзер отвечает → переходим на следующий шаг.
 * 
 * ФЛОУ ДЛЯ ОБЫЧНОГО ЗАКАЗА (1 точка):
 * DELIVERY_DATE → RECIPIENT_NAME → RECIPIENT_PHONE → 
 * DELIVERY_ADDRESS → PRICE_CONFIRMATION → COMMENT → CONFIRMATION
 * 
 * ФЛОУ ДЛЯ МУЛЬТИАДРЕСНОГО ЗАКАЗА (2+ точек):
 * ... → PRICE_CONFIRMATION → ASK_ADDITIONAL_STOP → 
 * ADDITIONAL_NAME → ADDITIONAL_PHONE → ADDITIONAL_ADDRESS → 
 * ADDITIONAL_PRICE → (снова ASK_ADDITIONAL_STOP или COMMENT)
 */
public enum OrderCreationState {
    // ============================================
    // ОБЩИЕ СОСТОЯНИЯ
    // ============================================
    
    /** Не в процессе создания */
    NONE,

    /** Ждём дату доставки (сегодня/завтра) */
    WAITING_DELIVERY_DATE,

    // ============================================
    // ПЕРВАЯ ТОЧКА (основная)
    // ============================================

    /** Ждём имя получателя */
    WAITING_RECIPIENT_NAME,

    /** Ждём телефон получателя */
    WAITING_RECIPIENT_PHONE,

    /** Ждём адрес доставки */
    WAITING_DELIVERY_ADDRESS,

    /** Ждём подтверждения автоматической цены */
    WAITING_PRICE_CONFIRMATION,

    /** Ждём ручной ввод цены (если геокодирование не удалось) */
    WAITING_DELIVERY_PRICE,

    /** Ждём комментарий к первой точке */
    WAITING_STOP_COMMENT,

    // ============================================
    // ДОПОЛНИТЕЛЬНЫЕ ТОЧКИ (мультиадрес)
    // ============================================

    /** Спрашиваем: добавить ещё один адрес? */
    WAITING_ASK_ADDITIONAL_STOP,

    /** Ждём имя получателя дополнительной точки */
    WAITING_ADDITIONAL_RECIPIENT_NAME,

    /** Ждём телефон получателя дополнительной точки */
    WAITING_ADDITIONAL_RECIPIENT_PHONE,

    /** Ждём адрес дополнительной точки */
    WAITING_ADDITIONAL_ADDRESS,

    /** Ждём подтверждения цены дополнительной точки */
    WAITING_ADDITIONAL_PRICE_CONFIRMATION,

    /** Ждём ручной ввод цены дополнительной точки */
    WAITING_ADDITIONAL_PRICE,

    /** Ждём комментарий к дополнительной точке */
    WAITING_ADDITIONAL_STOP_COMMENT,

    // ============================================
    // ЗАВЕРШЕНИЕ
    // ============================================

    /** Ждём финального подтверждения (общий комментарий убран — комменты теперь у каждой точки) */
    WAITING_COMMENT,

    /** Ждём финального подтверждения */
    WAITING_CONFIRMATION

}
