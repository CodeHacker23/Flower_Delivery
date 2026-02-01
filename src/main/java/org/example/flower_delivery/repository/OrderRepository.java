package org.example.flower_delivery.repository;

import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.OrderStatus;
import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    //Найти все заказы магазина:
    List<Order> findByShop(Shop shop);

    //Найти заказы по статусу
    List<Order> findByStatus(OrderStatus orderStatus);

    //Найти заказы курьера:
    List<Order> findByCourier(User courier);

    //Посчитать заказы магазина:
    long countByShop(Shop shop);

    //Посчитать активные заказы курьера (для проверки лимита 3)
    long countByCourierAndStatusIn(User courier, List<OrderStatus> statuses);


}
