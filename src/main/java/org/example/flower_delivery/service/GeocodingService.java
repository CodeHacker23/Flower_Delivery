package org.example.flower_delivery.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.config.RegionConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Сервис геокодирования через DaData API (Подсказки).
 * <p>
 * Что делает:
 * 1. Принимает адрес текстом: "ул. Ленина 44"
 * 2. Добавляет город из конфига: "Челябинск, ул. Ленина 44"
 * 3. Отправляет в DaData → получает координаты [lat, lon]
 * <p>
 * DaData бесплатно: 10 000 запросов/день
 * Используем API подсказок (suggestions) — требует только API-ключ!
 * Документация: https://dadata.ru/api/suggest/address/
 */
@Slf4j
@Service
public class GeocodingService {

    // API подсказок — работает только с API-ключом (без секретного)
    private static final String DADATA_URL = "https://suggestions.dadata.ru/suggestions/api/4_1/rs/suggest/address";
    
    private final RegionConfig regionConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;

    @Value("${dadata.api-key}")
    private String apiKey;
    
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
     *
     * @param address Адрес от пользователя (например: "ул. Ленина 44, кв. 15")
     * @return Optional с координатами, или empty если не удалось
     */
    public Optional<GeocodingResult> geocode(String address) {
        try {
            // Убираем подъезд и квартиру — DaData их не понимает
            String cleanAddress = cleanAddressForGeocoding(address);
            
            // Добавляем город к адресу
            String fullAddress = regionConfig.enrichAddress(cleanAddress);
            log.info("=== DADATA GEOCODING ===");
            log.info("Адрес для геокодирования: {}", fullAddress);
            log.info("API Key (первые 10 символов): {}...", apiKey.substring(0, Math.min(10, apiKey.length())));

            // Формируем запрос к DaData (API подсказок)
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
            headers.setAcceptCharset(java.util.List.of(StandardCharsets.UTF_8));
            headers.set("Authorization", "Token " + apiKey);
            // API подсказок НЕ требует X-Secret!

            // Используем HttpURLConnection для полного контроля над кодировкой
            log.info("DaData URL: {}", DADATA_URL);
            
            java.net.URL url = new java.net.URL(DADATA_URL);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Token " + apiKey);
            conn.setDoOutput(true);
            
            // Формируем JSON и отправляем как UTF-8 bytes
            String jsonQuery = "{\"query\":\"" + fullAddress.replace("\"", "\\\"") + "\",\"count\":1}";
            byte[] jsonBytes = jsonQuery.getBytes(StandardCharsets.UTF_8);
            
            log.info("DaData request (UTF-8 bytes length): {}", jsonBytes.length);
            log.info("DaData request JSON: {}", jsonQuery);
            
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(jsonBytes);
                os.flush();
            }
            
            int responseCode = conn.getResponseCode();
            log.info("DaData response code: {}", responseCode);
            
            // Читаем ответ
            StringBuilder responseBody = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    responseBody.append(line);
                }
            }
            
            String responseStr = responseBody.toString();
            log.info("DaData response body: {}", responseStr);

            if (responseCode != 200 || responseStr.isEmpty()) {
                log.error("DaData вернул ошибку: status={}", responseCode);
                return Optional.empty();
            }

            // Парсим ответ API подсказок
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
            
            // Проверяем что координаты есть
            JsonNode geoLat = data.get("geo_lat");
            JsonNode geoLon = data.get("geo_lon");
            
            if (geoLat == null || geoLon == null || 
                geoLat.isNull() || geoLon.isNull() ||
                geoLat.asText().isEmpty() || geoLon.asText().isEmpty()) {
                log.warn("DaData не нашёл координаты для адреса: {}", fullAddress);
                return Optional.empty();
            }

            double lat = Double.parseDouble(geoLat.asText());
            double lon = Double.parseDouble(geoLon.asText());
            
            // Извлекаем город и область для валидации
            String city = getTextOrEmpty(data, "city");
            String region = getTextOrEmpty(data, "region");
            String resultAddress = getTextOrEmpty(firstSuggestion, "value");

            log.info("Геокодирование успешно: lat={}, lon={}, city={}, region={}", 
                    lat, lon, city, region);

            return Optional.of(new GeocodingResult(lat, lon, resultAddress, city, region));

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("=== DADATA HTTP ERROR ===");
            log.error("Status: {}", e.getStatusCode());
            log.error("Response: {}", e.getResponseBodyAsString());
            log.error("Message: {}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.error("=== DADATA EXCEPTION ===");
            log.error("Тип ошибки: {}", e.getClass().getName());
            log.error("Сообщение: {}", e.getMessage());
            log.error("Stack trace:", e);
            return Optional.empty();
        }
    }
    
    /**
     * Экранировать строку для JSON.
     */
    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
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
        
        log.info("Адрес очищен: '{}' → '{}'", address, clean);
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
