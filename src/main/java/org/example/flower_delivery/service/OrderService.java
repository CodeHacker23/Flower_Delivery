package org.example.flower_delivery.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.OrderStatus;
import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.repository.OrderRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {
    //инжектим нам Ордер репозиторий
    private final OrderRepository orderRepository;

    /**
     * Создать новый заказ (без координат).
     */
    @Transactional
    public Order createOrder(Shop shop, String recipientName, String recipientPhone,
                             String deliveryAddress,
                             BigDecimal deliveryPrice,
                             String comment,
                             LocalDate deliveryDate) {
        return createOrder(shop, recipientName, recipientPhone, deliveryAddress, 
                deliveryPrice, comment, deliveryDate, null, null);
    }
    
    /**
     * Создать новый заказ (с координатами).
     */
    @Transactional
    public Order createOrder(Shop shop, String recipientName, String recipientPhone,
                             String deliveryAddress,
                             BigDecimal deliveryPrice,
                             String comment,
                             LocalDate deliveryDate,
                             Double deliveryLatitude,
                             Double deliveryLongitude) {
        log.info("Создание заказа: shopId={}, recipient={}, date={}", shop.getId(), recipientName, deliveryDate);

        // Создаём заказ через Builder
        Order.OrderBuilder builder = Order.builder()
                .shop(shop)
                .recipientName(recipientName)
                .recipientPhone(recipientPhone)
                .deliveryAddress(deliveryAddress)
                .deliveryPrice(deliveryPrice)
                .comment(comment)
                .deliveryDate(deliveryDate)
                .status(OrderStatus.NEW);
        
        // Добавляем координаты если есть
        if (deliveryLatitude != null && deliveryLongitude != null) {
            builder.deliveryLatitude(BigDecimal.valueOf(deliveryLatitude));
            builder.deliveryLongitude(BigDecimal.valueOf(deliveryLongitude));
        }

        Order order = builder.build();
        Order savedOrder = orderRepository.save(order);
        log.info("Заказ создан: orderId={}", savedOrder.getId());

        return savedOrder;
    }

    /**
     * Найти заказ по ID.
     */

    public Optional<Order> findById(UUID orderId) {
        return orderRepository.findById(orderId);
    }


    /**
     * Получить все заказы магазина.
     */

    public List<Order>  getOrdersByShop(Shop shop) {
        return orderRepository.findByShop(shop);

    }

    /**
     * Получить свободные заказы (статус NEW).
     * Курьеры видят эти заказы и могут их взять.
     */
    public List<Order> getAvailableOrders() {
        return orderRepository.findByStatus(OrderStatus.NEW);
    }

}


