package org.example.flower_delivery.model;

/**
 * Состояния диалога регистрации магазина.
 *
 * Каждый шаг — это один вопрос от бота.
 * Юзер отвечает → переходим на следующий шаг.
 */
public enum RegistrationState {

    // Ожидание ввода названия магазина
    WAITING_SHOP_NAME,

    // Ожидание ввода адреса забора
    WAITING_PICKUP_ADDRESS,

    // Ожидание ввода телефона
    WAITING_PHONE,

    // Регистрация завершена (или юзер не в процессе регистрации)
    NONE
}


