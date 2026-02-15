package org.example.flower_delivery.repository;

import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.OrderStatus;
import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    //Найти все заказы магазина:
    List<Order> findByShop(Shop shop);

    //Найти заказы по статусу
    List<Order> findByStatus(OrderStatus orderStatus);

    /** Доступные заказы с подгруженным магазином (адрес забора). */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.shop WHERE o.status = :status")
    List<Order> findByStatusWithShop(@Param("status") OrderStatus status);

    //Найти заказы курьера:
    List<Order> findByCourier(User courier);

    /** Заказы курьера с подгруженным магазином. */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.shop WHERE o.courier = :courier")
    List<Order> findByCourierWithShop(@Param("courier") User courier);

    /** Заказы курьера с подгруженными stops (для статистики: getTotalDeliveryPrice() у мультиадреса). */
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.stops WHERE o.courier = :courier")
    List<Order> findByCourierWithStops(@Param("courier") User courier);

    /** Один заказ с магазином (для сообщения «Заказ взят» и т.п.). */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.shop WHERE o.id = :id")
    Optional<Order> findByIdWithShop(@Param("id") UUID id);

    //Посчитать заказы магазина:
    long countByShop(Shop shop);

    //Посчитать активные заказы курьера (для проверки лимита 3)
    long countByCourierAndStatusIn(User courier, List<OrderStatus> statuses);

    /** Доставленные курьером за период (для честного распределения: сколько заказов от какого магазина). */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.shop WHERE o.courier = :courier AND o.status = :status AND o.deliveredAt >= :since")
    List<Order> findDeliveredByCourierSince(@Param("courier") User courier, @Param("status") OrderStatus status, @Param("since") LocalDateTime since);
}
