package org.example.flower_delivery.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

/**
 * Сервис расчёта стоимости доставки.
 * <p>
 * Что делает:
 * 1. Считает расстояние между двумя точками (формула Haversine)
 * 2. По расстоянию определяет цену из тарифной сетки
 * <p>
 * Тарифы загружаются из application.properties:
 * app.tariffs.3=300  (до 3 км = 300₽)
 * app.tariffs.5=400  (до 5 км = 400₽)
 * и т.д.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryPriceService {

    private final Environment env;

    /**
     * Радиус Земли в километрах (для расчёта расстояния).
     */
    private static final double EARTH_RADIUS_KM = 6371.0;
    
    /**
     * Коэффициент для пересчёта расстояния "по прямой" в расстояние "по дорогам".
     * Используется как fallback если OSRM недоступен.
     */
    private static final double ROAD_DISTANCE_COEFFICIENT = 1.6;
    
    /**
     * Корректирующий коэффициент для OSRM.
     * OSRM строит оптимальный маршрут, а 2GIS учитывает реальные объезды.
     * Эмпирически: OSRM занижает на 15-25% для дальних расстояний.
     */
    private static final double OSRM_CORRECTION_COEFFICIENT = 1.2;

    /**
     * Тарифная сетка: км -> цена.
     * TreeMap для автоматической сортировки по ключу.
     */
    private final TreeMap<Integer, BigDecimal> tariffs = new TreeMap<>();

    /**
     * Результат расчёта доставки.
     */
    public record DeliveryCalculation(
            double distanceKm,        // Расстояние в км
            BigDecimal price,         // Рекомендуемая цена
            String priceDescription   // Описание для юзера
    ) {}

    @PostConstruct
    public void loadTariffs() {
        // Загружаем тарифы из конфига
        // Формат: app.tariffs.3=300, app.tariffs.5=400 и т.д.
        int[] distances = {3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23, 25, 27, 30};
        
        for (int km : distances) {
            String key = "app.tariffs." + km;
            String value = env.getProperty(key);
            if (value != null) {
                tariffs.put(km, new BigDecimal(value));
            }
        }
        
        log.info("Загружено {} тарифов: {}", tariffs.size(), tariffs);
        
        if (tariffs.isEmpty()) {
            // Дефолтные тарифы если конфиг пустой
            tariffs.put(3, new BigDecimal("300"));
            tariffs.put(5, new BigDecimal("400"));
            tariffs.put(7, new BigDecimal("500"));
            tariffs.put(9, new BigDecimal("700"));
            tariffs.put(11, new BigDecimal("850"));
            tariffs.put(13, new BigDecimal("1000"));
            tariffs.put(15, new BigDecimal("1150"));
            tariffs.put(17, new BigDecimal("1300"));
            tariffs.put(19, new BigDecimal("1450"));
            tariffs.put(21, new BigDecimal("1550"));
            tariffs.put(23, new BigDecimal("1650"));
            tariffs.put(25, new BigDecimal("1750"));
            tariffs.put(27, new BigDecimal("1850"));
            tariffs.put(30, new BigDecimal("2000"));
            log.warn("Использованы дефолтные тарифы!");
        }
    }

    /**
     * Рассчитать расстояние между двумя точками (формула Haversine).
     * <p>
     * Формула Haversine — это способ расчёта расстояния по поверхности сферы (Земли)
     * между двумя точками, заданными широтой и долготой.
     *
     * @param lat1 Широта точки 1 (магазин)
     * @param lon1 Долгота точки 1 (магазин)
     * @param lat2 Широта точки 2 (доставка)
     * @param lon2 Долгота точки 2 (доставка)
     * @return Расстояние в километрах
     */
    public double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        // Переводим градусы в радианы
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double deltaLat = Math.toRadians(lat2 - lat1);
        double deltaLon = Math.toRadians(lon2 - lon1);

        // Формула Haversine
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                   Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                   Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        double distance = EARTH_RADIUS_KM * c;
        
        log.debug("Расстояние между [{},{}] и [{},{}] = {} км", 
                lat1, lon1, lat2, lon2, distance);
        
        return distance;
    }

    /**
     * Определить цену по расстоянию.
     *
     * @param distanceKm Расстояние в км
     * @return Цена доставки
     */
    public BigDecimal getPriceByDistance(double distanceKm) {
        // Находим ближайший тариф >= расстояния
        for (Map.Entry<Integer, BigDecimal> entry : tariffs.entrySet()) {
            if (distanceKm <= entry.getKey()) {
                return entry.getValue();
            }
        }
        
        // Если расстояние больше максимального тарифа — берём максимальную цену
        // + добавляем по 100₽ за каждые 3 км сверх
        Map.Entry<Integer, BigDecimal> lastEntry = tariffs.lastEntry();
        int extraKm = (int) Math.ceil(distanceKm - lastEntry.getKey());
        int extraBlocks = (extraKm + 2) / 3; // Округляем вверх до блоков по 3 км
        BigDecimal extraPrice = new BigDecimal(extraBlocks * 100);
        
        return lastEntry.getValue().add(extraPrice);
    }

    /**
     * Рассчитать доставку.
     *
     * @param shopLat     Широта магазина
     * @param shopLon     Долгота магазина
     * @param deliveryLat Широта доставки
     * @param deliveryLon Долгота доставки
     * @return Результат расчёта
     */
    public DeliveryCalculation calculate(double shopLat, double shopLon, 
                                         double deliveryLat, double deliveryLon) {
        // Пробуем получить расстояние по дорогам через OSRM (бесплатно)
        Double roadDistance = getOsrmDistance(shopLat, shopLon, deliveryLat, deliveryLon);
        
        // Если OSRM не сработал — используем коэффициент
        if (roadDistance == null) {
            double straightDistance = calculateDistance(shopLat, shopLon, deliveryLat, deliveryLon);
            roadDistance = straightDistance * ROAD_DISTANCE_COEFFICIENT;
            log.warn("OSRM недоступен, используем коэффициент: {} × {} = {} км", 
                    String.format("%.1f", straightDistance), ROAD_DISTANCE_COEFFICIENT, 
                    String.format("%.1f", roadDistance));
        }
        
        BigDecimal price = getPriceByDistance(roadDistance);
        
        // Округляем расстояние до 1 знака после запятой
        double roundedDistance = Math.round(roadDistance * 10.0) / 10.0;
        
        String description = String.format("%.1f км — %s₽", roundedDistance, price);
        
        log.info("Расчёт доставки: {} км = {}₽", roundedDistance, price);
        
        return new DeliveryCalculation(roundedDistance, price, description);
    }
    
    /**
     * Получить расстояние по дорогам через OSRM API (OpenStreetMap).
     * Бесплатно и без регистрации!
     * 
     * @return расстояние в км, или null если ошибка
     */
    private Double getOsrmDistance(double lat1, double lon1, double lat2, double lon2) {
        try {
            // OSRM публичный сервер (бесплатно)
            String url = String.format(
                    java.util.Locale.US,
                    "https://router.project-osrm.org/route/v1/driving/%f,%f;%f,%f?overview=false",
                    lon1, lat1, lon2, lat2  // OSRM принимает: lon,lat (не lat,lon!)
            );
            
            log.debug("OSRM URL: {}", url);
            
            java.net.URL osrmUrl = new java.net.URL(url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) osrmUrl.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);  // 3 секунды таймаут
            conn.setReadTimeout(3000);
            
            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                log.warn("OSRM вернул код: {}", responseCode);
                return null;
            }
            
            // Читаем ответ
            StringBuilder response = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }
            
            // Парсим JSON
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(response.toString());
            
            String code = root.get("code").asText();
            if (!"Ok".equals(code)) {
                log.warn("OSRM статус: {}", code);
                return null;
            }
            
            // Расстояние в метрах
            double distanceMeters = root.get("routes").get(0).get("distance").asDouble();
            double distanceKm = distanceMeters / 1000.0;
            
            // Применяем динамический корректирующий коэффициент
            // Ближние расстояния — OSRM точен, дальние — занижает
            double coefficient = calculateOsrmCoefficient(distanceKm);
            double correctedDistance = distanceKm * coefficient;
            
            log.info("OSRM расстояние: {} км × {} = {} км", 
                    String.format("%.1f", distanceKm), 
                    String.format("%.2f", coefficient),
                    String.format("%.1f", correctedDistance));
            return correctedDistance;
            
        } catch (Exception e) {
            log.warn("OSRM ошибка: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Рассчитать динамический коэффициент для OSRM.
     * 
     * Калибровка для Челябинска:
     * - Труда 156в: OSRM=3.1, реально=3.0 → коэфф 1.0
     * - Академическая 7: OSRM=12.9, реально=16 → коэфф 1.24
     * 
     * Логика:
     * - До 5 км: 1.0 (OSRM точен)
     * - 5-12 км: плавно от 1.0 до 1.24
     * - Более 12 км: 1.24
     */
    private double calculateOsrmCoefficient(double distanceKm) {
        if (distanceKm <= 5) {
            return 1.0;
        } else if (distanceKm <= 12) {
            // Плавный рост: при 5 км = 1.0, при 12 км = 1.24
            double progress = (distanceKm - 5) / 7.0;
            return 1.0 + (0.24 * progress);
        } else {
            return 1.24;
        }
    }

    /**
     * Получить минимальную цену (для валидации).
     */
    public BigDecimal getMinPrice() {
        return tariffs.isEmpty() ? new BigDecimal("300") : tariffs.firstEntry().getValue();
    }

    /**
     * Получить тарифную сетку для отображения.
     */
    public String getTariffDescription() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, BigDecimal> entry : tariffs.entrySet()) {
            sb.append("• до ").append(entry.getKey()).append(" км — ")
              .append(entry.getValue()).append("₽\n");
        }
        return sb.toString();
    }
}
