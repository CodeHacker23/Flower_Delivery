package org.example.flower_delivery.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Снимок геолокации курьера в момент подтверждения статуса заказа.
 * Используется для проверки при отмене: был ли курьер в магазине / у получателя (радиус 200 м).
 */
@Entity
@Table(name = "order_status_geo_snapshots")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderStatusGeoSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    /**
     * Статус, при котором курьер отправил локацию: IN_SHOP или DELIVERED.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status;

    @Column(name = "courier_lat", nullable = false, precision = 10, scale = 8)
    private BigDecimal courierLat;

    @Column(name = "courier_lon", nullable = false, precision = 11, scale = 8)
    private BigDecimal courierLon;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
