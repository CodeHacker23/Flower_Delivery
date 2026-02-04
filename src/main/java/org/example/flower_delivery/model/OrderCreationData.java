package org.example.flower_delivery.model;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Данные для создания заказа (хранятся в памяти во время диалога).
 * 
 * Поддерживает как обычные заказы (1 точка), так и мультиадресные (2+ точек).
 */
@Data
public class OrderCreationData {
    
    // ============================================
    // СОСТОЯНИЕ
    // ============================================
    
    /** Текущий шаг создания заказа */
    private OrderCreationState state = OrderCreationState.NONE;

    // ============================================
    // ОБЩИЕ ДАННЫЕ ЗАКАЗА
    // ============================================
    
    /** Дата доставки (сегодня или завтра) */
    private LocalDate deliveryDate;
    
    /** Комментарий к заказу (общий) */
    private String comment;

    // ============================================
    // ТОЧКИ ДОСТАВКИ
    // ============================================
    
    /** Список точек доставки (для мультиадресных заказов) */
    private List<StopData> stops = new ArrayList<>();
    
    /** Текущая точка, которую заполняем */
    private StopData currentStop;

    // ============================================
    // ДАННЫЕ ПЕРВОЙ ТОЧКИ (для совместимости)
    // ============================================
    // Эти поля используются для первой точки,
    // потом переносятся в stops.get(0)
    
    /** Имя получателя (первая точка) */
    private String recipientName;

    /** Телефон получателя (первая точка) */
    private String recipientPhone;

    /** Адрес доставки (первая точка) */
    private String deliveryAddress;
    
    /** Широта (первая точка) */
    private Double deliveryLatitude;
    
    /** Долгота (первая точка) */
    private Double deliveryLongitude;
    
    /** Расстояние в км (первая точка) */
    private Double distanceKm;
    
    /** Рекомендуемая цена (первая точка) */
    private BigDecimal suggestedPrice;

    /** Финальная цена (первая точка) */
    private BigDecimal deliveryPrice;

    // ============================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ============================================

    /**
     * Это мультиадресный заказ?
     */
    public boolean isMultiStop() {
        return stops.size() > 1;
    }

    /**
     * Получить общую стоимость доставки.
     */
    public BigDecimal getTotalPrice() {
        if (stops.isEmpty()) {
            return deliveryPrice != null ? deliveryPrice : BigDecimal.ZERO;
        }
        return stops.stream()
                .map(StopData::getDeliveryPrice)
                .filter(p -> p != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Начать заполнение новой точки.
     */
    public void startNewStop() {
        currentStop = new StopData();
        currentStop.setStopNumber(stops.size() + 1);
    }

    /**
     * Сохранить текущую точку в список.
     */
    public void saveCurrentStop() {
        if (currentStop != null) {
            stops.add(currentStop);
            currentStop = null;
        }
    }

    /**
     * Сохранить данные первой точки в список stops.
     * Вызывается после заполнения первой точки.
     */
    public void saveFirstStopFromFields() {
        StopData firstStop = new StopData();
        firstStop.setStopNumber(1);
        firstStop.setRecipientName(recipientName);
        firstStop.setRecipientPhone(recipientPhone);
        firstStop.setDeliveryAddress(deliveryAddress);
        firstStop.setDeliveryLatitude(deliveryLatitude);
        firstStop.setDeliveryLongitude(deliveryLongitude);
        firstStop.setDistanceKm(distanceKm);
        firstStop.setDeliveryPrice(deliveryPrice);
        
        if (stops.isEmpty()) {
            stops.add(firstStop);
        } else {
            stops.set(0, firstStop);
        }
    }

    /**
     * Получить последнюю точку (для расчёта расстояния).
     */
    public StopData getLastStop() {
        if (stops.isEmpty()) {
            return null;
        }
        return stops.get(stops.size() - 1);
    }

    /**
     * Получить координаты последней точки.
     * Нужно для расчёта расстояния до следующей.
     */
    public double[] getLastStopCoordinates() {
        StopData last = getLastStop();
        if (last != null && last.getDeliveryLatitude() != null && last.getDeliveryLongitude() != null) {
            return new double[] { last.getDeliveryLatitude(), last.getDeliveryLongitude() };
        }
        // Если нет точек — возвращаем координаты первой точки из полей
        if (deliveryLatitude != null && deliveryLongitude != null) {
            return new double[] { deliveryLatitude, deliveryLongitude };
        }
        return null;
    }

    // ============================================
    // ВЛОЖЕННЫЙ КЛАСС: ДАННЫЕ ОДНОЙ ТОЧКИ
    // ============================================

    /**
     * Данные одной точки доставки.
     */
    @Data
    public static class StopData {
        /** Номер точки (1, 2, 3...) */
        private Integer stopNumber;
        
        /** Имя получателя */
        private String recipientName;
        
        /** Телефон получателя */
        private String recipientPhone;
        
        /** Адрес доставки */
        private String deliveryAddress;
        
        /** Широта */
        private Double deliveryLatitude;
        
        /** Долгота */
        private Double deliveryLongitude;
        
        /** Расстояние до этой точки (от предыдущей) */
        private Double distanceKm;
        
        /** Рекомендуемая цена */
        private BigDecimal suggestedPrice;
        
        /** Финальная цена */
        private BigDecimal deliveryPrice;
        
        /** Комментарий к этой точке */
        private String comment;
    }
}
