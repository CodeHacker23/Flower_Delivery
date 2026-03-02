package org.example.flower_delivery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.config.RegionConfig;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.OrderStop;
import org.example.flower_delivery.repository.OrderStopRepository;
import org.example.flower_delivery.util.GeoUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * Сервис связок заказов: подбор 2–3 заказов по пути.
 * OSRM Trip API — оптимальный порядок точек по дорогам (TSP).
 * Fallback: Haversine (по прямой), если OSRM недоступен.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderBundleService {

    private final OrderStopRepository orderStopRepository;
    private final RegionConfig regionConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    @Lazy
    private BundleCacheService bundleCacheService;

    @Value("${app.osrm.url:}")
    private String osrmBaseUrl;

    /** Предрасчитанная связка (без курьера) для кэша. */
    public record CachedBundle(List<UUID> orderIds, List<double[]> waypoints, double distanceKm) {}

    /** Результат: связка заказов с оптимальным порядком и расстоянием. */
    public record OrderBundle(
            List<Order> ordersInRoute,  // Заказы в порядке маршрута
            List<Integer> indicesInList, // Индексы в исходном списке (1-based для UI)
            double totalDistanceKm,
            String yandexRouteUrl,
            String twoGisRouteUrl
    ) {}

    /**
     * Найти 1–2 рекомендуемые связки из списка заказов.
     * Используем OSRM Route: перебираем перестановки (shop_i, delivery_i) и выбираем кратчайший маршрут.
     *
     * @param orders     список доступных заказов (с подгруженным shop)
     * @param courierLat широта курьера
     * @param courierLon долгота курьера
     * @return до 2 связок: лучшая из 3 заказов (если есть), лучшая из 2 заказов
     */
    public List<OrderBundle> findRecommendedBundles(List<Order> orders, double courierLat, double courierLon) {
        List<OrderBundle> result = new ArrayList<>();
        if (orders == null || orders.size() < 2) return result;

        try {
            // Собираем заказы с координатами (магазин + доставка)
            List<OrderWithCoords> valid = new ArrayList<>();
            for (int i = 0; i < orders.size(); i++) {
                Order o = orders.get(i);
                Coords shop = getShopCoords(o);
                Coords delivery = getDeliveryCoords(o);
                if (shop != null && delivery != null) {
                    valid.add(new OrderWithCoords(o, i + 1, shop, delivery));
                }
            }
            if (valid.size() < 2) return result;

            // До 15 заказов для анализа связок (предрасчёт в фоне через BundleCacheService)
            int maxForBundles = Math.min(15, valid.size());
            List<OrderWithCoords> limited = valid.subList(0, maxForBundles);

            // Сначала пробуем кэш (мгновенный ответ)
            if (bundleCacheService != null) {
                List<OrderBundle> fromCache = findBundlesFromCache(limited, courierLat, courierLon);
                if (!fromCache.isEmpty()) {
                    return fromCache;
                }
            }
            // Fallback: расчёт на лету
            if (limited.size() >= 3) {
                OrderBundle best3 = findBestBundle(limited, 3, courierLat, courierLon);
                if (best3 != null) result.add(best3);
            }
            OrderBundle best2 = findBestBundle(limited, 2, courierLat, courierLon);
            if (best2 != null) result.add(best2);
            if (limited.size() >= 2) {
                OrderBundle alt = findSecondBestBundle(limited, 2, courierLat, courierLon, best2);
                if (alt != null) result.add(alt);
            }
        } catch (Exception e) {
            log.warn("OSRM недоступен, связки не рассчитаны: {}", e.getMessage());
        }
        return result;
    }

    /** Связки из кэша: добавляем курьера, считаем расстояние, строим URL. */
    private List<OrderBundle> findBundlesFromCache(List<OrderWithCoords> valid, double courierLat, double courierLon) {
        List<OrderBundle> result = new ArrayList<>();
        Map<UUID, OrderWithCoords> byId = new java.util.HashMap<>();
        for (OrderWithCoords owc : valid) byId.put(owc.order.getId(), owc);

        List<CachedBundleWithDistance> candidates = new ArrayList<>();
        for (int i = 0; i < valid.size(); i++) {
            for (int j = i + 1; j < valid.size(); j++) {
                Set<UUID> ids = Set.of(valid.get(i).order.getId(), valid.get(j).order.getId());
                bundleCacheService.getCachedBundle(ids).ifPresent(cb -> {
                    double firstLeg = GeoUtil.distanceKm(courierLat, courierLon,
                            cb.waypoints().get(0)[0], cb.waypoints().get(0)[1]);
                    candidates.add(new CachedBundleWithDistance(cb, firstLeg + cb.distanceKm()));
                });
            }
        }
        for (int i = 0; i < valid.size(); i++) {
            for (int j = i + 1; j < valid.size(); j++) {
                for (int k = j + 1; k < valid.size(); k++) {
                    Set<UUID> ids = Set.of(valid.get(i).order.getId(), valid.get(j).order.getId(), valid.get(k).order.getId());
                    bundleCacheService.getCachedBundle(ids).ifPresent(cb -> {
                        double firstLeg = GeoUtil.distanceKm(courierLat, courierLon,
                                cb.waypoints().get(0)[0], cb.waypoints().get(0)[1]);
                        candidates.add(new CachedBundleWithDistance(cb, firstLeg + cb.distanceKm()));
                    });
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(a.totalKm(), b.totalKm()));
        Set<Set<UUID>> seen = new java.util.HashSet<>();
        for (CachedBundleWithDistance c : candidates) {
            Set<UUID> ids = Set.copyOf(c.cached().orderIds());
            if (seen.contains(ids)) continue;
            seen.add(ids);
            OrderBundle ob = buildOrderBundleFromCache(c.cached(), byId, courierLat, courierLon);
            if (ob != null) result.add(ob);
            if (result.size() >= 3) break;
        }
        return result;
    }

    private record CachedBundleWithDistance(CachedBundle cached, double totalKm) {}

    private OrderBundle buildOrderBundleFromCache(CachedBundle cb, Map<UUID, OrderWithCoords> byId,
                                                  double courierLat, double courierLon) {
        List<OrderWithCoords> selected = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        for (UUID id : cb.orderIds()) {
            OrderWithCoords owc = byId.get(id);
            if (owc == null) return null;
            selected.add(owc);
            indices.add(owc.indexInList());
        }
        List<double[]> waypoints = new ArrayList<>();
        waypoints.add(new double[]{courierLat, courierLon});
        waypoints.addAll(cb.waypoints());
        String yandexUrl = buildYandexUrlFromAddresses(selected, waypoints, courierLat, courierLon);
        String twoGisUrl = build2GisMultiPointUrl(courierLat, courierLon, waypoints);
        return new OrderBundle(
                selected.stream().map(owc -> owc.order).toList(),
                indices,
                cb.distanceKm() + GeoUtil.distanceKm(courierLat, courierLon, cb.waypoints().get(0)[0], cb.waypoints().get(0)[1]),
                yandexUrl,
                twoGisUrl
        );
    }

    private OrderBundle findBestBundle(List<OrderWithCoords> valid, int size, double courierLat, double courierLon) {
        return findAllBundles(valid, size, courierLat, courierLon).stream()
                .min((a, b) -> Double.compare(a.totalDistanceKm(), b.totalDistanceKm()))
                .orElse(null);
    }

    /** Вторая по расстоянию связка (другая комбинация заказов, не та же пара). */
    private OrderBundle findSecondBestBundle(List<OrderWithCoords> valid, int size, double courierLat, double courierLon,
                                             OrderBundle best) {
        if (best == null) return findBestBundle(valid, size, courierLat, courierLon);
        Set<Integer> bestSet = Set.copyOf(best.indicesInList());
        return findAllBundles(valid, size, courierLat, courierLon).stream()
                .filter(b -> !Set.copyOf(b.indicesInList()).equals(bestSet))
                .min((a, b) -> Double.compare(a.totalDistanceKm(), b.totalDistanceKm()))
                .orElse(null);
    }

    private List<OrderBundle> findAllBundles(List<OrderWithCoords> valid, int size, double courierLat, double courierLon) {
        List<List<Integer>> permutations = generatePermutations(valid.size(), size);
        return permutations.parallelStream()
                .map(perm -> {
                    List<OrderWithCoords> selected = new ArrayList<>();
                    for (int idx : perm) selected.add(valid.get(idx));
                    RouteResult route = computeRoute(selected, courierLat, courierLon);
                    if (route == null) return null;
                    List<Order> ordersInRoute = selected.stream().map(owc -> owc.order).toList();
                    List<Integer> indices = selected.stream().map(owc -> owc.indexInList).toList();
                    return new OrderBundle(
                            ordersInRoute,
                            indices,
                            route.distanceKm,
                            buildYandexUrlFromAddresses(selected, route.waypoints, courierLat, courierLon),
                            build2GisMultiPointUrl(courierLat, courierLon, route.waypoints)
                    );
                })
                .filter(b -> b != null)
                .toList();
    }

    private RouteResult computeRoute(List<OrderWithCoords> ordersInOrder, double courierLat, double courierLon) {
        List<double[]> waypoints = new ArrayList<>();
        waypoints.add(new double[]{courierLat, courierLon});

        // Все заказы из ОДНОГО магазина? → один визит магазина, затем доставки в оптимальном порядке
        boolean sameShop = ordersInOrder.size() > 1 && ordersInOrder.stream()
                .allMatch(owc -> Math.abs(owc.shop.lat - ordersInOrder.get(0).shop.lat) < 1e-6
                        && Math.abs(owc.shop.lon - ordersInOrder.get(0).shop.lon) < 1e-6);

        if (sameShop) {
            waypoints.add(new double[]{ordersInOrder.get(0).shop.lat, ordersInOrder.get(0).shop.lon});
            for (OrderWithCoords owc : ordersInOrder) {
                waypoints.add(new double[]{owc.delivery.lat, owc.delivery.lon});
            }
        } else {
            // Разные магазины: courier, shop1, del1, shop2, del2, ...
            for (OrderWithCoords owc : ordersInOrder) {
                waypoints.add(new double[]{owc.shop.lat, owc.shop.lon});
                waypoints.add(new double[]{owc.delivery.lat, owc.delivery.lon});
            }
        }
        // OSRM Trip API — оптимальный порядок по дорогам (1 запрос)
        if (osrmBaseUrl != null && !osrmBaseUrl.isBlank()) {
            RouteResult osrm = callOsrmTrip(waypoints, sameShop);
            if (osrm != null) return osrm;
        }
        // Fallback: Haversine (по прямой)
        double totalKm = 0;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            double[] a = waypoints.get(i);
            double[] b = waypoints.get(i + 1);
            totalKm += GeoUtil.distanceKm(a[0], a[1], b[0], b[1]);
        }
        return new RouteResult(waypoints, totalKm);
    }

    /** OSRM Trip API — оптимальный порядок точек по дорогам (TSP). */
    private RouteResult callOsrmTrip(List<double[]> waypoints, boolean sameShop) {
        if (waypoints.size() < 2) return null;
        try {
            StringBuilder coords = new StringBuilder();
            for (double[] wp : waypoints) {
                if (coords.length() > 0) coords.append(";");
                coords.append(String.format(java.util.Locale.US, "%f,%f", wp[1], wp[0]));
            }
            String url = osrmBaseUrl.replaceAll("/$", "") + "/trip/v1/driving/" + coords
                    + "?roundtrip=false&source=first&destination=last&overview=false";
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JsonNode root = objectMapper.readTree(sb.toString());
            if (!"Ok".equals(root.path("code").asText(""))) return null;
            JsonNode waypointsNode = root.get("waypoints");
            if (waypointsNode == null || !waypointsNode.isArray()) return null;
            // Сортируем по trips_index — порядок посещения
            List<JsonNode> sorted = new ArrayList<>();
            for (JsonNode w : waypointsNode) sorted.add(w);
            sorted.sort((a, b) -> Integer.compare(a.path("trips_index").asInt(0), b.path("trips_index").asInt(0)));
            // Проверка: магазин(ы) перед доставками
            int[] pos = new int[waypoints.size()];
            for (int i = 0; i < sorted.size(); i++) {
                pos[sorted.get(i).path("waypoint_index").asInt(0)] = i;
            }
            if (sameShop && waypoints.size() >= 4) {
                // Один магазин (idx 1), доставки (idx 2,3,...) — магазин должен быть перед всеми доставками
                for (int d = 2; d < waypoints.size(); d++) {
                    if (pos[1] > pos[d]) return null;
                }
            } else {
                // Разные магазины: shop_i перед del_i
                int nOrders = (waypoints.size() - 1) / 2;
                for (int i = 0; i < nOrders; i++) {
                    int shopIdx = 2 * i + 1, delIdx = 2 * i + 2;
                    if (pos[shopIdx] > pos[delIdx]) return null;
                }
            }
            List<double[]> ordered = new ArrayList<>();
            for (JsonNode w : sorted) {
                JsonNode loc = w.get("location");
                if (loc != null && loc.isArray()) {
                    ordered.add(new double[]{loc.get(1).asDouble(), loc.get(0).asDouble()});
                }
            }
            double distM = 0;
            JsonNode trips = root.get("trips");
            if (trips != null && trips.isArray() && trips.size() > 0) {
                JsonNode legs = trips.get(0).get("legs");
                if (legs != null && legs.isArray()) {
                    for (JsonNode l : legs) distM += l.path("distance").asDouble(0);
                }
            }
            List<double[]> resultWaypoints = ordered.isEmpty() ? waypoints : ordered;
            double distKm = distM > 0 ? distM / 1000.0 : computeHaversineKm(resultWaypoints);
            return new RouteResult(resultWaypoints, distKm);
        } catch (Exception e) {
            log.debug("OSRM Trip: {}", e.getMessage());
            return null;
        }
    }

    private double computeHaversineKm(List<double[]> waypoints) {
        double total = 0;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            double[] a = waypoints.get(i), b = waypoints.get(i + 1);
            total += GeoUtil.distanceKm(a[0], a[1], b[0], b[1]);
        }
        return total;
    }

    private record RouteResult(List<double[]> waypoints, double distanceKm) {}
    private record Coords(double lat, double lon) {}
    private record OrderWithCoords(Order order, int indexInList, Coords shop, Coords delivery) {}

    private Coords getShopCoords(Order o) {
        BigDecimal lat = o.getEffectivePickupLatitude();
        BigDecimal lon = o.getEffectivePickupLongitude();
        if (lat == null || lon == null) return null;
        return new Coords(lat.doubleValue(), lon.doubleValue());
    }

    private Coords getDeliveryCoords(Order o) {
        if (o.isMultiStopOrder()) {
            List<OrderStop> stops = orderStopRepository.findByOrderIdOrderByStopNumberAsc(o.getId());
            if (!stops.isEmpty()) {
                OrderStop first = stops.get(0);
                if (first.getDeliveryLatitude() != null && first.getDeliveryLongitude() != null) {
                    return new Coords(first.getDeliveryLatitude().doubleValue(),
                            first.getDeliveryLongitude().doubleValue());
                }
            }
        }
        if (o.getDeliveryLatitude() != null && o.getDeliveryLongitude() != null) {
            return new Coords(o.getDeliveryLatitude().doubleValue(), o.getDeliveryLongitude().doubleValue());
        }
        return null;
    }

    private List<List<Integer>> generatePermutations(int n, int k) {
        List<List<Integer>> result = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < n; i++) indices.add(i);
        generateCombinations(indices, k, 0, new ArrayList<>(), result);
        return result;
    }

    private void generateCombinations(List<Integer> indices, int k, int start,
                                       List<Integer> current, List<List<Integer>> result) {
        if (current.size() == k) {
            permute(current, 0, result);
            return;
        }
        for (int i = start; i < indices.size(); i++) {
            current.add(indices.get(i));
            generateCombinations(indices, k, i + 1, current, result);
            current.remove(current.size() - 1);
        }
    }

    private void permute(List<Integer> arr, int idx, List<List<Integer>> result) {
        if (idx == arr.size()) {
            result.add(new ArrayList<>(arr));
            return;
        }
        for (int i = idx; i < arr.size(); i++) {
            Collections.swap(arr, idx, i);
            permute(arr, idx + 1, result);
            Collections.swap(arr, idx, i);
        }
    }

    /**
     * Яндекс.Карты: передаём координаты в rtext, чтобы карта не улетала в другой город
     * (при передаче адресов строкой геокодер может выбрать, например, "Калинина 20" в Якутске).
     * Порядок точек — как в waypoints (курьер → магазин1 → доставка1 → ...).
     */
    private String buildYandexUrlFromAddresses(List<OrderWithCoords> selected, List<double[]> waypoints,
                                               double courierLat, double courierLon) {
        String path = (regionConfig != null && regionConfig.getYandexPath() != null && !regionConfig.getYandexPath().isBlank())
                ? regionConfig.getYandexPath() : "74/chelyabinsk";
        // rtext: координаты lat,lon через ~. Первая точка "~" = от текущего местоположения, далее точки маршрута по порядку
        StringBuilder rtext = new StringBuilder("~");
        for (int i = 1; i < waypoints.size(); i++) {
            double[] wp = waypoints.get(i);
            if (rtext.length() > 1) rtext.append("~");
            rtext.append(String.format(java.util.Locale.US, "%.6f,%.6f", wp[0], wp[1]));
        }
        if (rtext.length() <= 1) return buildYandexFallbackUrl(waypoints, path);
        // ll — центр карты по первой точке маршрута, чтобы открывалось в нужном регионе
        double[] first = waypoints.get(1);
        String ll = String.format(java.util.Locale.US, "%.4f,%.4f", first[1], first[0]); // lon,lat для ll
        return "https://yandex.ru/maps/" + path + "/?ll=" + ll + "&rtext=" + rtext + "&rtt=auto&z=12";
    }

    private String buildYandexFallbackUrl(List<double[]> waypoints, String path) {
        StringBuilder rtext = new StringBuilder();
        for (int i = 0; i < waypoints.size(); i++) {
            double[] wp = waypoints.get(i);
            if (i > 0) rtext.append("~");
            rtext.append(wp[0]).append(",").append(wp[1]);
        }
        return "https://yandex.ru/maps/" + path + "/?rtext=" + rtext + "&rtt=auto";
    }

    /** 2ГИС: несколько точек маршрута (lon,lat через |). Используется для одиночного заказа (3 точки) и т.п. */
    private String build2GisMultiPointUrl(double fromLat, double fromLon, List<double[]> waypoints) {
        String citySlug = regionConfig != null ? regionConfig.getTwoGisCity() : "chelyabinsk";
        return "https://2gis.ru/" + citySlug + "/directions/points/" + build2GisPointsString(waypoints);
    }

    /** Только точки (без курьера) — для связки: 4 точки в порядке забор1→доставка1→забор2→доставка2. */
    private String build2GisPointsUrl(List<double[]> waypoints) {
        if (waypoints == null || waypoints.isEmpty()) return "";
        String citySlug = regionConfig != null ? regionConfig.getTwoGisCity() : "chelyabinsk";
        return "https://2gis.ru/" + citySlug + "/directions/points/" + build2GisPointsString(waypoints);
    }

    private static String build2GisPointsString(List<double[]> waypoints) {
        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < waypoints.size(); i++) {
            if (i > 0) pts.append("%7C"); // |
            double[] wp = waypoints.get(i);
            pts.append(String.format(java.util.Locale.US, "%.6f", wp[1])).append("%2C").append(String.format(java.util.Locale.US, "%.6f", wp[0])); // lon,lat (2GIS API)
        }
        return pts.toString();
    }

    /**
     * Предрасчёт связки БЕЗ курьера (для кэша).
     * Возвращает оптимальный порядок waypoints и расстояние.
     */
    public Optional<CachedBundle> computeBundleWithoutCourier(List<Order> orders) {
        if (orders == null || orders.size() < 2 || orders.size() > 3) return Optional.empty();
        List<OrderWithCoords> valid = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            Order o = orders.get(i);
            Coords shop = getShopCoords(o);
            Coords delivery = getDeliveryCoords(o);
            if (shop != null && delivery != null) {
                valid.add(new OrderWithCoords(o, i + 1, shop, delivery));
            }
        }
        if (valid.size() != orders.size()) return Optional.empty();
        RouteResult route = computeRouteWithoutCourier(valid);
        if (route == null) return Optional.empty();
        List<UUID> ids = valid.stream().map(owc -> owc.order.getId()).toList();
        return Optional.of(new CachedBundle(ids, route.waypoints(), route.distanceKm()));
    }

    private RouteResult computeRouteWithoutCourier(List<OrderWithCoords> ordersInOrder) {
        List<double[]> waypoints = new ArrayList<>();
        boolean sameShop = ordersInOrder.size() > 1 && ordersInOrder.stream()
                .allMatch(owc -> Math.abs(owc.shop.lat - ordersInOrder.get(0).shop.lat) < 1e-6
                        && Math.abs(owc.shop.lon - ordersInOrder.get(0).shop.lon) < 1e-6);
        if (sameShop) {
            waypoints.add(new double[]{ordersInOrder.get(0).shop.lat, ordersInOrder.get(0).shop.lon});
            for (OrderWithCoords owc : ordersInOrder) {
                waypoints.add(new double[]{owc.delivery.lat, owc.delivery.lon});
            }
        } else {
            for (OrderWithCoords owc : ordersInOrder) {
                waypoints.add(new double[]{owc.shop.lat, owc.shop.lon});
                waypoints.add(new double[]{owc.delivery.lat, owc.delivery.lon});
            }
        }
        if (osrmBaseUrl != null && !osrmBaseUrl.isBlank()) {
            RouteResult osrm = callOsrmTrip(waypoints, sameShop);
            if (osrm != null) return osrm;
        }
        double totalKm = computeHaversineKm(waypoints);
        return new RouteResult(waypoints, totalKm);
    }

    /**
     * Маршрут для одиночного заказа: 3 точки — курьер → забор (магазин) → доставка.
     * Используется в сообщении «Заказ взят!» для кнопок Яндекс/2ГИС.
     */
    public record SingleOrderRouteUrls(String yandexUrl, String twoGisUrl) {}

    public Optional<SingleOrderRouteUrls> buildRouteForSingleOrder(Order order, double courierLat, double courierLon) {
        Coords shop = getShopCoords(order);
        Coords delivery = getDeliveryCoords(order);
        if (shop == null || delivery == null) return Optional.empty();
        List<double[]> waypoints = List.of(
                new double[]{courierLat, courierLon},
                new double[]{shop.lat, shop.lon},
                new double[]{delivery.lat, delivery.lon}
        );
        OrderWithCoords owc = new OrderWithCoords(order, 1, shop, delivery);
        String yandexUrl = buildYandexUrlFromAddresses(List.of(owc), waypoints, courierLat, courierLon);
        String twoGisUrl = build2GisMultiPointUrl(courierLat, courierLon, waypoints);
        return Optional.of(new SingleOrderRouteUrls(yandexUrl, twoGisUrl));
    }

    /**
     * Вернуть заказы в оптимальном порядке маршрута (забор1→доставка1→забор2→доставка2…).
     * Перебирает все перестановки заказов и выбирает порядок с минимальной длиной маршрута:
     * при наличии app.osrm.url используется OSRM Trip (по дорогам), иначе — Haversine (по прямой).
     * Текст «Связка взята!» и кнопки Яндекс/2ГИС строятся уже в этом порядке — точки в сообщении и на карте совпадают.
     */
    public List<Order> reorderByOptimalRoute(List<Order> orders) {
        if (orders == null || orders.size() < 2 || orders.size() > 3) return orders != null ? orders : List.of();
        List<OrderWithCoords> valid = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            Order o = orders.get(i);
            Coords shop = getShopCoords(o);
            Coords delivery = getDeliveryCoords(o);
            if (shop != null && delivery != null) {
                valid.add(new OrderWithCoords(o, i + 1, shop, delivery));
            }
        }
        if (valid.size() != orders.size()) return orders;
        List<List<Integer>> perms = generatePermutations(valid.size(), valid.size());
        List<Order> bestOrder = new ArrayList<>(orders);
        double bestKm = Double.MAX_VALUE;
        for (List<Integer> perm : perms) {
            List<OrderWithCoords> selected = new ArrayList<>();
            for (int idx : perm) selected.add(valid.get(idx));
            RouteResult r = computeRouteWithoutCourier(selected);
            if (r != null && r.distanceKm() < bestKm) {
                bestKm = r.distanceKm();
                bestOrder = selected.stream().map(owc -> owc.order).toList();
            }
        }
        return bestOrder;
    }

    /**
     * Построить URL маршрута для уже взятой связки (2–3 заказа).
     * Используется в сообщении «Связка взята!» для кнопок Яндекс/2ГИС.
     * Точки строго по порядку как в сообщении: курьер → забор1 → доставка1 → забор2 → доставка2 ...
     * (аральская 168 → комса 41 → худякова 13 → энтузиастов 47 и т.д.)
     */
    public Optional<OrderBundle> buildRouteForOrders(List<Order> orders, double courierLat, double courierLon) {
        if (orders == null || orders.size() < 2 || orders.size() > 3) return Optional.empty();
        List<OrderWithCoords> valid = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            Order o = orders.get(i);
            // Координаты только из БД: заказ (shop_pickup_* или shop), доставка (delivery_* или order_stops)
            Coords shop = getShopCoords(o);
            Coords delivery = getDeliveryCoords(o);
            if (shop != null && delivery != null) {
                valid.add(new OrderWithCoords(o, i + 1, shop, delivery));
            }
        }
        if (valid.size() != orders.size()) return Optional.empty();
        // Гарантируем порядок как в сообщении (1, 2, 3) — на случай если список как-то перепутан
        valid.sort(Comparator.comparingInt(OrderWithCoords::indexInList));
        // Порядок: курьер → забор1 → доставка1 → забор2 → доставка2 (для расчёта и Яндекс)
        List<double[]> waypointsWithCourier = new ArrayList<>();
        waypointsWithCourier.add(new double[]{courierLat, courierLon});
        for (OrderWithCoords owc : valid) {
            waypointsWithCourier.add(new double[]{owc.shop.lat, owc.shop.lon});
            waypointsWithCourier.add(new double[]{owc.delivery.lat, owc.delivery.lon});
        }
        double totalKm = computeHaversineKm(waypointsWithCourier);
        // Яндекс: "~" = от текущего местоположения, дальше точки по порядку
        String yandexUrl = buildYandexUrlFromAddresses(valid, waypointsWithCourier, courierLat, courierLon);
        // 2ГИС: первая точка = гео курьера, чтобы маршрут был «от меня» → забор1 → доставка1 → ... (удобно «Поехали»)
        String twoGisUrl = build2GisPointsUrl(waypointsWithCourier);
        OrderBundle bundle = new OrderBundle(
                valid.stream().map(owc -> owc.order).toList(),
                valid.stream().map(OrderWithCoords::indexInList).toList(),
                totalKm,
                yandexUrl,
                twoGisUrl
        );
        return Optional.of(bundle);
    }
}
