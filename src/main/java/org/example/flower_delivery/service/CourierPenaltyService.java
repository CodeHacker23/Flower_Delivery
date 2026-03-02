package org.example.flower_delivery.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.*;
import org.example.flower_delivery.repository.OrderRepository;
import org.example.flower_delivery.repository.OrderStopRepository;
import org.example.flower_delivery.util.GeoUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Проверка при отмене/возврате заказа курьером.
 *
 * Логика по гео:
 * - Был в магазине (IN_SHOP) И у получателя (DELIVERED) по гео в радиусе 200 м → заказ фактически доставлен.
 *   Комиссию не возвращаем (списывается только комиссия, штрафа нет). Обход: курьер мог договориться с магазином и отвезти без бота.
 * - Был в магазине, но НЕ у получателя по гео → «звоночек», уведомляем админа. Штрафы назначает админ.
 *
 * Штраф 1000₽ — автоматически при большом числе отмен за период. Остальные штрафы — через админа.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CourierPenaltyService {

    private final CourierGeoService courierGeoService;
    private final CourierService courierService;
    private final CourierTransactionService courierTransactionService;
    private final OrderRepository orderRepository;
    private final OrderStopRepository orderStopRepository;

    @Value("${app.penalty.amount-500:500}")
    private BigDecimal penaltyAmount500;

    @Value("${app.penalty.amount-1000:1000}")
    private BigDecimal penaltyAmount1000;

    @Value("${app.penalty.cancelled-threshold:3}")
    private int cancelledThreshold;

    @Value("${app.penalty.cancelled-days:30}")
    private int cancelledDays;

    /** Пропускать проверку 200 м (для тестов, когда гео не на месте). */
    @Value("${app.penalty.skip-geo-check:true}")
    private boolean skipGeoCheck;

    /**
     * Результат проверки при отмене/возврате.
     * noRefund = true → комиссию не возвращаем (гео подтвердило доставку или штраф 1000).
     * notifyAdmin = true → курьер был в магазине, но не у получателя по гео — «звоночек» на разбор админу.
     */
    public record PenaltyResult(boolean noRefund, boolean penalty1000Applied, boolean notifyAdmin, String reason) {
        public boolean anyApplied() {
            return noRefund;
        }
    }

    /**
     * Проверить и применить штрафы при отмене/возврате заказа курьером.
     * Если штраф применён — комиссию не возвращаем.
     *
     * @return PenaltyResult — какие штрафы применены
     */
    @Transactional
    public PenaltyResult checkAndApplyPenalties(Order order, User courierUser, boolean isReturn) {
        Optional<Courier> courierOpt = courierService.findByUser(courierUser);
        if (courierOpt.isEmpty()) {
            return new PenaltyResult(false, false, false, "Курьер не найден");
        }
        Courier courier = courierOpt.get();

        boolean noRefund = false;
        boolean penalty1000 = false;
        boolean notifyAdmin = false;
        StringBuilder reason = new StringBuilder();

        List<OrderStatusGeoSnapshot> snapshots = courierGeoService.getSnapshotsForOrder(order.getId());
        boolean hasInShop = snapshots.stream().anyMatch(s -> s.getStatus() == OrderStatus.IN_SHOP);
        boolean hasDelivered = snapshots.stream().anyMatch(s -> s.getStatus() == OrderStatus.DELIVERED);
        boolean geoOk = hasInShop && hasDelivered && (skipGeoCheck || checkGeoWithin200m(order, snapshots));

        // Оба гео совпали (магазин + получатель) → штраф 500₽ за сговор, комиссию не возвращаем
        if (geoOk) {
            noRefund = true;
            if (courierService.chargeFromBalance(courierUser, penaltyAmount500)) {
                courierTransactionService.addPenalty(courier, order, "PENALTY_500", penaltyAmount500,
                        "Штраф за сговор: гео магазина и получателя совпали.");
                reason.append("Штраф 500₽ (совпадение гео магазина и получателя), комиссия не возвращается. ");
            } else {
                reason.append("Гео подтвердило доставку (магазин + получатель), комиссия не возвращается. ");
            }
        }
        // Нет гео (доставили без бота и отменили) → комиссию не возвращаем (вариант A)
        else if (!hasInShop && !hasDelivered) {
            noRefund = true;
            reason.append("Нет гео подтверждения — комиссия не возвращается. ");
        }
        // Был в магазине, но не у получателя → звоночек, админ разберётся (штрафы через админа)
        else if (hasInShop && !hasDelivered) {
            notifyAdmin = true;
            log.warn("⚠️ Звоночек: курьер был в магазине, но не у получателя по гео. orderId={}, courierUserId={} — на разбор админу",
                    order.getId(), courierUser.getId());
        }

        // Штраф 1000₽: много отменённых/возвращённых за последние N дней (уже включая текущий заказ)
        LocalDateTime since = LocalDateTime.now().minusDays(cancelledDays);
        long cancelledCount = orderRepository.countByCourierAndStatusInAndUpdatedAtSince(
                courierUser,
                List.of(OrderStatus.CANCELLED, OrderStatus.RETURNED),
                since
        );
        if (cancelledCount >= cancelledThreshold) {
            if (courierService.chargeFromBalance(courierUser, penaltyAmount1000)) {
                courierTransactionService.addPenalty(courier, order, "PENALTY_1000", penaltyAmount1000,
                        "Штраф за частые отмены: " + cancelledCount + " за " + cancelledDays + " дн.");
                penalty1000 = true;
                noRefund = true;
                reason.append("1000₽ (много отмен за период). ");
            } else {
                log.warn("Штраф 1000₽ не применён: недостаточно баланса у курьера {}", courier.getId());
            }
        }

        return new PenaltyResult(noRefund, penalty1000, notifyAdmin, reason.toString().trim());
    }

    private boolean checkGeoWithin200m(Order order, List<OrderStatusGeoSnapshot> snapshots) {
        Optional<Order> orderWithShop = orderRepository.findByIdWithShop(order.getId());
        if (orderWithShop.isEmpty()) return false;

        Order o = orderWithShop.get();
        BigDecimal refLat = o.getEffectivePickupLatitude();
        BigDecimal refLon = o.getEffectivePickupLongitude();
        if (refLat == null || refLon == null) {
            return false;
        }

        for (OrderStatusGeoSnapshot s : snapshots) {
            double lat = s.getCourierLat().doubleValue();
            double lon = s.getCourierLon().doubleValue();
            if (s.getStatus() == OrderStatus.IN_SHOP) {
                if (!GeoUtil.isWithinRadiusKm(lat, lon,
                        refLat.doubleValue(), refLon.doubleValue(),
                        GeoUtil.RADIUS_200_M_KM)) {
                    return false;
                }
            }
            if (s.getStatus() == OrderStatus.DELIVERED) {
                BigDecimal delLat = o.getDeliveryLatitude();
                BigDecimal delLon = o.getDeliveryLongitude();
                if (delLat == null || delLon == null) {
                    var stops = orderStopRepository.findByOrderIdOrderByStopNumberAsc(o.getId());
                    if (!stops.isEmpty()) {
                        var lastStop = stops.get(stops.size() - 1);
                        delLat = lastStop.getDeliveryLatitude();
                        delLon = lastStop.getDeliveryLongitude();
                    }
                }
                if (delLat == null || delLon == null) return false;
                if (!GeoUtil.isWithinRadiusKm(lat, lon, delLat.doubleValue(), delLon.doubleValue(), GeoUtil.RADIUS_200_M_KM)) {
                    return false;
                }
            }
        }
        return true;
    }
}
