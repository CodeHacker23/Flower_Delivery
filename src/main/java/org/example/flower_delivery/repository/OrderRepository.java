package org.example.flower_delivery.repository;

import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.OrderStatus;
import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.model.User;
import org.springframework.data.domain.Pageable;
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

    /** Доступные заказы с лимитом, по дате доставки (для масштаба 400+ заказов). */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.shop WHERE o.status = :status ORDER BY o.deliveryDate ASC NULLS LAST, o.createdAt ASC")
    List<Order> findByStatusWithShopOrderByDeliveryDate(@Param("status") OrderStatus status, Pageable pageable);

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

    /** Несколько заказов с магазином и stops (для пагинации «Доступные заказы» — getRouteDescription не падает с LazyInit). */
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.shop LEFT JOIN FETCH o.stops WHERE o.id IN :ids")
    List<Order> findByIdInWithShop(@Param("ids") List<UUID> ids);

    /** Заказ с магазином, пользователем магазина и курьером (для запроса магазину «Курьер забрал?»). */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.shop s LEFT JOIN FETCH s.user LEFT JOIN FETCH o.courier WHERE o.id = :id")
    Optional<Order> findByIdWithShopAndShopUserAndCourier(@Param("id") UUID id);

    //Посчитать заказы магазина:
    long countByShop(Shop shop);

    //Посчитать активные заказы курьера (для проверки лимита 3)
    long countByCourierAndStatusIn(User courier, List<OrderStatus> statuses);

    /** Доставленные курьером за период (для честного распределения: сколько заказов от какого магазина). */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.shop WHERE o.courier = :courier AND o.status = :status AND o.deliveredAt >= :since")
    List<Order> findDeliveredByCourierSince(@Param("courier") User courier, @Param("status") OrderStatus status, @Param("since") LocalDateTime since);

    /** Количество отменённых или возвращённых заказов курьером за период (для штрафа 1000₽). */
    @Query("SELECT COUNT(o) FROM Order o WHERE o.courier = :courier AND o.status IN (:statuses) AND o.updatedAt >= :since")
    long countByCourierAndStatusInAndUpdatedAtSince(@Param("courier") User courier,
                                                    @Param("statuses") List<OrderStatus> statuses,
                                                    @Param("since") LocalDateTime since);
}
