package org.example.flower_delivery.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class OrderCreationData {
    // текущий шаг
    private OrderCreationState state = OrderCreationState.NONE;

    // Введённые данные (заполняются по мере прохождения шагов)
    // дата доставки (сегодня или завтра)
    private LocalDate deliveryDate;

    // имя получателя
    private String recipientName;

    //номер тел
    private String recipientPhone;

    // адрес (полный: улица, дом, подъезд, квартира)
    private String deliveryAddress;
    
    // координаты доставки (от геокодирования)
    private Double deliveryLatitude;
    private Double deliveryLongitude;
    
    // расстояние в км (от магазина до точки доставки)
    private Double distanceKm;
    
    // рекомендуемая цена (автоматически рассчитанная)
    private BigDecimal suggestedPrice;

    //цена (финальная)
    private BigDecimal deliveryPrice;

    // коментарий
    private String comment;


}
