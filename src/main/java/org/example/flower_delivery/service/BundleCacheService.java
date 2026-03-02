package org.example.flower_delivery.service;

import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Фоновый сервис предрасчёта связок заказов.
 * <p>
 * Запускается по расписанию (каждые 2 мин), анализирует все NEW заказы,
 * кэширует оптимальный порядок точек для пар и троек (без курьера).
 * Когда курьер нажимает «Доступные заказы», связки берутся из кэша — ответ мгновенный.
 */
@Slf4j
@Service
public class BundleCacheService {

    private final OrderService orderService;
    private final OrderBundleService orderBundleService;

    /** Кэш: ключ = "id1,id2" или "id1,id2,id3" (отсортированные UUID), значение = предрасчитанная связка. */
    private final Map<String, OrderBundleService.CachedBundle> cache = new ConcurrentHashMap<>();

    /** Макс. заказов для анализа (из 80 загружаемых). */
    private static final int MAX_ORDERS_FOR_CACHE = 20;

    /** Макс. комбинаций для предрасчёта (пары + тройки). */
    private static final int MAX_COMBINATIONS = 80;

    public BundleCacheService(OrderService orderService, OrderBundleService orderBundleService) {
        this.orderService = orderService;
        this.orderBundleService = orderBundleService;
    }


    @PostConstruct
    public void init() {
        refreshCache();
    }

    @Scheduled(fixedDelayString = "${app.bundle-cache.interval-ms:120000}") // 2 мин по умолчанию
    public void refreshCache() {
        try {
            List<Order> orders = orderService.getAvailableOrdersWithShop();
            if (orders.size() < 2) {
                cache.clear();
                return;
            }
            List<Order> limited = orders.size() > MAX_ORDERS_FOR_CACHE
                    ? orders.subList(0, MAX_ORDERS_FOR_CACHE)
                    : orders;

            Map<String, OrderBundleService.CachedBundle> newCache = new HashMap<>();
            int count = 0;

            // Пары
            for (int i = 0; i < limited.size() && count < MAX_COMBINATIONS; i++) {
                for (int j = i + 1; j < limited.size() && count < MAX_COMBINATIONS; j++) {
                    Order a = limited.get(i), b = limited.get(j);
                    if (hasCoords(a) && hasCoords(b)) {
                        Optional<OrderBundleService.CachedBundle> opt = orderBundleService.computeBundleWithoutCourier(List.of(a, b));
                        opt.ifPresent(cb -> {
                            String key = bundleKey(cb.orderIds());
                            newCache.put(key, cb);
                        });
                        count++;
                    }
                }
            }
            // Тройки
            for (int i = 0; i < limited.size() && count < MAX_COMBINATIONS; i++) {
                for (int j = i + 1; j < limited.size() && count < MAX_COMBINATIONS; j++) {
                    for (int k = j + 1; k < limited.size() && count < MAX_COMBINATIONS; k++) {
                        Order a = limited.get(i), b = limited.get(j), c = limited.get(k);
                        if (hasCoords(a) && hasCoords(b) && hasCoords(c)) {
                            Optional<OrderBundleService.CachedBundle> opt = orderBundleService.computeBundleWithoutCourier(List.of(a, b, c));
                            opt.ifPresent(cb -> {
                                String key = bundleKey(cb.orderIds());
                                newCache.put(key, cb);
                            });
                            count++;
                        }
                    }
                }
            }

            cache.clear();
            cache.putAll(newCache);
            log.debug("Кэш связок обновлён: {} записей", cache.size());
        } catch (Exception e) {
            log.warn("Ошибка обновления кэша связок: {}", e.getMessage());
        }
    }

    /** Получить связку из кэша по ID заказов. */
    public Optional<OrderBundleService.CachedBundle> getCachedBundle(Set<UUID> orderIds) {
        if (orderIds.size() < 2 || orderIds.size() > 3) return Optional.empty();
        List<UUID> sorted = new ArrayList<>(orderIds);
        sorted.sort(UUID::compareTo);
        return Optional.ofNullable(cache.get(bundleKey(sorted)));
    }

    private static String bundleKey(List<UUID> ids) {
        return String.join(",", ids.stream().map(UUID::toString).toList());
    }

    private static boolean hasCoords(Order o) {
        return o.getEffectivePickupLatitude() != null && o.getEffectivePickupLongitude() != null
                && o.getDeliveryLatitude() != null && o.getDeliveryLongitude() != null;
    }
}
