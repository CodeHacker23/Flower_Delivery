package org.example.flower_delivery.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Order {

    /**
     * Уникальный идентификатор заказа.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Магазин, создавший заказ.
     *
     * ManyToOne — много заказов могут принадлежать одному магазину.
     * FetchType.LAZY — не загружать магазин сразу (только когда понадобится).
     */

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shop_id", nullable = false)
    private Shop shop;

    /**
     * Курьер, выполняющий заказ.
     * <p>
     * NULL — заказ ещё не взят курьером (статус NEW).
     * ManyToOne — много заказов могут выполняться одним курьером.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "courier_id")
    private User courier;

    // ============================================
    // ИНФОРМАЦИЯ О ПОЛУЧАТЕЛЕ
    // ============================================

    /**
     * Имя получателя букета.
     * Примеры: "Анна", "Мария Ивановна"
     */

    @Column(name = "recipient_name", nullable = false, length = 255)
    private String recipientName;


    /**
     * Телефон получателя.
     * Курьер звонит по этому номеру при доставке.
     */
    @Column(name = "recipient_phone", nullable = false, length = 50)
    private String recipientPhone;

    /**
     * Адрес доставки.
     * Куда курьер везёт букет.
     */
    @Column(name = "delivery_address", nullable = false, length = 500)
    private String deliveryAddress;

    // ============================================
    // КООРДИНАТЫ ДОСТАВКИ
    // ============================================

    /**
     * Широта адреса доставки (для карт).
     * NULL — адрес ещё не геокодирован.
     */
    @Column(name = "delivery_latitude", precision = 10, scale = 8)
    private BigDecimal deliveryLatitude;

    /**
     * Долгота адреса доставки (для карт).
     * NULL — адрес ещё не геокодирован.
     */

    @Column(name = "delivery_longitude", precision = 11, scale = 8)
    private BigDecimal deliveryLongitude;

    // ============================================
    // СТОИМОСТЬ
    // ============================================

    /**
     * Стоимость доставки.
     * Сколько получатель платит курьеру за доставку.
     */

    @Column(name =  "delivery_price",  nullable = false, precision = 10, scale = 2)
    private BigDecimal deliveryPrice;



    // ============================================
    // ДЕТАЛИ ДОСТАВКИ
    // ============================================

    /**
     * Комментарий к заказу.
     * Примеры: "Домофон 123", "Позвонить за 30 минут"
     */
    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    // ============================================
    // СТАТУС
    // ============================================

    /**
     * Текущий статус заказа.
     *
     * @Enumerated(EnumType.STRING) — сохраняет как строку "NEW", "DELIVERED" и т.д.
     * @Builder.Default — при использовании Builder по умолчанию NEW.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private OrderStatus status = OrderStatus.NEW;

    // ============================================
    // ДАТА ДОСТАВКИ
    // ============================================

    /**
     * Дата доставки (сегодня или завтра).
     * Магазин может создавать заказы заранее на следующий день.
     */
    @Column(name = "delivery_date", nullable = false)
    private LocalDate deliveryDate;

    // ============================================
    // ВРЕМЕННЫЕ МЕТКИ
    // ============================================

    /**
     * Когда заказ создан.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Когда заказ последний раз обновлялся.
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Когда курьер принял заказ.
     * NULL — ещё не принят.
     */
    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    /**
     * Когда курьер забрал букет из магазина.
     * NULL — ещё не забрал.
     */
    @Column(name = "picked_up_at")
    private LocalDateTime pickedUpAt;

    /**
     * Когда заказ доставлен.
     * NULL — ещё не доставлен.
     */
    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    // ============================================
    // МУЛЬТИАДРЕСНАЯ ДОСТАВКА
    // ============================================

    /**
     * Флаг мультиадресного заказа.
     * true = заказ имеет несколько точек доставки.
     */
    @Column(name = "is_multi_stop", nullable = false)
    @Builder.Default
    private Boolean isMultiStop = false;

    /**
     * Количество точек доставки.
     * 1 = обычный заказ
     * 2+ = мультиадресный заказ
     */
    @Column(name = "total_stops", nullable = false)
    @Builder.Default
    private Integer totalStops = 1;

    /**
     * Точки доставки для мультиадресных заказов.
     * Для обычных заказов — пустой список.
     * 
     * mappedBy = "order" — связь определена в OrderStop.order
     * cascade = ALL — при удалении заказа удаляются и все точки
     * orphanRemoval = true — при удалении точки из списка она удаляется из БД
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stopNumber ASC")
    @Builder.Default
    private List<OrderStop> stops = new ArrayList<>();

    // ============================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ============================================

    /**
     * Проверить, назначен ли курьер.
     */
    public boolean hasCourier() {
        return courier != null;
    }

    /**
     * Проверить, можно ли взять заказ (только NEW заказы).
     */
    public boolean isAvailable() {
        return status == OrderStatus.NEW;
    }

    /**
     * Проверить, завершён ли заказ (доставлен, возвращён или отменён).
     */
    public boolean isCompleted() {
        return status == OrderStatus.DELIVERED ||
                status == OrderStatus.RETURNED ||
                status == OrderStatus.CANCELLED;
    }

    /**
     * Дедлайн доставки = acceptedAt + 30 минут.
     * Курьер должен доставить заказ до этого времени.
     *
     * @return дедлайн или null если заказ ещё не взят курьером
     */
    public LocalDateTime getDeadline() {
        if (acceptedAt == null) {
            return null;
        }
        return acceptedAt.plusMinutes(30);
    }

    /**
     * Проверить, просрочен ли заказ.
     *
     * @return true если дедлайн прошёл, false если ещё есть время или заказ не взят
     */
    public boolean isOverdue() {
        LocalDateTime deadline = getDeadline();
        if (deadline == null) {
            return false;
        }
        return LocalDateTime.now().isAfter(deadline);
    }

    // ============================================
    // МЕТОДЫ ДЛЯ МУЛЬТИАДРЕСНЫХ ЗАКАЗОВ
    // ============================================

    /**
     * Это мультиадресный заказ?
     */
    public boolean isMultiStopOrder() {
        return Boolean.TRUE.equals(isMultiStop) || totalStops > 1;
    }

    /**
     * Добавить точку доставки.
     * Автоматически обновляет счётчики.
     *
     * @param stop точка доставки
     */
    public void addStop(OrderStop stop) {
        stops.add(stop);
        stop.setOrder(this);
        this.totalStops = stops.size();
        this.isMultiStop = stops.size() > 1;
    }

    /**
     * Получить общую стоимость доставки (сумма всех точек).
     *
     * @return сумма deliveryPrice всех точек
     */
    public BigDecimal getTotalDeliveryPrice() {
        if (stops == null || stops.isEmpty()) {
            return deliveryPrice;
        }
        return stops.stream()
                .map(OrderStop::getDeliveryPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Все точки доставлены?
     *
     * @return true если все точки имеют статус DELIVERED
     */
    public boolean areAllStopsDelivered() {
        if (stops == null || stops.isEmpty()) {
            return status == OrderStatus.DELIVERED;
        }
        return stops.stream().allMatch(OrderStop::isDelivered);
    }

    /**
     * Получить следующую недоставленную точку.
     *
     * @return следующая точка со статусом PENDING или empty
     */
    public java.util.Optional<OrderStop> getNextPendingStop() {
        if (stops == null) {
            return java.util.Optional.empty();
        }
        return stops.stream()
                .filter(s -> s.getStopStatus() == StopStatus.PENDING)
                .findFirst();
    }

    /**
     * Получить краткое описание маршрута.
     * Пример: "Ленина 15 → Ленина 17 → Труда 10"
     */
    public String getRouteDescription() {
        if (stops == null || stops.isEmpty()) {
            return deliveryAddress;
        }
        return stops.stream()
                .map(OrderStop::getDeliveryAddress)
                .reduce((a, b) -> a + " → " + b)
                .orElse(deliveryAddress);
    }
}
