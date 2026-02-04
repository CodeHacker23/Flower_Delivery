package org.example.flower_delivery.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.OrderCreationData;
import org.example.flower_delivery.model.OrderStatus;
import org.example.flower_delivery.model.OrderStop;
import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.model.StopStatus;
import org.example.flower_delivery.repository.OrderRepository;
import org.example.flower_delivery.repository.OrderStopRepository;

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
    
    private final OrderRepository orderRepository;
    private final OrderStopRepository orderStopRepository;
    private final GeocodingService geocodingService;
    private final DeliveryPriceService deliveryPriceService;

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

    /**
     * Отменить заказ (только со статусом NEW).
     *
     * @param orderId ID заказа
     * @return true если отменён, false если заказ не найден или уже не NEW
     */
    @Transactional
    public boolean cancelOrder(java.util.UUID orderId) {
        Optional<Order> opt = orderRepository.findById(orderId);
        if (opt.isEmpty()) {
            return false;
        }
        Order order = opt.get();
        if (order.getStatus() != OrderStatus.NEW) {
            log.warn("Попытка отменить заказ не в статусе NEW: orderId={}, status={}", orderId, order.getStatus());
            return false;
        }
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        log.info("Заказ отменён: orderId={}", orderId);
        return true;
    }

    // ============================================
    // МУЛЬТИАДРЕСНЫЕ ЗАКАЗЫ
    // ============================================

    /**
     * Создать мультиадресный заказ (несколько точек доставки).
     * 
     * @param shop магазин
     * @param deliveryDate дата доставки
     * @param comment общий комментарий
     * @param stopsData список точек доставки
     * @return созданный заказ со всеми точками
     */
    @Transactional
    public Order createMultiStopOrder(Shop shop, LocalDate deliveryDate, String comment,
                                       List<OrderCreationData.StopData> stopsData) {
        log.info("Создание мультиадресного заказа: shopId={}, stops={}, date={}", 
                shop.getId(), stopsData.size(), deliveryDate);
        
        // Рассчитываем общую стоимость
        BigDecimal totalPrice = stopsData.stream()
                .map(OrderCreationData.StopData::getDeliveryPrice)
                .filter(p -> p != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        // Берём первую точку для основных данных заказа (для обратной совместимости)
        OrderCreationData.StopData firstStop = stopsData.get(0);
        
        // Создаём заказ
        Order order = Order.builder()
                .shop(shop)
                .recipientName(firstStop.getRecipientName())
                .recipientPhone(firstStop.getRecipientPhone())
                .deliveryAddress(firstStop.getDeliveryAddress())
                .deliveryPrice(totalPrice)
                .comment(comment)
                .deliveryDate(deliveryDate)
                .status(OrderStatus.NEW)
                .isMultiStop(true)
                .totalStops(stopsData.size())
                .build();
        
        // Добавляем координаты первой точки (для обратной совместимости)
        if (firstStop.getDeliveryLatitude() != null && firstStop.getDeliveryLongitude() != null) {
            order.setDeliveryLatitude(BigDecimal.valueOf(firstStop.getDeliveryLatitude()));
            order.setDeliveryLongitude(BigDecimal.valueOf(firstStop.getDeliveryLongitude()));
        }
        
        // Сохраняем заказ (чтобы получить ID)
        Order savedOrder = orderRepository.save(order);
        
        // Создаём точки доставки
        for (OrderCreationData.StopData stopData : stopsData) {
            OrderStop stop = OrderStop.builder()
                    .order(savedOrder)
                    .stopNumber(stopData.getStopNumber())
                    .recipientName(stopData.getRecipientName())
                    .recipientPhone(stopData.getRecipientPhone())
                    .deliveryAddress(stopData.getDeliveryAddress())
                    .deliveryPrice(stopData.getDeliveryPrice())
                    .stopStatus(StopStatus.PENDING)
                    .build();
            
            // Добавляем координаты если есть
            if (stopData.getDeliveryLatitude() != null && stopData.getDeliveryLongitude() != null) {
                stop.setDeliveryLatitude(BigDecimal.valueOf(stopData.getDeliveryLatitude()));
                stop.setDeliveryLongitude(BigDecimal.valueOf(stopData.getDeliveryLongitude()));
            }
            
            // Добавляем расстояние если есть
            if (stopData.getDistanceKm() != null) {
                stop.setDistanceKm(BigDecimal.valueOf(stopData.getDistanceKm()));
            }
            
            // Добавляем комментарий если есть
            if (stopData.getComment() != null && !stopData.getComment().isEmpty()) {
                stop.setComment(stopData.getComment());
            }
            
            orderStopRepository.save(stop);
            savedOrder.getStops().add(stop);
        }
        
        log.info("Мультиадресный заказ создан: orderId={}, stops={}, totalPrice={}", 
                savedOrder.getId(), stopsData.size(), totalPrice);
        
        return savedOrder;
    }

    /**
     * Получить все точки заказа.
     */
    public List<OrderStop> getOrderStops(UUID orderId) {
        return orderStopRepository.findByOrderIdOrderByStopNumberAsc(orderId);
    }

    /**
     * Отметить точку как доставленную.
     */
    @Transactional
    public void markStopDelivered(UUID orderId, int stopNumber) {
        Optional<OrderStop> stopOpt = orderStopRepository.findByOrderIdAndStopNumber(orderId, stopNumber);
        if (stopOpt.isPresent()) {
            OrderStop stop = stopOpt.get();
            stop.markDelivered();
            orderStopRepository.save(stop);
            log.info("Точка #{} заказа {} отмечена как доставленная", stopNumber, orderId);
            
            // Проверяем, все ли точки доставлены
            if (orderStopRepository.areAllStopsDelivered(orderId)) {
                // Меняем статус заказа на DELIVERED
                Order order = orderRepository.findById(orderId).orElse(null);
                if (order != null) {
                    order.setStatus(OrderStatus.DELIVERED);
                    order.setDeliveredAt(java.time.LocalDateTime.now());
                    orderRepository.save(order);
                    log.info("Все точки доставлены, заказ {} помечен как DELIVERED", orderId);
                }
            }
        }
    }

    // ============================================
    // РЕДАКТИРОВАНИЕ ЗАКАЗА
    // ============================================

    /**
     * Обновить адрес точки доставки.
     * Для обычного заказа (без order_stops) обновляется order.deliveryAddress.
     * Для мультиадресного — order_stops.delivery_address по stopNumber.
     */
    @Transactional
    public boolean updateStopAddress(UUID orderId, int stopNumber, String newAddress) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.NEW) return false;
        List<OrderStop> stops = orderStopRepository.findByOrderIdOrderByStopNumberAsc(orderId);
        if (stops.isEmpty()) {
            if (stopNumber != 1) return false;
            order.setDeliveryAddress(newAddress);
            // Переопределяем координаты по новому адресу (если получится)
            geocodeOrderAddress(order, newAddress);
            // Пересчитываем цену доставки от магазина до нового адреса
            recalcSingleOrderDelivery(order);
            orderRepository.save(order);
            log.info("Заказ {}: обновлён адрес (основной) с перерасчётом цены", orderId);
            return true;
        }
        Optional<OrderStop> stopOpt = orderStopRepository.findByOrderIdAndStopNumber(orderId, stopNumber);
        if (stopOpt.isEmpty()) return false;
        OrderStop stop = stopOpt.get();
        stop.setDeliveryAddress(newAddress);
        // Обновляем координаты точки (если получится)
        geocodeStopAddress(stop, newAddress);
        if (stopNumber == 1) {
            order.setDeliveryAddress(newAddress);
        }
        // Пересчитываем всю мультиадресную доставку (все точки и итоговую цену)
        recalcMultiStopDelivery(order, stops);
        log.info("Заказ {}: обновлён адрес точки {} с перерасчётом цен", orderId, stopNumber);
        return true;
    }

    /**
     * Обновить телефон точки.
     */
    @Transactional
    public boolean updateStopPhone(UUID orderId, int stopNumber, String newPhone) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.NEW) return false;
        List<OrderStop> stops = orderStopRepository.findByOrderIdOrderByStopNumberAsc(orderId);
        if (stops.isEmpty()) {
            if (stopNumber != 1) return false;
            order.setRecipientPhone(newPhone);
            orderRepository.save(order);
            log.info("Заказ {}: обновлён телефон (основной)", orderId);
            return true;
        }
        Optional<OrderStop> stopOpt = orderStopRepository.findByOrderIdAndStopNumber(orderId, stopNumber);
        if (stopOpt.isEmpty()) return false;
        OrderStop stop = stopOpt.get();
        stop.setRecipientPhone(newPhone);
        orderStopRepository.save(stop);
        if (stopNumber == 1) {
            order.setRecipientPhone(newPhone);
            orderRepository.save(order);
        }
        log.info("Заказ {}: обновлён телефон точки {}", orderId, stopNumber);
        return true;
    }

    /**
     * Обновить комментарий точки.
     */
    @Transactional
    public boolean updateStopComment(UUID orderId, int stopNumber, String newComment) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.NEW) return false;
        List<OrderStop> stops = orderStopRepository.findByOrderIdOrderByStopNumberAsc(orderId);
        if (stops.isEmpty()) {
            if (stopNumber != 1) return false;
            order.setComment(newComment);
            orderRepository.save(order);
            log.info("Заказ {}: обновлён комментарий (основной)", orderId);
            return true;
        }
        Optional<OrderStop> stopOpt = orderStopRepository.findByOrderIdAndStopNumber(orderId, stopNumber);
        if (stopOpt.isEmpty()) return false;
        OrderStop stop = stopOpt.get();
        stop.setComment(newComment);
        orderStopRepository.save(stop);
        log.info("Заказ {}: обновлён комментарий точки {}", orderId, stopNumber);
        return true;
    }

    /**
     * Обновить дату доставки заказа (одна дата на весь заказ).
     */
    @Transactional
    public boolean updateOrderDeliveryDate(UUID orderId, LocalDate newDate) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.NEW) return false;
        order.setDeliveryDate(newDate);
        orderRepository.save(order);
        log.info("Заказ {}: обновлена дата доставки {}", orderId, newDate);
        return true;
    }

    // ============================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ДЛЯ ПЕРЕСЧЁТА
    // ============================================

    /**
     * Геокодировать адрес заказа (для обычного заказа без точек).
     */
    private void geocodeOrderAddress(Order order, String address) {
        try {
            var geoOpt = geocodingService.geocode(address);
            if (geoOpt.isEmpty()) {
                log.warn("Не удалось геокодировать адрес заказа {}: {}", order.getId(), address);
                return;
            }
            GeocodingService.GeocodingResult geo = geoOpt.get();
            if (!geocodingService.isInAllowedRegion(geo)) {
                log.warn("Адрес {} вне разрешённого региона, координаты не обновляем", geo.fullAddress());
                return;
            }
            order.setDeliveryLatitude(BigDecimal.valueOf(geo.latitude()));
            order.setDeliveryLongitude(BigDecimal.valueOf(geo.longitude()));
        } catch (Exception e) {
            log.warn("Ошибка геокодирования адреса заказа {}: {}", order.getId(), e.getMessage());
        }
    }

    /**
     * Геокодировать адрес точки мультиадресного заказа.
     */
    private void geocodeStopAddress(OrderStop stop, String address) {
        try {
            var geoOpt = geocodingService.geocode(address);
            if (geoOpt.isEmpty()) {
                log.warn("Не удалось геокодировать адрес точки {} заказа {}: {}", 
                        stop.getStopNumber(), stop.getOrder().getId(), address);
                return;
            }
            GeocodingService.GeocodingResult geo = geoOpt.get();
            if (!geocodingService.isInAllowedRegion(geo)) {
                log.warn("Адрес точки {} {} вне разрешённого региона, координаты не обновляем",
                        stop.getStopNumber(), geo.fullAddress());
                return;
            }
            stop.setDeliveryLatitude(BigDecimal.valueOf(geo.latitude()));
            stop.setDeliveryLongitude(BigDecimal.valueOf(geo.longitude()));
            orderStopRepository.save(stop);
        } catch (Exception e) {
            log.warn("Ошибка геокодирования адреса точки {} заказа {}: {}", 
                    stop.getStopNumber(), stop.getOrder().getId(), e.getMessage());
        }
    }

    /**
     * Пересчитать цену доставки для обычного заказа (1 адрес).
     */
    private void recalcSingleOrderDelivery(Order order) {
        if (order.getShop() == null ||
                order.getShop().getLatitude() == null ||
                order.getShop().getLongitude() == null ||
                order.getDeliveryLatitude() == null ||
                order.getDeliveryLongitude() == null) {
            return;
        }
        double shopLat = order.getShop().getLatitude().doubleValue();
        double shopLon = order.getShop().getLongitude().doubleValue();
        double lat = order.getDeliveryLatitude().doubleValue();
        double lon = order.getDeliveryLongitude().doubleValue();

        DeliveryPriceService.DeliveryCalculation calc =
                deliveryPriceService.calculate(shopLat, shopLon, lat, lon);

        order.setDeliveryPrice(calc.price());
        log.info("Пересчёт обычной доставки заказа {}: новое расстояние {} км, цена {}₽",
                order.getId(), calc.distanceKm(), calc.price());
    }

    /**
     * Пересчитать цены и расстояния для мультиадресного заказа.
     * Считаем от магазина до первой точки, затем от точки к точке.
     */
    private void recalcMultiStopDelivery(Order order, List<OrderStop> stops) {
        if (stops == null || stops.isEmpty()) {
            return;
        }
        if (order.getShop() == null ||
                order.getShop().getLatitude() == null ||
                order.getShop().getLongitude() == null) {
            log.warn("Невозможно пересчитать мультидоставку: у магазина нет координат (orderId={})", order.getId());
            return;
        }

        double shopLat = order.getShop().getLatitude().doubleValue();
        double shopLon = order.getShop().getLongitude().doubleValue();

        // Проверяем, что у всех точек есть координаты
        for (OrderStop stop : stops) {
            if (stop.getDeliveryLatitude() == null || stop.getDeliveryLongitude() == null) {
                log.warn("Невозможно пересчитать мультидоставку: у точки {} заказа {} нет координат",
                        stop.getStopNumber(), order.getId());
                return;
            }
        }

        BigDecimal total = BigDecimal.ZERO;

        // Первая точка — от магазина
        OrderStop first = stops.get(0);
        DeliveryPriceService.DeliveryCalculation firstCalc =
                deliveryPriceService.calculate(
                        shopLat, shopLon,
                        first.getDeliveryLatitude().doubleValue(),
                        first.getDeliveryLongitude().doubleValue()
                );
        first.setDistanceKm(BigDecimal.valueOf(firstCalc.distanceKm()));
        first.setDeliveryPrice(firstCalc.price());
        total = total.add(firstCalc.price());

        double prevLat = first.getDeliveryLatitude().doubleValue();
        double prevLon = first.getDeliveryLongitude().doubleValue();

        // Остальные точки — от предыдущей точки
        for (int i = 1; i < stops.size(); i++) {
            OrderStop stop = stops.get(i);
            DeliveryPriceService.DeliveryCalculation calc =
                    deliveryPriceService.calculateAdditionalStop(
                            prevLat, prevLon,
                            stop.getDeliveryLatitude().doubleValue(),
                            stop.getDeliveryLongitude().doubleValue()
                    );
            stop.setDistanceKm(BigDecimal.valueOf(calc.distanceKm()));
            stop.setDeliveryPrice(calc.price());
            total = total.add(calc.price());

            prevLat = stop.getDeliveryLatitude().doubleValue();
            prevLon = stop.getDeliveryLongitude().doubleValue();
        }

        order.setDeliveryPrice(total);
        orderRepository.save(order);
        orderStopRepository.saveAll(stops);

        log.info("Пересчёт мультидоставки заказа {}: {} точек, итоговая цена {}₽",
                order.getId(), stops.size(), total);
    }
}


