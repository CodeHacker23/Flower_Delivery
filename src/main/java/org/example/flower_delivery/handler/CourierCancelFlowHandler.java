package org.example.flower_delivery.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Courier;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.OrderStatus;
import org.example.flower_delivery.model.User;
import org.example.flower_delivery.service.CourierService;
import org.example.flower_delivery.service.MenuKeyboardService;
import org.example.flower_delivery.service.OrderService;
import org.example.flower_delivery.service.UserService;
import org.example.flower_delivery.telegram.TelegramSender;
import org.example.flower_delivery.util.RouteUrlBuilder;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Обработка отмены и возврата заказа курьером (рефакторинг, фаза 6).
 *
 * Состояние: выбор номера заказа для отмены, ввод причины. Вызывается из Bot.processTextUpdate
 * и из CallbackQueryHandler (кнопка «Отменить заказ», возврат по точке). См. docs/REFACTORING_STATUS.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourierCancelFlowHandler {

    private final TelegramSender telegramSender;
    private final CourierService courierService;
    private final OrderService orderService;
    private final UserService userService;
    private final MenuKeyboardService menuKeyboardService;

    private final Map<Long, List<UUID>> awaitingCancelSelection = new ConcurrentHashMap<>();
    private final Map<Long, PendingCancelReason> awaitingCancelReason = new ConcurrentHashMap<>();

    public record PendingCancelReason(UUID orderId, boolean isReturn) {}

    public boolean isAwaitingCancelSelection(Long telegramId) {
        return awaitingCancelSelection.containsKey(telegramId);
    }

    public boolean isAwaitingCancelReason(Long telegramId) {
        return awaitingCancelReason.containsKey(telegramId);
    }

    /**
     * Начать выбор номера заказа для отмены (вызывается из CallbackQueryHandler по кнопке «Отменить заказ»).
     */
    public void startCourierCancelSelection(Long telegramId, Long chatId) {
        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty()) return;
        List<Order> allOrders = orderService.getOrdersByCourierWithShop(courierOpt.get().getUser());
        List<UUID> activeIds = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        sb.append("⛔ *Отмена заказа*\n\n");
        sb.append("Выбери номер заказа для отмены:\n\n");
        int idx = 0;
        for (Order o : allOrders) {
            OrderStatus st = o.getStatus();
            if (st == OrderStatus.ACCEPTED || st == OrderStatus.IN_SHOP
                    || st == OrderStatus.PICKED_UP || st == OrderStatus.ON_WAY) {
                idx++;
                activeIds.add(o.getId());
                sb.append("*").append(idx).append(".* ")
                        .append(o.getRecipientName()).append(" — ")
                        .append(o.getDeliveryAddress()).append("\n");
            }
        }
        if (activeIds.isEmpty()) {
            sendSimple(chatId, "У тебя нет активных заказов для отмены.");
            return;
        }
        sb.append("\nВведи номер (1–").append(activeIds.size()).append(") или /cancel для выхода.");
        awaitingCancelSelection.put(telegramId, activeIds);
        sendSimple(chatId, sb.toString());
    }

    /**
     * Обработка ввода номера заказа для отмены. Возвращает true, если текст обработан (остаёмся в режиме отмены).
     */
    public boolean handleCancelSelectionText(Long telegramId, Long chatId, String text) {
        if ("📋 Доступные заказы".equals(text) || "🚚 Мои заказы".equals(text)
                || "💰 Моя статистика".equals(text) || "ℹ️ Информация".equals(text)
                || "/start".equals(text) || "/cancel".equalsIgnoreCase(text.trim())) {
            awaitingCancelSelection.remove(telegramId);
            return false;
        }

        List<UUID> ids = awaitingCancelSelection.get(telegramId);
        if (ids == null || ids.isEmpty()) {
            awaitingCancelSelection.remove(telegramId);
            return false;
        }

        int index;
        try {
            index = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            sendSimple(chatId, "❌ Введи число от 1 до " + ids.size());
            return true;
        }
        if (index < 1 || index > ids.size()) {
            sendSimple(chatId, "❌ Номер должен быть от 1 до " + ids.size());
            return true;
        }

        UUID orderId = ids.get(index - 1);
        awaitingCancelSelection.remove(telegramId);

        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty() || !Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
            sendSimple(chatId, "❌ Нет активного профиля курьера.");
            return true;
        }
        startAwaitingCancelReason(telegramId, chatId, orderId, false);
        return true;
    }

    /**
     * Начать ввод причины отмены или возврата (вызывается из Bot после выбора номера и из CallbackQueryHandler для возврата по точке).
     */
    public void startAwaitingCancelReason(Long telegramId, Long chatId, UUID orderId, boolean isReturn) {
        awaitingCancelReason.put(telegramId, new PendingCancelReason(orderId, isReturn));
        String prompt = isReturn
                ? "📝 *Укажи причину возврата* заказа в магазин (или /skip чтобы пропустить):\n\n_Причина передаётся в поддержку._"
                : "📝 *Укажи причину отмены* заказа (или /skip чтобы пропустить):\n\n_Причина передаётся в поддержку._";
        sendSimple(chatId, prompt);
    }

    /**
     * Обработка ввода причины отмены/возврата. Возвращает true, если текст обработан.
     */
    public boolean handleCancelReasonText(Long telegramId, Long chatId, String text) {
        if ("/start".equals(text) || "/cancel".equalsIgnoreCase(text.trim())) {
            awaitingCancelReason.remove(telegramId);
            sendSimple(chatId, "Отменено.");
            return true;
        }
        if ("📋 Доступные заказы".equals(text) || "🚚 Мои заказы".equals(text)
                || "💰 Моя статистика".equals(text) || "ℹ️ Информация".equals(text)) {
            awaitingCancelReason.remove(telegramId);
            return false;
        }

        PendingCancelReason pending = awaitingCancelReason.get(telegramId);
        if (pending == null) return false;

        awaitingCancelReason.remove(telegramId);

        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty() || !Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
            sendSimple(chatId, "❌ Нет активного профиля курьера.");
            return true;
        }
        Courier courier = courierOpt.get();

        String reason = "/skip".equalsIgnoreCase(text.trim()) ? null : text.trim();
        if (reason != null && reason.isEmpty()) reason = null;

        OrderService.CancelResult result = pending.isReturn()
                ? orderService.returnOrderToShopByCourier(pending.orderId(), courier.getUser(), reason)
                : orderService.cancelOrderByCourier(pending.orderId(), courier.getUser(), reason);

        UUID orderId = pending.orderId();
        if (result.success()) {
            String msg;
            if (pending.isReturn()) {
                msg = result.penaltyApplied()
                        ? "⚠️ *Заказ возвращён в магазин.*\n\n" + (result.penaltyReason() != null ? result.penaltyReason() + "\n\n" : "") + "Подробности в «💰 Моя статистика»."
                        : "✅ Заказ возвращён в магазин.\n\nНажми «🚚 Мои заказы» чтобы обновить список.";
            } else {
                msg = result.penaltyApplied()
                        ? "⚠️ *Заказ отменён.*\n\n" + (result.penaltyReason() != null ? result.penaltyReason() + "\n\n" : "") + "Подробности в «💰 Моя статистика»."
                        : "✅ Заказ отменён.\n\nНажми «🚚 Мои заказы» чтобы обновить список.";
            }
            sendCourierMenuText(chatId, msg);
            orderService.getOrderForShopPickupMessage(orderId).ifPresent(order -> {
                var shop = order.getShop();
                if (shop != null && shop.getUser() != null && shop.getUser().getTelegramId() != null) {
                    Long shopChatId = shop.getUser().getTelegramId();
                    String action = pending.isReturn() ? "возвращён" : "отменён";
                    String shopMsg = "⚠️ *Заказ " + action + " курьером*\n\n"
                            + "Получатель: " + order.getRecipientName() + "\n"
                            + "Адрес: " + order.getDeliveryAddress() + "\n\n"
                            + "Курьер: " + courier.getFullName();
                    if (order.getCourierCancelReason() != null && !order.getCourierCancelReason().isBlank()) {
                        shopMsg += "\n\n📝 *Причина:* " + order.getCourierCancelReason();
                    }
                    sendSimple(shopChatId, shopMsg);
                }
            });
            if (result.notifyAdmin()) {
                String reasonForAdmin = orderService.getOrderForShopPickupMessage(orderId)
                        .map(Order::getCourierCancelReason)
                        .orElse(null);
                notifyAdminsSuspiciousCancel(telegramId, orderId, pending.isReturn(), reasonForAdmin);
            }
            if (pending.isReturn()) {
                orderService.getOrderForShopPickupMessage(orderId).ifPresent(order -> {
                    var allForCourier = orderService.getOrdersByCourierWithShop(courier.getUser());
                    boolean hasOtherActive = allForCourier.stream().anyMatch(o ->
                            !o.getId().equals(order.getId()) &&
                                    (o.getStatus() == OrderStatus.ACCEPTED
                                            || o.getStatus() == OrderStatus.IN_SHOP
                                            || o.getStatus() == OrderStatus.PICKED_UP
                                            || o.getStatus() == OrderStatus.ON_WAY));
                    if (!hasOtherActive) {
                        sendReturnToShopRoute(courier, order);
                    }
                });
            }
        } else {
            sendSimple(chatId, pending.isReturn()
                    ? "❌ Не удалось вернуть заказ. Возможно, он уже завершён."
                    : "❌ Не удалось отменить заказ. Возможно, он уже завершён.");
        }
        return true;
    }

    private void sendCourierMenuText(Long chatId, String text) {
        menuKeyboardService.sendCourierMenu(chatId, text);
    }

    private void sendReturnToShopRoute(Courier courier, Order order) {
        if (courier == null || order == null) return;
        Long chatId = courier.getUser() != null ? courier.getUser().getTelegramId() : null;
        if (chatId == null) return;
        if (courier.getLastLatitude() == null || courier.getLastLongitude() == null) return;
        if (order.getEffectivePickupLatitude() == null || order.getEffectivePickupLongitude() == null) return;

        double fromLat = courier.getLastLatitude().doubleValue();
        double fromLon = courier.getLastLongitude().doubleValue();
        double toLat = order.getEffectivePickupLatitude().doubleValue();
        double toLon = order.getEffectivePickupLongitude().doubleValue();

        String yandexUrl = RouteUrlBuilder.buildYandexRouteUrl(fromLat, fromLon, toLat, toLon);
        String twoGisUrl = RouteUrlBuilder.build2GisRouteUrl(fromLat, fromLon, toLat, toLon);

        InlineKeyboardButton yandexBtn = InlineKeyboardButton.builder().text("🗺 Яндекс.Карты").url(yandexUrl).build();
        InlineKeyboardButton twoGisBtn = InlineKeyboardButton.builder().text("🗺 2ГИС").url(twoGisUrl).build();
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(List.of(List.of(yandexBtn, twoGisBtn)));

        String text = "↩️ *Заказ помечен как возвращён в магазин.*\n\n"
                + "Если ты ещё с букетом — вот маршрут обратно в магазин.";
        telegramSender.sendMessage(chatId, text, "Markdown", kb);
    }

    private void notifyAdminsSuspiciousCancel(Long courierTelegramId, UUID orderId, boolean isReturn, String cancelReason) {
        List<User> admins = userService.findActiveAdmins();
        if (admins.isEmpty()) {
            log.warn("Нет активных админов для уведомления о подозрительной отмене: orderId={}, courierTelegramId={}",
                    orderId, courierTelegramId);
            return;
        }
        String action = isReturn ? "возвратил в магазин" : "отменил";
        String text = "⚠️ *Звоночек: подозрительная отмена*\n\n"
                + "Курьер telegramId: `" + courierTelegramId + "`\n"
                + "Заказ: `" + orderId + "`\n"
                + "Действие: " + action + "\n\n"
                + "Гео: курьер был в магазине, но не у получателя.\n";
        if (cancelReason != null && !cancelReason.isBlank()) {
            text += "\n📝 *Причина от курьера:* " + cancelReason + "\n";
        }
        text += "\nРазобрать и при необходимости назначить штраф.";
        for (User admin : admins) {
            Long adminChatId = admin.getTelegramId();
            if (adminChatId == null) continue;
            telegramSender.sendMessage(adminChatId, text, "Markdown", null);
        }
    }

    private void sendSimple(Long chatId, String text) {
        telegramSender.sendMessage(chatId, text, "Markdown", null);
    }
}
