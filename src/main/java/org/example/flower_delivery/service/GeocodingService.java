package org.example.flower_delivery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.config.RegionConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Сервис геокодирования через DaData.
 * <p>
 * Сначала вызывается API стандартизации (Clean) — он нормализует адрес и возвращает
 * координаты, лучше подходит для автоматической обработки. Если секретный ключ не задан
 * или Clean не вернул координаты — fallback на API подсказок (Suggest) с ограничением по городу.
 * <p>
 * Для адресов магазинов важно вводить адрес точно (улица и номер дома).
 * Документация: https://dadata.ru/api/clean/address/ и https://dadata.ru/api/suggest/address/
 */
@Slf4j
@Service
public class GeocodingService {

    private static final String DADATA_CLEAN_URL = "https://cleaner.dadata.ru/api/v1/clean/address";
    private static final String DADATA_SUGGEST_URL = "https://suggestions.dadata.ru/suggestions/api/4_1/rs/suggest/address";

    private final RegionConfig regionConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;

    @Value("${dadata.api-key}")
    private String apiKey;

    @Value("${dadata.secret-key:}")
    private String secretKey;
    
    public GeocodingService(RegionConfig regionConfig) {
        this.regionConfig = regionConfig;
        // Создаём RestTemplate с UTF-8 кодировкой
        this.restTemplate = new RestTemplate();
        this.restTemplate.getMessageConverters()
                .add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));
    }

    /**
     * Результат геокодирования.
     */
    public record GeocodingResult(
            double latitude,
            double longitude,
            String fullAddress,  // Полный адрес от DaData
            String city,         // Город
            String region        // Область
    ) {}

    /**
     * Геокодировать адрес.
     * Сначала пробуем API стандартизации (Clean) — точнее для автоматической обработки.
     * При отсутствии secret-key или при неудаче — fallback на API подсказок (Suggest).
     *
     * @param address Адрес от пользователя (например: "ул. Ленина 44, кв. 15")
     * @return Optional с координатами, или empty если не удалось
     */
    public Optional<GeocodingResult> geocode(String address) {
        String cleanAddress = cleanAddressForGeocoding(address);
        String fullAddress = regionConfig.enrichAddress(cleanAddress);
        log.debug("Геокодирование адреса: {}", fullAddress);

        if (secretKey != null && !secretKey.isBlank()) {
            Optional<GeocodingResult> cleanResult = geocodeWithClean(fullAddress);
            if (cleanResult.isPresent()) return cleanResult;
            log.debug("DaData Clean не вернул координаты, пробуем Suggest");
        }
        return geocodeWithSuggest(fullAddress);
    }

    /**
     * API стандартизации DaData (clean/address) — нормализует адрес и возвращает координаты.
     * Требует dadata.secret-key. Ответ: массив объектов с result, geo_lat, geo_lon, qc_geo, city, region.
     */
    private Optional<GeocodingResult> geocodeWithClean(String fullAddress) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(DADATA_CLEAN_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Token " + apiKey);
            conn.setRequestProperty("X-Secret", secretKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            // Тело: массив из одного адреса
            String body = objectMapper.writeValueAsString(List.of(fullAddress));
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int code = conn.getResponseCode();
            String responseStr = readResponseBody(conn, code);
            if (responseStr == null) return Optional.empty();

            JsonNode root = objectMapper.readTree(responseStr);
            if (!root.isArray() || root.size() == 0) {
                log.debug("DaData Clean вернул пустой массив");
                return Optional.empty();
            }
            JsonNode first = root.get(0);
            if (first == null || first.isNull()) return Optional.empty();

            String geoLatStr = getTextOrEmpty(first, "geo_lat");
            String geoLonStr = getTextOrEmpty(first, "geo_lon");
            if (geoLatStr.isEmpty() || geoLonStr.isEmpty()) {
                log.debug("DaData Clean: нет координат в ответе");
                return Optional.empty();
            }
            double lat = Double.parseDouble(geoLatStr);
            double lon = Double.parseDouble(geoLonStr);
            String resultAddress = getTextOrEmpty(first, "result");
            if (resultAddress.isEmpty()) resultAddress = fullAddress;
            String city = getTextOrEmpty(first, "city");
            String region = getTextOrEmpty(first, "region");

            // Только наш город: иначе DaData может вернуть, например, Копейск для "Цвиллинга 45"
            String ourCity = regionConfig.getCity();
            if (!city.isEmpty() && ourCity != null && !ourCity.isBlank()
                    && !city.trim().equalsIgnoreCase(ourCity.trim())) {
                log.warn("DaData Clean вернул другой город: {} (ожидаем {}), адрес {} — пропускаем, будет Suggest",
                        city, ourCity, fullAddress);
                return Optional.empty();
            }

            String qcGeo = getTextOrEmpty(first, "qc_geo");
            if (!qcGeo.isEmpty()) {
                try {
                    if (Integer.parseInt(qcGeo) >= 2) {
                        log.warn("DaData Clean: неточные координаты (qc_geo={}) для {}", qcGeo, fullAddress);
                    }
                } catch (NumberFormatException ignored) { }
            }
            log.debug("Геокодирование (Clean) успешно: lat={}, lon={}", lat, lon);
            return Optional.of(new GeocodingResult(lat, lon, resultAddress, city, region));
        } catch (Exception e) {
            log.debug("DaData Clean ошибка: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * API подсказок DaData (suggest/address) с ограничением по городу. Fallback при отключённом или неудачном Clean.
     */
    private Optional<GeocodingResult> geocodeWithSuggest(String fullAddress) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(DADATA_SUGGEST_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Token " + apiKey);
            conn.setDoOutput(true);

            Map<String, Object> body = new java.util.HashMap<>();
            body.put("query", fullAddress);
            body.put("count", 1);
            body.put("locations", List.of(Map.of("city", regionConfig.getCity())));
            String jsonQuery = objectMapper.writeValueAsString(body);
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(jsonQuery.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            String responseStr = readResponseBody(conn, responseCode);
            if (responseStr == null) {
                log.error("DaData Suggest: ошибка status={}", responseCode);
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(responseStr);
            JsonNode suggestions = root.get("suggestions");
            if (suggestions == null || !suggestions.isArray() || suggestions.size() == 0) {
                log.warn("DaData не нашёл адрес: {}", fullAddress);
                return Optional.empty();
            }
            JsonNode firstSuggestion = suggestions.get(0);
            JsonNode data = firstSuggestion.get("data");
            if (data == null) {
                log.warn("DaData вернул пустые данные для адреса: {}", fullAddress);
                return Optional.empty();
            }
            JsonNode geoLat = data.get("geo_lat");
            JsonNode geoLon = data.get("geo_lon");
            if (geoLat == null || geoLon == null || geoLat.isNull() || geoLon.isNull()
                    || geoLat.asText().isEmpty() || geoLon.asText().isEmpty()) {
                log.warn("DaData не нашёл координаты для адреса: {}", fullAddress);
                return Optional.empty();
            }
            double lat = Double.parseDouble(geoLat.asText());
            double lon = Double.parseDouble(geoLon.asText());
            String qcGeo = getTextOrEmpty(data, "qc_geo");
            if (!qcGeo.isEmpty()) {
                try {
                    if (Integer.parseInt(qcGeo) >= 2) {
                        log.warn("DaData вернул неточные координаты (qc_geo={}): {} — проверьте адрес.", qcGeo, fullAddress);
                    }
                } catch (NumberFormatException ignored) { }
            }
            String city = getTextOrEmpty(data, "city");
            String region = getTextOrEmpty(data, "region");
            String resultAddress = getTextOrEmpty(firstSuggestion, "value");
            log.debug("Геокодирование (Suggest) успешно: lat={}, lon={}", lat, lon);
            return Optional.of(new GeocodingResult(lat, lon, resultAddress, city, region));
        } catch (Exception e) {
            log.warn("Ошибка геокодирования (Suggest): {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String readResponseBody(java.net.HttpURLConnection conn, int responseCode) throws java.io.IOException {
        if (responseCode != 200) return null;
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
    
    /**
     * Очистить адрес для геокодирования — убрать подъезд и квартиру.
     * DaData не понимает "п2 кв 25", ему нужен только адрес до дома.
     * 
     * Примеры:
     * "Российская 59а п2 кв 25" → "Российская 59а"
     * "ул. Ленина 44, подъезд 2, кв. 15" → "ул. Ленина 44"
     */
    private String cleanAddressForGeocoding(String address) {
        String clean = address;
        
        // Убираем подъезд в разных форматах
        clean = clean.replaceAll("(?i)[,\\s]*(подъезд|подьезд|п\\.?|под\\.?)\\s*\\d+", "");
        
        // Убираем квартиру в разных форматах  
        clean = clean.replaceAll("(?i)[,\\s]*(квартира|кв\\.?|к\\.)\\s*\\d+", "");
        
        // Убираем лишние пробелы и запятые в конце
        clean = clean.replaceAll("[,\\s]+$", "").trim();
        
        log.debug("Адрес очищен: '{}' → '{}'", address, clean);
        return clean;
    }

    /**
     * Проверить что адрес в нужном регионе.
     */
    public boolean isInAllowedRegion(GeocodingResult result) {
        String allowedArea = regionConfig.getArea().toLowerCase();
        String resultRegion = result.region().toLowerCase();
        
        boolean allowed = resultRegion.contains(allowedArea.replace(" область", "")) ||
                          allowedArea.contains(resultRegion.replace(" область", ""));
        
        if (!allowed) {
            log.warn("Адрес не в разрешённом регионе: {} (ожидается {})", 
                    result.region(), regionConfig.getArea());
        }
        
        return allowed;
    }

    private String getTextOrEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value != null && !value.isNull()) ? value.asText() : "";
    }
}
