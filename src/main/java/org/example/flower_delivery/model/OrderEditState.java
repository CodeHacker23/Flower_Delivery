package org.example.flower_delivery.model;

import lombok.Data;

import java.util.UUID;

/**
 * Состояние редактирования заказа (хранится в памяти, пока юзер вводит новое значение).
 * 
 * Когда магазин нажал "Редактировать" → "Точка 2" → "Адрес",
 * мы показываем "Введите новый адрес" и запоминаем: orderId, stopNumber=2, field=address.
 * Следующее текстовое сообщение от этого юзера — новое значение, сохраняем и сбрасываем состояние.
 */
@Data
public class OrderEditState {
    
    private UUID orderId;
    /** Номер точки (1, 2, 3...) или 1 для обычного заказа без order_stops */
    private int stopNumber;
    /** Какое поле редактируем: address, phone, comment, date */
    private String field;
}
