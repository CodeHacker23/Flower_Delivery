package org.example.flower_delivery.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.OrderStatus;
import org.example.flower_delivery.model.User;
import org.example.flower_delivery.telegram.TelegramSender;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Уведомления активных администраторов (проблемы с гео, подозрительные отмены и т.д.).
 * Фаза 7 рефакторинга — вынесено из Bot (см. docs/REFACTORING_STATUS.md).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminNotificationService {

    private final UserService userService;
    private final TelegramSender telegramSender;

    /**
     * Уведомить всех активных администраторов о проблеме с геолокацией курьера
     * (например, 3 неудачные попытки подтвердить «В магазине» / «Вручил»).
     */
    public void notifyAdminsAboutCourierGeoIssue(Long courierTelegramId,
                                                 UUID orderId,
                                                 OrderStatus nextStatus,
                                                 double latitude,
                                                 double longitude,
                                                 int attempts) {
        List<User> admins = userService.findActiveAdmins();
        if (admins.isEmpty()) {
            log.warn("Нет активных админов для уведомления о проблеме гео: orderId={}, courierTelegramId={}",
                    orderId, courierTelegramId);
            return;
        }
        String text = "⚠️ *Проблема с подтверждением геолокации курьера*\n\n"
                + "Курьер telegramId: `" + courierTelegramId + "`\n"
                + "Заказ: `" + orderId + "`\n"
                + "Статус для подтверждения: *" + nextStatus.getDisplayName() + "*\n"
                + "Попыток: " + attempts + "\n"
                + "Последняя точка: `" + latitude + ", " + longitude + "`\n\n"
                + "Нужно вручную проверить ситуацию и при необходимости скорректировать статус/штрафы.";
        for (User admin : admins) {
            Long chatId = admin.getTelegramId();
            if (chatId == null) continue;
            telegramSender.sendMessage(chatId, text, "Markdown", null);
        }
    }
}
