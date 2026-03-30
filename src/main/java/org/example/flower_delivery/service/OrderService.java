package org.example.flower_delivery.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.OrderCreationData;
import org.example.flower_delivery.model.OrderStatus;
import org.example.flower_delivery.model.OrderStop;
import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.model.StopStatus;
import org.example.flower_delivery.model.User;
import org.example.flower_delivery.repository.OrderRepository;
import org.example.flower_delivery.repository.OrderStopRepository;
import org.example.flower_delivery.util.GeoUtil;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {
    
    private final OrderRepository orderRepository;
    private final OrderStopRepository orderStopRepository;
    private final GeocodingService geocodingService;
    private final DeliveryPriceService deliveryPriceService;
    private final CourierService courierService;
    private final CourierTransactionService courierTransactionService;
    private final CourierPenaltyService courierPenaltyService;

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
                deliveryPrice, comment, deliveryDate, null, null, null);
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
        return createOrder(shop, recipientName, recipientPhone, deliveryAddress,
                deliveryPrice, comment, deliveryDate, deliveryLatitude, deliveryLongitude, null);
    }

    /**
     * Создать новый заказ (с координатами и интервалом доставки).
     */
    @Transactional
    public Order createOrder(Shop shop, String recipientName, String recipientPhone,
                             String deliveryAddress,
                             BigDecimal deliveryPrice,
                             String comment,
                             LocalDate deliveryDate,
                             Double deliveryLatitude,
                             Double deliveryLongitude,
                             org.example.flower_delivery.model.DeliveryInterval deliveryInterval) {
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
                .status(OrderStatus.NEW)
                .deliveryInterval(deliveryInterval);

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
     * Доступные заказы с подгруженным магазином (для отображения адреса забора).
     */
    public List<Order> getAvailableOrdersWithShop() {
        return orderRepository.findByStatusWithShop(OrderStatus.NEW);
    }

    /** Макс. заказов для загрузки (при 400+ в боте не тянем все в память). */
    private static final int AVAILABLE_ORDERS_FETCH_LIMIT = 80;

    /**
     * Доступные заказы, отсортированные по расстоянию от курьера до магазина (ближайшие сверху).
     * Загружаем не более 80 — при 400 заказах не тянем все в память.
     */
    public List<Order> getAvailableOrdersSortedByDistanceFrom(double courierLat, double courierLon) {
        List<Order> list = orderRepository.findByStatusWithShopOrderByDeliveryDate(
                OrderStatus.NEW, org.springframework.data.domain.PageRequest.of(0, AVAILABLE_ORDERS_FETCH_LIMIT));
        list.sort(Comparator.comparingDouble(order -> distanceFromCourier(order, courierLat, courierLon)));
        return list;
    }

    private static double distanceFromCourier(Order order, double courierLat, double courierLon) {
        BigDecimal lat = order.getEffectivePickupLatitude();
        BigDecimal lon = order.getEffectivePickupLongitude();
        if (lat == null || lon == null) return Double.POSITIVE_INFINITY;
        return GeoUtil.distanceKm(courierLat, courierLon, lat.doubleValue(), lon.doubleValue());
    }

    /**
     * Сколько доставленных заказов от каждого магазина доставил курьер за последние lastHours часов.
     * Нужно для честного распределения: показывать заказы от «обделённых» магазинов.
     */
    public Map<UUID, Long> getDeliveredCountPerShopForCourier(User courier, int lastHours) {
        LocalDateTime since = LocalDateTime.now().minusHours(lastHours);
        List<Order> delivered = orderRepository.findDeliveredByCourierSince(courier, OrderStatus.DELIVERED, since);
        return delivered.stream()
                .filter(o -> o.getShop() != null && o.getShop().getId() != null)
                .collect(Collectors.groupingBy(o -> o.getShop().getId(), Collectors.counting()));
    }

    /**
     * Доступные заказы с честным распределением: первые nearestCount — ближайшие по гео;
     * следующие otherCount — от магазинов, которым этот курьер доставил меньше всего за последние 24 ч.
     * Берём до 80 заказов из БД (при 400+ не тянем все).
     */
    public List<Order> getAvailableOrdersWithFairness(double courierLat, double courierLon, User courier, int nearestCount, int otherCount) {
        List<Order> allSorted = getAvailableOrdersSortedByDistanceFrom(courierLat, courierLon);
        if (allSorted.isEmpty()) return allSorted;

        Map<UUID, Long> deliveredByShop = getDeliveredCountPerShopForCourier(courier, 24);
        long minDeliveries = deliveredByShop.isEmpty() ? 0 : deliveredByShop.values().stream().min(Long::compareTo).orElse(0L);

        int n = Math.min(nearestCount, allSorted.size());
        List<Order> nearest = new ArrayList<>(allSorted.subList(0, n));
        Set<UUID> nearestIds = nearest.stream().map(Order::getId).collect(Collectors.toSet());

        List<Order> rest = allSorted.stream().filter(o -> !nearestIds.contains(o.getId())).toList();
        List<Order> other = rest.stream()
                .filter(o -> o.getShop() != null && o.getShop().getId() != null
                        && deliveredByShop.getOrDefault(o.getShop().getId(), 0L) <= minDeliveries)
                .limit(otherCount)
                .toList();

        List<Order> result = new ArrayList<>(nearest);
        result.addAll(other);
        return result;
    }

    /**
     * Получить все заказы курьера.
     */
    public List<Order> getOrdersByCourier(User courier) {
        return orderRepository.findByCourier(courier);
    }

    /**
     * Заказы курьера с подгруженным магазином (для «Мои заказы» с адресом забора).
     */
    public List<Order> getOrdersByCourierWithShop(User courier) {
        return orderRepository.findByCourierWithShop(courier);
    }

    /**
     * Заказы курьера с подгруженными stops (для статистики: getTotalDeliveryPrice() у мультиадреса не падает с LazyInit).
     */
    public List<Order> getOrdersByCourierWithStops(User courier) {
        return orderRepository.findByCourierWithStops(courier);
    }

    /**
     * Заказ с подгруженным магазином (для сообщения «Заказ взят» и т.п.).
     */
    public Optional<Order> getOrderWithShop(UUID orderId) {
        return orderRepository.findByIdWithShop(orderId);
    }

    /**
     * Заказы с магазином по списку ID (порядок сохраняется).
     */
    public List<Order> findByIdsWithShop(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Order> found = orderRepository.findByIdInWithShop(ids);
        Map<UUID, Order> byId = new HashMap<>();
        for (Order o : found) byId.put(o.getId(), o);
        List<Order> result = new ArrayList<>();
        for (UUID id : ids) {
            Order o = byId.get(id);
            if (o != null) result.add(o);
        }
        return result;
    }

    /**
     * Заказ с магазином, пользователем магазина и курьером (для отправки запроса магазину «Курьер забрал?»).
     */
    public Optional<Order> getOrderForShopPickupMessage(UUID orderId) {
        return orderRepository.findByIdWithShopAndShopUserAndCourier(orderId);
    }

    /**
     * Отметить, что магазину отправлен запрос «Курьер забрал заказ?» (при переходе в «В путь»).
     */
    @Transactional
    public boolean markShopPickupConfirmationRequested(UUID orderId) {
        Optional<Order> opt = orderRepository.findById(orderId);
        if (opt.isEmpty()) return false;
        Order order = opt.get();
        if (order.getShopPickupConfirmationRequestedAt() != null) {
            return false;
        }
        order.setShopPickupConfirmationRequestedAt(LocalDateTime.now());
        orderRepository.save(order);
        return true;
    }

    /**
     * Сохранить ответ магазина на запрос «Курьер забрал заказ?» (ДА/Нет).
     * Вызывать только если вызывающий — пользователь магазина этого заказа.
     *
     * @return true если заказ найден и ответ ещё не был сохранён
     */
    @Transactional
    public boolean setShopPickupConfirmed(UUID orderId, boolean confirmed) {
        Optional<Order> opt = orderRepository.findByIdWithShop(orderId);
        if (opt.isEmpty()) return false;
        Order order = opt.get();
        if (order.getShopPickupConfirmed() != null) return false; // уже ответил
        order.setShopPickupConfirmed(confirmed);
        order.setShopPickupConfirmedAt(LocalDateTime.now());
        orderRepository.save(order);
        return true;
    }

    /**
     * Посчитать количество активных заказов курьера.
     * Активные = в пути / в работе, ещё не завершённые.
     */
    public long countActiveOrdersForCourier(User courier) {
        List<OrderStatus> activeStatuses = java.util.List.of(
                OrderStatus.ACCEPTED,
                OrderStatus.IN_SHOP,
                OrderStatus.PICKED_UP,
                OrderStatus.ON_WAY
        );
        return orderRepository.countByCourierAndStatusIn(courier, activeStatuses);
    }

    /**
     * Попробовать назначить курьера на заказ.
     *
     * Условия:
     * - заказ должен быть в статусе NEW;
     * - у заказа ещё не должен быть назначен курьер.
     *
     * @return Optional с обновлённым заказом, либо empty если взять нельзя
     */
    @Transactional
    public Optional<Order> assignOrderToCourier(UUID orderId, User courier) {
        Optional<Order> opt = orderRepository.findById(orderId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        Order order = opt.get();
        if (!order.isAvailable() || order.hasCourier()) {
            log.warn("Попытка взять недоступный заказ: orderId={}, status={}, hasCourier={}",
                    orderId, order.getStatus(), order.hasCourier());
            return Optional.empty();
        }
        // Рассчитываем комиссию курьера за этот заказ (процент в Courier.commissionPercent)
        // и пытаемся списать её с баланса.
        BigDecimal commission = calculateCommissionForCourier(order, courier);
        if (commission != null && commission.compareTo(BigDecimal.ZERO) > 0) {
            if (!courierService.chargeFromBalance(courier, commission)) {
                // Недостаточно средств — заказ брать нельзя.
                log.info("Курьеру не хватает баланса для комиссии: userId={}, commission={}", courier.getId(), commission);
                return Optional.empty();
            } else {
                // Записываем транзакцию комиссии.
                courierService.findByUser(courier)
                        .ifPresent(c -> courierTransactionService.addCommissionCharge(c, order, commission));
            }
        }
        order.setCourier(courier);
        order.setStatus(OrderStatus.ACCEPTED);
        order.setAcceptedAt(java.time.LocalDateTime.now());
        Order saved = orderRepository.save(order);

        log.info("Заказ {} назначен курьеру {} (userId={})",
                saved.getId(), courier.getFullName(), courier.getId());

        return Optional.of(saved);
    }

    /**
     * Назначить связку заказов курьеру (2–3 заказа).
     * Проверяет лимит активных (max 3), баланс на сумму комиссий, назначает по порядку.
     *
     * @param orderIds список ID заказов в порядке маршрута
     * @param courier  курьер (User)
     * @return список успешно назначенных заказов; пустой при ошибке
     */
    @Transactional
    public List<Order> assignBundleToCourier(List<UUID> orderIds, User courier) {
        if (orderIds == null || orderIds.isEmpty() || courier == null) {
            return List.of();
        }
        if (orderIds.size() > 3) {
            log.warn("Связка больше 3 заказов: {}", orderIds.size());
            return List.of();
        }
        long activeCount = countActiveOrdersForCourier(courier);
        if (activeCount + orderIds.size() > 3) {
            log.warn("Курьер не может взять связку: активных {}, связка {}", activeCount, orderIds.size());
            return List.of();
        }
        // Считаем общую комиссию
        BigDecimal totalCommission = BigDecimal.ZERO;
        List<Order> toAssign = new ArrayList<>();
        for (UUID id : orderIds) {
            Optional<Order> opt = orderRepository.findById(id);
            if (opt.isEmpty() || !opt.get().isAvailable() || opt.get().hasCourier()) {
                log.warn("Заказ {} недоступен для связки", id);
                return List.of(); // хотя бы один недоступен — отменяем всю связку
            }
            BigDecimal comm = calculateCommissionForCourier(opt.get(), courier);
            if (comm != null) totalCommission = totalCommission.add(comm);
            toAssign.add(opt.get());
        }
        if (!courierService.chargeFromBalance(courier, totalCommission)) {
            log.info("Недостаточно баланса для связки: commission={}", totalCommission);
            return List.of();
        }
        List<Order> assigned = new ArrayList<>();
        for (Order order : toAssign) {
            BigDecimal comm = calculateCommissionForCourier(order, courier);
            if (comm != null && comm.compareTo(BigDecimal.ZERO) > 0) {
                courierService.findByUser(courier).ifPresent(c ->
                        courierTransactionService.addCommissionCharge(c, order, comm));
            }
            order.setCourier(courier);
            order.setStatus(OrderStatus.ACCEPTED);
            order.setAcceptedAt(java.time.LocalDateTime.now());
            orderRepository.save(order);
            assigned.add(order);
        }
        log.info("Связка из {} заказов назначена курьеру {}", assigned.size(), courier.getId());
        return assigned;
    }

    /**
     * Перевести заказ в следующий статус (только для заказа этого курьера).
     * Цепочка: ACCEPTED → IN_SHOP → ON_WAY → DELIVERED (без отдельного «Забран»).
     *
     * @return true если статус обновлён, false если заказ не найден, не твой или переход недопустим
     */
    @Transactional
    public boolean updateOrderStatusByCourier(UUID orderId, User courier, OrderStatus newStatus) {
        Optional<Order> opt = orderRepository.findById(orderId);
        if (opt.isEmpty()) return false;
        Order order = opt.get();
        if (order.getCourier() == null || !order.getCourier().getId().equals(courier.getId())) {
            return false;
        }
        OrderStatus current = order.getStatus();
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        switch (current) {
            case ACCEPTED:
                if (newStatus != OrderStatus.IN_SHOP) return false;
                if (order.getAcceptedAt() == null) order.setAcceptedAt(now);
                break;
            case IN_SHOP:
                if (newStatus != OrderStatus.ON_WAY) return false;
                order.setPickedUpAt(now);
                break;
            case PICKED_UP:
                if (newStatus != OrderStatus.ON_WAY) return false;
                break;
            case ON_WAY:
                if (newStatus != OrderStatus.DELIVERED) return false;
                order.setDeliveredAt(now);
                List<OrderStop> stops = orderStopRepository.findByOrderIdOrderByStopNumberAsc(orderId);
                if (stops.isEmpty()) {
                    order.setStatus(OrderStatus.DELIVERED);
                    orderRepository.save(order);
                } else {
                    for (OrderStop s : stops) {
                        markStopDelivered(orderId, s.getStopNumber());
                    }
                }
                log.info("Заказ {} доставлен курьером {}", orderId, courier.getId());
                return true;
            default:
                return false;
        }
        order.setStatus(newStatus);
        orderRepository.save(order);
        log.info("Заказ {} переведён в {} курьером {}", orderId, newStatus, courier.getId());
        return true;
    }

    /**
     * Рассчитать комиссию курьера за заказ по его проценту.
     *
     * @return сумма комиссии или null, если курьер не найден
     */
    public BigDecimal calculateCommissionForCourier(Order order, User courier) {
        if (order == null || courier == null) {
            return null;
        }
        Optional<org.example.flower_delivery.model.Courier> courierOpt = courierService.findByUser(courier);
        if (courierOpt.isEmpty()) {
            return null;
        }
        // Избегаем LazyInitializationException: для мультиадресных заказов берём сумму точек через репозиторий,
        // для обычных — просто deliveryPrice из самого заказа.
        BigDecimal totalPrice;
        if (order.isMultiStopOrder()) {
            totalPrice = orderStopRepository.getTotalDeliveryPrice(order.getId());
        } else {
            totalPrice = order.getDeliveryPrice();
        }
        if (totalPrice == null) {
            totalPrice = BigDecimal.ZERO;
        }
        BigDecimal percent = courierOpt.get().getCommissionPercent() != null
                ? courierOpt.get().getCommissionPercent()
                : new BigDecimal("20.00");
        return totalPrice
                .multiply(percent)
                .divide(new BigDecimal("100.00"));
    }

    /**
     * Проверить, хватает ли баланса курьера для комиссии по конкретному заказу.
     * Используется, чтобы показать курьеру понятное сообщение при попытке взять заказ.
     */
    public boolean isInsufficientBalanceForOrder(UUID orderId, User courier) {
        Optional<Order> opt = orderRepository.findById(orderId);
        if (opt.isEmpty()) {
            return false;
        }
        BigDecimal commission = calculateCommissionForCourier(opt.get(), courier);
        if (commission == null || commission.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        Optional<org.example.flower_delivery.model.Courier> courierOpt = courierService.findByUser(courier);
        if (courierOpt.isEmpty()) {
            return false;
        }
        BigDecimal balance = courierOpt.get().getBalance() != null
                ? courierOpt.get().getBalance()
                : BigDecimal.ZERO;
        return balance.compareTo(commission) < 0;
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

    /**
     * Результат отмены/возврата заказа курьером.
     * notifyAdmin = курьер был в магазине, но не у получателя по гео — «звоночек» на разбор админу.
     */
    public record CancelResult(boolean success, boolean penaltyApplied, boolean notifyAdmin, String penaltyReason) {}

    /**
     * Отмена заказа курьером.
     * Допускается только для активных заказов этого курьера (ACCEPTED / IN_SHOP / PICKED_UP / ON_WAY).
     * Завершённые или чужие заказы отменить нельзя.
     *
     * @param cancelReason причина отмены (передаётся админам), может быть null
     */
    @Transactional
    public CancelResult cancelOrderByCourier(UUID orderId, User courierUser, String cancelReason) {
        log.info("Запрос отмены заказа курьером: orderId={}, courierUserId={}", orderId, courierUser != null ? courierUser.getId() : null);
        Optional<Order> opt = orderRepository.findById(orderId);
        if (opt.isEmpty()) {
            log.warn("🚨 Отмена курьером: заказ не найден, orderId={}", orderId);
            return new CancelResult(false, false, false, null);
        }
        Order order = opt.get();
        if (order.isCompleted()) {
            log.warn("Курьер пытается отменить завершённый заказ: orderId={}, status={}", orderId, order.getStatus());
            return new CancelResult(false, false, false, null);
        }
        if (order.getCourier() == null || courierUser == null
                || order.getCourier().getId() == null
                || !order.getCourier().getId().equals(courierUser.getId())) {
            log.warn("Курьер пытается отменить не свой заказ: orderId={}, courierId={}",
                    orderId, courierUser != null ? courierUser.getId() : null);
            return new CancelResult(false, false, false, null);
        }
        OrderStatus status = order.getStatus();
        if (!(status == OrderStatus.ACCEPTED
                || status == OrderStatus.IN_SHOP
                || status == OrderStatus.PICKED_UP
                || status == OrderStatus.ON_WAY)) {
            log.warn("🚫 Статус заказа не подходит для отмены курьером: orderId={}, status={}", orderId, status);
            return new CancelResult(false, false, false, null);
        }
        order.setStatus(OrderStatus.CANCELLED);
        if (cancelReason != null && !cancelReason.isBlank()) {
            order.setCourierCancelReason(cancelReason.trim());
        }
        orderRepository.save(order);
        log.info("✅ Заказ отменён курьером: orderId={}, courierUserId={}", orderId, courierUser.getId());

        var penaltyResult = courierPenaltyService.checkAndApplyPenalties(order, courierUser, false);
        if (penaltyResult.anyApplied()) {
            log.info("⚠️ Штраф применён при отмене: orderId={}, courierUserId={}, reason={}",
                    orderId, courierUser.getId(), penaltyResult.reason());
        } else {
            Optional<org.example.flower_delivery.model.Courier> courierOpt = courierService.findByUser(courierUser);
            courierOpt.ifPresent(courier -> {
                BigDecimal commission = calculateCommissionForCourier(order, courierUser);
                if (commission != null && commission.compareTo(BigDecimal.ZERO) > 0) {
                    log.info("Возврат комиссии после отмены: orderId={}, courierId={}, amount={}",
                            orderId, courier.getId(), commission);
                    courierService.addToBalance(courierUser, commission);
                    courierTransactionService.addCommissionRefund(courier, order, commission);
                }
            });
        }
        return new CancelResult(true, penaltyResult.anyApplied(), penaltyResult.notifyAdmin(), penaltyResult.reason());
    }

    /**
     * Возврат заказа в магазин курьером.
     * Допускается только для активных заказов этого курьера (ACCEPTED / IN_SHOP / PICKED_UP / ON_WAY).
     * Завершённые или чужие заказы вернуть нельзя.
     *
     * @param cancelReason причина возврата (передаётся админам), может быть null
     */
    @Transactional
    public CancelResult returnOrderToShopByCourier(UUID orderId, User courierUser, String cancelReason) {
        log.info("Запрос возврата заказа в магазин курьером: orderId={}, courierUserId={}", orderId, courierUser != null ? courierUser.getId() : null);
        Optional<Order> opt = orderRepository.findById(orderId);
        if (opt.isEmpty()) {
            log.warn("🚨 Возврат в магазин: заказ не найден, orderId={}", orderId);
            return new CancelResult(false, false, false, null);
        }
        Order order = opt.get();
        if (order.isCompleted()) {
            log.warn("Курьер пытается вернуть завершённый заказ: orderId={}, status={}", orderId, order.getStatus());
            return new CancelResult(false, false, false, null);
        }
        if (order.getCourier() == null || courierUser == null
                || order.getCourier().getId() == null
                || !order.getCourier().getId().equals(courierUser.getId())) {
            log.warn("Курьер пытается вернуть не свой заказ: orderId={}, courierId={}",
                    orderId, courierUser != null ? courierUser.getId() : null);
            return new CancelResult(false, false, false, null);
        }
        OrderStatus status = order.getStatus();
        if (!(status == OrderStatus.ACCEPTED
                || status == OrderStatus.IN_SHOP
                || status == OrderStatus.PICKED_UP
                || status == OrderStatus.ON_WAY)) {
            log.warn("🚫 Статус заказа не подходит для возврата в магазин: orderId={}, status={}", orderId, status);
            return new CancelResult(false, false, false, null);
        }
        order.setStatus(OrderStatus.RETURNED);
        if (cancelReason != null && !cancelReason.isBlank()) {
            order.setCourierCancelReason(cancelReason.trim());
        }
        orderRepository.save(order);
        log.info("↩️ Заказ помечен как возвращён в магазин курьером: orderId={}, courierUserId={}", orderId, courierUser.getId());

        var penaltyResult = courierPenaltyService.checkAndApplyPenalties(order, courierUser, true);
        if (penaltyResult.anyApplied()) {
            log.info("⚠️ Штраф применён при возврате: orderId={}, courierUserId={}, reason={}",
                    orderId, courierUser.getId(), penaltyResult.reason());
        } else {
            Optional<org.example.flower_delivery.model.Courier> courierOpt = courierService.findByUser(courierUser);
            courierOpt.ifPresent(courier -> {
                BigDecimal commission = calculateCommissionForCourier(order, courierUser);
                if (commission != null && commission.compareTo(BigDecimal.ZERO) > 0) {
                    log.info("Возврат комиссии после возврата в магазин: orderId={}, courierId={}, amount={}",
                            orderId, courier.getId(), commission);
                    courierService.addToBalance(courierUser, commission);
                    courierTransactionService.addCommissionRefund(courier, order, commission);
                }
            });
        }
        return new CancelResult(true, penaltyResult.anyApplied(), penaltyResult.notifyAdmin(), penaltyResult.reason());
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
    public Order createMultiStopOrder(Shop shop, LocalDate deliveryDate,
                                       org.example.flower_delivery.model.DeliveryInterval deliveryInterval,
                                       String comment,
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
                .deliveryInterval(deliveryInterval)
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
        BigDecimal shopLatBd = order.getEffectivePickupLatitude();
        BigDecimal shopLonBd = order.getEffectivePickupLongitude();
        if (shopLatBd == null || shopLonBd == null ||
                order.getDeliveryLatitude() == null || order.getDeliveryLongitude() == null) {
            return;
        }
        double shopLat = shopLatBd.doubleValue();
        double shopLon = shopLonBd.doubleValue();
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
        BigDecimal shopLatBd = order.getEffectivePickupLatitude();
        BigDecimal shopLonBd = order.getEffectivePickupLongitude();
        if (shopLatBd == null || shopLonBd == null) {
            log.warn("Невозможно пересчитать мультидоставку: у магазина нет координат (orderId={})", order.getId());
            return;
        }
        double shopLat = shopLatBd.doubleValue();
        double shopLon = shopLonBd.doubleValue();

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


