package org.example.flower_delivery.repository;

import org.example.flower_delivery.model.OrderStatus;
import org.example.flower_delivery.model.OrderStatusGeoSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderStatusGeoSnapshotRepository extends JpaRepository<OrderStatusGeoSnapshot, UUID> {

    List<OrderStatusGeoSnapshot> findByOrderIdOrderByCreatedAtAsc(UUID orderId);

    Optional<OrderStatusGeoSnapshot> findFirstByOrderIdAndStatusOrderByCreatedAtDesc(UUID orderId, OrderStatus status);
}
