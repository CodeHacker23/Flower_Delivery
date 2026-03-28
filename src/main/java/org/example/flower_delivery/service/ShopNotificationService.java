package org.example.flower_delivery.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.telegram.TelegramSender;
import org.example.flower_delivery.util.TextFormattingUtil;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.List;

/**
 * Уведомления магазину (запрос «Курьер забрал заказ?» и т.д.).
 * Вынесено из Bot, см. docs/REFACTORING_STATUS.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopNotificationService {

    private final TelegramSender telegramSender;

    /**
     * Отправить магазину запрос: «Курьер [имя] уехал с заказом … Вы передали ему заказ? ДА ✅ / Нет ❌».
     * Вызывать после перевода заказа в «В путь». Order должен быть загружен с shop, shop.user, courier.
     */
    public void sendShopPickupConfirmationRequest(Order order) {
        if (order == null || order.getShop() == null || order.getShop().getUser() == null) {
            log.warn("Не удалось отправить запрос магазину: заказ или магазин/пользователь отсутствуют");
            return;
        }
        Long shopChatId = order.getShop().getUser().getTelegramId();
        if (shopChatId == null) {
            log.warn("У магазина заказа {} нет telegram_id", order.getId());
            return;
        }
        String courierName = order.getCourier() != null && order.getCourier().getFullName() != null
                ? order.getCourier().getFullName() : "Курьер";
        String recipient = order.getRecipientName() != null ? order.getRecipientName() : "";
        String address = order.getDeliveryAddress() != null ? order.getDeliveryAddress() : "";
        String text = "📦 *Курьер забрал заказ?*\n\n"
                + "Курьер *" + TextFormattingUtil.escapeMarkdown(courierName) + "* уехал с заказом:\n"
                + "• " + TextFormattingUtil.escapeMarkdown(recipient) + "\n"
                + "• " + TextFormattingUtil.escapeMarkdown(address) + "\n\n"
                + "Вы передали ему заказ?";
        InlineKeyboardButton btnYes = InlineKeyboardButton.builder()
                .text("ДА ✅")
                .callbackData("shop_pickup_confirm:" + order.getId() + ":yes")
                .build();
        InlineKeyboardButton btnNo = InlineKeyboardButton.builder()
                .text("Нет ❌")
                .callbackData("shop_pickup_confirm:" + order.getId() + ":no")
                .build();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(List.of(btnYes, btnNo)));
        telegramSender.sendMessage(shopChatId, text, "Markdown", markup);
        log.info("Запрос «Курьер забрал?» отправлен магазину: orderId={}, shopChatId={}", order.getId(), shopChatId);
    }
}
