package org.example.flower_delivery.model;

import lombok.Data;

/**
 * Временные данные регистрации магазина.
 *
 * Хранит:
 * - Текущий шаг диалога (state)
 * - Уже введённые данные (shopName, pickupAddress, phone)
 *
 * Живёт в памяти, пока юзер не закончит регистрацию.
 */
@Data
public class ShopRegistrationData {
    
    // Текущий шаг диалога
    private RegistrationState state = RegistrationState.NONE;

    // Введённые данные (заполняются по мере прохождения шагов)
    private String shopName;
    private String pickupAddress;
    private String phone;
}
