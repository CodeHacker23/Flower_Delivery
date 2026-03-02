package org.example.flower_delivery.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class YooKassaPaymentService {

    @Value("${yookassa.shop-id:}")
    private String shopId;

    @Value("${yookassa.secret-key:}")
    private String secretKey;

    @Value("${yookassa.return-url:https://t.me/}")
    private String returnUrlBase;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Создать платёж в ЮKassa для пополнения депозита курьера.
     *
     * @param telegramId telegramId курьера (кладём в metadata)
     * @param amount     сумма пополнения
     * @return confirmation_url для перехода к оплате или null при ошибке
     */
    public String createCourierDepositPayment(Long telegramId, BigDecimal amount) {
        if (shopId == null || shopId.isBlank() || secretKey == null || secretKey.isBlank()) {
            log.error("ЮKassa не сконфигурирована: yookassa.shop-id / yookassa.secret-key пустые");
            return null;
        }
        try {
            log.info("Запрос создания платежа ЮKassa: telegramId={}, amount={}", telegramId, amount);
            String url = "https://api.yookassa.ru/v3/payments";

            Map<String, Object> body = new HashMap<>();
            Map<String, Object> amountMap = new HashMap<>();
            amountMap.put("value", amount.setScale(2, BigDecimal.ROUND_HALF_UP).toString());
            amountMap.put("currency", "RUB");
            body.put("amount", amountMap);

            // Способ оплаты — СБП (Система быстрых платежей)
            Map<String, Object> method = new HashMap<>();
            method.put("type", "sbp");
            body.put("payment_method_data", method);

            Map<String, Object> confirmation = new HashMap<>();
            confirmation.put("type", "redirect");
            confirmation.put("return_url", returnUrlBase);
            body.put("confirmation", confirmation);

            body.put("description", "Пополнение депозита курьера " + telegramId);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("telegram_id", telegramId);
            metadata.put("type", "courier_deposit");
            body.put("metadata", metadata);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Idempotence-Key", UUID.randomUUID().toString());
            String authRaw = shopId + ":" + secretKey;
            String auth = java.util.Base64.getEncoder().encodeToString(authRaw.getBytes(StandardCharsets.UTF_8));
            headers.set("Authorization", "Basic " + auth);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("ЮKassa вернула ошибку: status={}, body={}", response.getStatusCode(), response.getBody());
                return null;
            }
 // проерка ветки рефакторинг просто что бы она на гите появилась
            Object confirmationObj = response.getBody().get("confirmation");
            if (confirmationObj instanceof Map<?, ?> confirmationMap) {
                Object urlObj = confirmationMap.get("confirmation_url");
                if (urlObj != null) {
                    String resultUrl = urlObj.toString();
                    log.info("✅ Платёж ЮKassa успешно создан: telegramId={}, amount={}, confirmation_url={}",
                            telegramId, amount, resultUrl);
                    return resultUrl;
                }
            }
            log.error("Не удалось получить confirmation_url из ответа ЮKassa: {}", response.getBody());
            return null;
        } catch (Exception e) {
            log.error("Ошибка при обращении к ЮKassa", e);
            return null;
        }
    }
}

