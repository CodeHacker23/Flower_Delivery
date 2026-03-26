package org.example.flower_delivery.handler.callback;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Role;
import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.service.CourierService;
import org.example.flower_delivery.service.MenuKeyboardService;
import org.example.flower_delivery.service.ShopService;
import org.example.flower_delivery.service.UserService;
import org.springframework.stereotype.Component;
import org.example.flower_delivery.handler.CourierRegistrationHandler;
import org.example.flower_delivery.handler.ShopRegistrationHandler;
import org.example.flower_delivery.handler.OrderCreationHandler;

/**
 * Обработка callback: выбор роли (role_*), создание заказа (create_order), информация о магазине (shop_info).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoleCallbackHandler {

    private final UserService userService;
    private final ShopService shopService;
    private final CourierService courierService;
    private final MenuKeyboardService menuKeyboardService;
    private final ShopRegistrationHandler shopRegistrationHandler;
    private final OrderCreationHandler orderCreationHandler;
    private final CourierRegistrationHandler courierRegistrationHandler;

    public boolean handles(String callbackData) {
        return callbackData != null && (
                callbackData.startsWith("role_") ||
                "create_order".equals(callbackData) ||
                "shop_info".equals(callbackData)
        );
    }

    public void handle(CallbackQueryContext ctx) {
        String data = ctx.callbackData();
        CallbackQueryResponder r = ctx.responder();

        if (data.startsWith("role_")) {
            r.answerCallbackQuery(ctx.callbackQueryId(), "✅ Роль выбрана!");
            handleRoleSelection(ctx);
        } else if ("create_order".equals(data)) {
            r.answerCallbackQuery(ctx.callbackQueryId(), "📦 Создаём заказ...");
            orderCreationHandler.startOrderCreation(ctx.telegramId(), ctx.chatId());
        } else if ("shop_info".equals(data)) {
            r.answerCallbackQuery(ctx.callbackQueryId(), "🏪 Информация о магазине");
            handleShopInfo(ctx);
        }
    }

    private void handleRoleSelection(CallbackQueryContext ctx) {
        String callbackData = ctx.callbackData();
        Role selectedRole;
        if ("role_shop".equals(callbackData)) {
            selectedRole = Role.SHOP;
        } else if ("role_courier".equals(callbackData)) {
            selectedRole = Role.COURIER;
        } else {
            log.warn("Неизвестная роль в callback_data: {}", callbackData);
            ctx.responder().sendMessage(ctx.chatId(), "❌ Неизвестная роль. Попробуй еще раз.", "Markdown", null);
            return;
        }

        try {
            userService.updateUserRole(ctx.telegramId(), selectedRole);
            log.info("Роль успешно обновлена: telegramId={}, role={}", ctx.telegramId(), selectedRole);

            if (selectedRole == Role.SHOP) {
                ctx.responder().sendMessage(ctx.chatId(), "✅ Отлично! Ты выбрал роль: *Магазин*\n\n" +
                        "Теперь давай заполним информацию о твоём магазине.", "Markdown", null);
                shopRegistrationHandler.startRegistrationFromCallback(ctx.telegramId(), ctx.chatId());
            } else if (selectedRole == Role.COURIER) {
                var courierOpt = courierService.findByTelegramId(ctx.telegramId());
                log.info("Выбор роли курьера: telegramId={}, courierExists={}",
                        ctx.telegramId(), courierOpt.isPresent());
                if (courierOpt.isPresent()) {
                    if (Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
                        log.info("Курьер уже активен: telegramId={}, courierId={}",
                                ctx.telegramId(), courierOpt.get().getId());
                        ctx.responder().sendMessage(ctx.chatId(), "✅ Ты уже зарегистрирован как курьер.\n\n" +
                                "Вот твоё меню курьера ниже.", "Markdown", null);
                        menuKeyboardService.sendCourierMenu(ctx.chatId(), "🚴 *Меню курьера*");
                    } else {
                        log.info("Курьер уже создан, но не активен: telegramId={}, courierId={}",
                                ctx.telegramId(), courierOpt.get().getId());
                        ctx.responder().sendMessage(ctx.chatId(), "⏳ Ты уже зарегистрирован как курьер.\n\n" +
                                "Профиль ждёт активации администратором. После активации ты сможешь брать заказы.", "Markdown", null);
                    }
                } else {
                    ctx.responder().sendMessage(ctx.chatId(), "✅ Отлично! Ты выбрал роль: *Курьер*.\n\n" +
                            "Сейчас зарегистрируем тебя как курьера.\n" +
                            "Сначала напиши своё имя и фамилию.", "Markdown", null);
                    log.info("Запускаем сценарий регистрации курьера: telegramId={}", ctx.telegramId());
                    courierRegistrationHandler.startRegistrationFromCallback(ctx.telegramId(), ctx.chatId(), null);
                }
            }
        } catch (IllegalArgumentException e) {
            log.error("Пользователь не найден при обновлении роли: telegramId={}", ctx.telegramId());
            ctx.responder().sendMessage(ctx.chatId(), "❌ Ошибка: пользователь не найден. Попробуй /start", "Markdown", null);
        } catch (Exception e) {
            log.error("Ошибка при обновлении роли: telegramId={}, role={}", ctx.telegramId(), selectedRole, e);
            ctx.responder().sendMessage(ctx.chatId(), "❌ Произошла ошибка при сохранении роли. Попробуй позже.", "Markdown", null);
        }
    }

    private void handleShopInfo(CallbackQueryContext ctx) {
        var shopOptional = shopService.findByUserTelegramId(ctx.telegramId());
        if (shopOptional.isEmpty()) {
            ctx.responder().sendMessage(ctx.chatId(), "❌ У тебя нет зарегистрированного магазина.", "Markdown", null);
            return;
        }
        Shop shop = shopOptional.get();
        String status = Boolean.TRUE.equals(shop.getIsActive()) ? "✅ Активен" : "⏳ Ожидает активации";
        ctx.responder().sendMessage(ctx.chatId(), "🏪 *Мой магазин*\n\n" +
                "📋 *Информация:*\n" +
                "• Название: " + shop.getShopName() + "\n" +
                "• Адрес забора: " + shop.getPickupAddress() + "\n" +
                "• Телефон: " + shop.getPhone() + "\n" +
                "• Статус: " + status + "\n\n" +
                "📅 Зарегистрирован: " + shop.getCreatedAt().toLocalDate(), "Markdown", null);
    }
}
