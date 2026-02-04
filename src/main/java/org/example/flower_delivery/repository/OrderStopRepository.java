package org.example.flower_delivery.repository;

import org.example.flower_delivery.model.OrderStop;
import org.example.flower_delivery.model.StopStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий для работы с точками доставки.
 * 
 * Предоставляет методы для:
 * - Получения всех точек заказа
 * - Поиска конкретной точки
 * - Подсчёта точек
 */
@Repository
public interface OrderStopRepository extends JpaRepository<OrderStop, UUID> {

    /**
     * Найти все точки заказа, отсортированные по номеру.
     * 
     * @param orderId ID заказа
     * @return список точек в порядке доставки (1, 2, 3...)
     */
    List<OrderStop> findByOrderIdOrderByStopNumberAsc(UUID orderId);

    /**
     * Найти конкретную точку по номеру.
     * 
     * @param orderId ID заказа
     * @param stopNumber номер точки (1, 2, 3...)
     * @return точка или empty
     */
    Optional<OrderStop> findByOrderIdAndStopNumber(UUID orderId, Integer stopNumber);

    /**
     * Посчитать количество точек в заказе.
     * 
     * @param orderId ID заказа
     * @return количество точек
     */
    int countByOrderId(UUID orderId);

    /**
     * Найти точки по статусу.
     * 
     * @param orderId ID заказа
     * @param status статус (PENDING, DELIVERED, FAILED)
     * @return список точек с этим статусом
     */
    List<OrderStop> findByOrderIdAndStopStatus(UUID orderId, StopStatus status);

    /**
     * Найти недоставленные точки заказа.
     * 
     * @param orderId ID заказа
     * @return список точек со статусом PENDING
     */
    default List<OrderStop> findPendingStops(UUID orderId) {
        return findByOrderIdAndStopStatus(orderId, StopStatus.PENDING);
    }

    /**
     * Найти последнюю точку заказа (с максимальным номером).
     * 
     * @param orderId ID заказа
     * @return последняя точка или empty
     */
    @Query("SELECT os FROM OrderStop os WHERE os.order.id = :orderId ORDER BY os.stopNumber DESC LIMIT 1")
    Optional<OrderStop> findLastStop(@Param("orderId") UUID orderId);

    /**
     * Получить сумму стоимости всех точек заказа.
     * 
     * @param orderId ID заказа
     * @return сумма delivery_price всех точек
     */
    @Query("SELECT COALESCE(SUM(os.deliveryPrice), 0) FROM OrderStop os WHERE os.order.id = :orderId")
    java.math.BigDecimal getTotalDeliveryPrice(@Param("orderId") UUID orderId);

    /**
     * Все ли точки доставлены?
     * 
     * @param orderId ID заказа
     * @return true если все точки имеют статус DELIVERED
     */
    @Query("SELECT COUNT(os) = 0 FROM OrderStop os WHERE os.order.id = :orderId AND os.stopStatus != 'DELIVERED'")
    boolean areAllStopsDelivered(@Param("orderId") UUID orderId);

    /**
     * Удалить все точки заказа.
     * Используется при отмене заказа или пересоздании.
     * 
     * @param orderId ID заказа
     */
    void deleteByOrderId(UUID orderId);
}
