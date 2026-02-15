package org.example.flower_delivery.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.OrderStatus;
import org.example.flower_delivery.model.OrderStatusGeoSnapshot;
import org.example.flower_delivery.repository.OrderStatusGeoSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Сервис для сохранения снимков геолокации курьера при подтверждении статусов заказа.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CourierGeoService {

    private final OrderStatusGeoSnapshotRepository snapshotRepository;

    @Transactional
    public OrderStatusGeoSnapshot saveSnapshot(UUID orderId, OrderStatus status, double lat, double lon) {
        OrderStatusGeoSnapshot snapshot = OrderStatusGeoSnapshot.builder()
                .orderId(orderId)
                .status(status)
                .courierLat(BigDecimal.valueOf(lat))
                .courierLon(BigDecimal.valueOf(lon))
                .build();
        OrderStatusGeoSnapshot saved = snapshotRepository.save(snapshot);
        log.info("Сохранён снимок гео для заказа {} статус {}: {}, {}", orderId, status, lat, lon);
        return saved;
    }

    public List<OrderStatusGeoSnapshot> getSnapshotsForOrder(UUID orderId) {
        return snapshotRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
    }
}
