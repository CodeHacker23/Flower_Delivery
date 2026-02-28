package org.example.flower_delivery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.OrderStop;
import org.example.flower_delivery.repository.OrderStopRepository;
import org.example.flower_delivery.util.GeoUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.osrm.url:}")
    private String osrmBaseUrl;

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

            // 4 заказа = меньше OSRM-вызовов (публичный сервер не любит много параллельных)
            int maxForBundles = Math.min(4, valid.size());
            List<OrderWithCoords> limited = valid.subList(0, maxForBundles);

            // Последовательно (без parallel) — OSRM блокирует много одновременных подключений
            OrderBundle best3 = limited.size() >= 3 ? findBestBundle(limited, 3, courierLat, courierLon) : null;
            OrderBundle best2 = findBestBundle(limited, 2, courierLat, courierLon);
            OrderBundle best = (best3 != null && (best2 == null || best3.totalDistanceKm() <= best2.totalDistanceKm()))
                    ? best3 : best2;
            if (best != null) result.add(best);

            // Альтернатива — связка из ДРУГИХ заказов
            if (best != null && limited.size() >= 2) {
                Set<Integer> usedIndices = Set.copyOf(best.indicesInList());
                List<OrderWithCoords> rest = limited.stream()
                        .filter(owc -> !usedIndices.contains(owc.indexInList()))
                        .toList();
                if (rest.size() >= 2) {
                    OrderBundle alt = findBestBundle(rest, Math.min(2, rest.size()), courierLat, courierLon);
                    if (alt != null) result.add(alt);
                }
            }
        } catch (Exception e) {
            log.warn("OSRM недоступен, связки не рассчитаны: {}", e.getMessage());
        }
        return result;
    }

    private OrderBundle findBestBundle(List<OrderWithCoords> valid, int size, double courierLat, double courierLon) {
        List<List<Integer>> permutations = generatePermutations(valid.size(), size);
        return permutations.parallelStream()
                .map(perm -> {
                    List<OrderWithCoords> selected = new ArrayList<>();
                    for (int idx : perm) {
                        selected.add(valid.get(idx));
                    }
                    RouteResult route = computeRoute(selected, courierLat, courierLon);
                    if (route == null) return null;
                    List<Order> ordersInRoute = selected.stream().map(owc -> owc.order).toList();
                    List<Integer> indices = selected.stream().map(owc -> owc.indexInList).toList();
                    return new OrderBundle(
                            ordersInRoute,
                            indices,
                            route.distanceKm,
                            buildYandexMultiPointUrl(courierLat, courierLon, route.waypoints),
                            build2GisMultiPointUrl(courierLat, courierLon, route.waypoints)
                    );
                })
                .filter(b -> b != null)
                .min((a, b) -> Double.compare(a.totalDistanceKm(), b.totalDistanceKm()))
                .orElse(null);
    }

    private RouteResult computeRoute(List<OrderWithCoords> ordersInOrder, double courierLat, double courierLon) {
        // Точки: courier, shop1, del1, shop2, del2, [shop3, del3]
        List<double[]> waypoints = new ArrayList<>();
        waypoints.add(new double[]{courierLat, courierLon});
        for (OrderWithCoords owc : ordersInOrder) {
            waypoints.add(new double[]{owc.shop.lat, owc.shop.lon});
            waypoints.add(new double[]{owc.delivery.lat, owc.delivery.lon});
        }
        // OSRM Trip API — оптимальный порядок по дорогам (1 запрос)
        if (osrmBaseUrl != null && !osrmBaseUrl.isBlank()) {
            RouteResult osrm = callOsrmTrip(waypoints);
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
    private RouteResult callOsrmTrip(List<double[]> waypoints) {
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
            // Проверка: shop_i перед del_i (индексы 1,2 = заказ0; 3,4 = заказ1; ...)
            int nOrders = (waypoints.size() - 1) / 2;
            int[] pos = new int[waypoints.size()];
            for (int i = 0; i < sorted.size(); i++) {
                pos[sorted.get(i).path("waypoint_index").asInt(0)] = i;
            }
            for (int i = 0; i < nOrders; i++) {
                int shopIdx = 2 * i + 1, delIdx = 2 * i + 2;
                if (pos[shopIdx] > pos[delIdx]) return null; // доставка до магазина — невалидно
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
        if (o.getShop() == null) return null;
        BigDecimal lat = o.getShop().getLatitude();
        BigDecimal lon = o.getShop().getLongitude();
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

    private String buildYandexMultiPointUrl(double fromLat, double fromLon, List<double[]> waypoints) {
        StringBuilder rtext = new StringBuilder();
        for (int i = 0; i < waypoints.size(); i++) {
            double[] wp = waypoints.get(i);
            if (i > 0) rtext.append("~");
            rtext.append(wp[0]).append(",").append(wp[1]); // lat,lon
        }
        return "https://yandex.ru/maps/?rtext=" + rtext + "&rtt=auto";
    }

    private String build2GisMultiPointUrl(double fromLat, double fromLon, List<double[]> waypoints) {
        // 2GIS directions/points: lon,lat|lon,lat|...
        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < waypoints.size(); i++) {
            if (i > 0) pts.append("%7C"); // |
            double[] wp = waypoints.get(i);
            pts.append(wp[1]).append("%2C").append(wp[0]); // lon,lat
        }
        return "https://2gis.ru/directions/points/" + pts;
    }

    /**
     * Построить URL маршрута для уже взятой связки (2–3 заказа).
     * Используется в сообщении «Связка взята!» для кнопок Яндекс/2ГИС.
     */
    public Optional<OrderBundle> buildRouteForOrders(List<Order> orders, double courierLat, double courierLon) {
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
        OrderBundle bundle = findBestBundle(valid, valid.size(), courierLat, courierLon);
        return Optional.ofNullable(bundle);
    }
}
