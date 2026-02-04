package org.example.flower_delivery.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Точка доставки в мультиадресном заказе.
 * 
 * Один заказ может иметь несколько точек доставки:
 * - Магазин создаёт заказ на 2 букета на соседние дома
 * - Точка 1: Ленина 15 → 400₽
 * - Точка 2: Ленина 17 → 150₽ (от точки 1)
 * - Итого: 550₽
 */
@Entity
@Table(name = "order_stops")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderStop {

    /**
     * Уникальный идентификатор точки.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Заказ, к которому относится эта точка.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /**
     * Порядковый номер точки в маршруте.
     * 1 = первая точка (основная)
     * 2, 3, ... = дополнительные точки
     */
    @Column(name = "stop_number", nullable = false)
    private Integer stopNumber;

    // ============================================
    // ИНФОРМАЦИЯ О ПОЛУЧАТЕЛЕ
    // ============================================

    /**
     * Имя получателя на этой точке.
     */
    @Column(name = "recipient_name", nullable = false, length = 255)
    private String recipientName;

    /**
     * Телефон получателя на этой точке.
     */
    @Column(name = "recipient_phone", nullable = false, length = 50)
    private String recipientPhone;

    // ============================================
    // АДРЕС И КООРДИНАТЫ
    // ============================================

    /**
     * Адрес доставки.
     */
    @Column(name = "delivery_address", nullable = false, length = 500)
    private String deliveryAddress;

    /**
     * Широта адреса доставки.
     */
    @Column(name = "delivery_latitude", precision = 10, scale = 8)
    private BigDecimal deliveryLatitude;

    /**
     * Долгота адреса доставки.
     */
    @Column(name = "delivery_longitude", precision = 11, scale = 8)
    private BigDecimal deliveryLongitude;

    // ============================================
    // СТОИМОСТЬ И РАССТОЯНИЕ
    // ============================================

    /**
     * Стоимость доставки ДО этой точки.
     * - Для точки 1: от магазина до точки 1
     * - Для точки 2+: от предыдущей точки до этой
     */
    @Column(name = "delivery_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal deliveryPrice;

    /**
     * Расстояние до этой точки (в км).
     * - Для точки 1: от магазина
     * - Для точки 2+: от предыдущей точки
     */
    @Column(name = "distance_km", precision = 6, scale = 2)
    private BigDecimal distanceKm;

    // ============================================
    // КОММЕНТАРИЙ
    // ============================================

    /**
     * Комментарий к этой точке.
     * Примеры: "Домофон 123", "Позвонить заранее"
     */
    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    // ============================================
    // СТАТУС ДОСТАВКИ
    // ============================================

    /**
     * Статус доставки этой точки.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "stop_status", nullable = false, length = 20)
    @Builder.Default
    private StopStatus stopStatus = StopStatus.PENDING;

    /**
     * Когда точка была доставлена.
     */
    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    // ============================================
    // ВРЕМЕННЫЕ МЕТКИ
    // ============================================

    /**
     * Когда точка создана.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ============================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ============================================

    /**
     * Это первая (основная) точка?
     */
    public boolean isFirstStop() {
        return stopNumber != null && stopNumber == 1;
    }

    /**
     * Это дополнительная точка?
     */
    public boolean isAdditionalStop() {
        return stopNumber != null && stopNumber > 1;
    }

    /**
     * Точка доставлена?
     */
    public boolean isDelivered() {
        return stopStatus == StopStatus.DELIVERED;
    }

    /**
     * Пометить точку как доставленную.
     */
    public void markDelivered() {
        this.stopStatus = StopStatus.DELIVERED;
        this.deliveredAt = LocalDateTime.now();
    }

    /**
     * Есть ли координаты?
     */
    public boolean hasCoordinates() {
        return deliveryLatitude != null && deliveryLongitude != null;
    }
}
