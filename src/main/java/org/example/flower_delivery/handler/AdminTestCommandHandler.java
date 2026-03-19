package org.example.flower_delivery.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.service.CourierService;
import org.example.flower_delivery.service.MenuKeyboardService;
import org.example.flower_delivery.service.ShopService;
import org.example.flower_delivery.telegram.TelegramSender;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Тестовые команды для активации магазина и курьера без админки.
 * /r — активировать магазин текущего пользователя.
 * /k — активировать курьера текущего пользователя.
 * В продакшене активацию делает админ. См. docs/REFACTORING_STATUS.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminTestCommandHandler {

    private final ShopService shopService;
    private final CourierService courierService;
    private final MenuKeyboardService menuKeyboardService;
    private final TelegramSender telegramSender;

    public void handleActivateCommand(Update update) {
        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();
        var shopOptional = shopService.findByUserTelegramId(telegramId);
        if (shopOptional.isEmpty()) {
            sendSimpleMessage(chatId, "❌ У тебя нет магазина для активации.");
            return;
        }
        Shop shop = shopOptional.get();
        if (Boolean.TRUE.equals(shop.getIsActive())) {
            menuKeyboardService.sendShopMenu(chatId, shop, "✅ Твой магазин уже активен!");
            return;
        }
        shop.setIsActive(true);
        shopService.save(shop);
        log.info("Магазин активирован (тестовая команда): shopId={}, telegramId={}", shop.getId(), telegramId);
        menuKeyboardService.sendShopMenu(chatId, shop, "✅ *Магазин активирован!*\n\nТеперь ты можешь создавать заказы.");
    }

    public void handleActivateCourierCommand(Update update) {
        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();
        var courierOptional = courierService.findByTelegramId(telegramId);
        if (courierOptional.isEmpty()) {
            sendSimpleMessage(chatId, "❌ У тебя ещё нет регистрации курьера.\nСначала выбери роль *Курьер* через /start.");
            return;
        }
        var courier = courierOptional.get();
        if (Boolean.TRUE.equals(courier.getIsActive())) {
            menuKeyboardService.sendCourierMenu(chatId, "✅ Твой профиль курьера уже активирован.\n\nМожешь смотреть доступные заказы и свою статистику.");
            return;
        }
        courierService.activateCourier(courier);
        menuKeyboardService.sendCourierMenu(chatId, "✅ *Профиль курьера активирован!*\n\nТеперь ты можешь выбирать заказы и работать курьером.");
    }

    private void sendSimpleMessage(Long chatId, String text) {
        telegramSender.sendMessage(chatId, text, "Markdown", null);
    }
}
