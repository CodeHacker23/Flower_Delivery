package org.example.flower_delivery.handler.callback;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.OrderStatus;
import org.example.flower_delivery.model.OrderStop;
import org.example.flower_delivery.service.CourierService;
import org.example.flower_delivery.service.OrderBundleService;
import org.example.flower_delivery.service.OrderService;
import org.example.flower_delivery.service.ShopNotificationService;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.example.flower_delivery.handler.CourierAvailableOrdersHandler;
import org.example.flower_delivery.handler.CourierCancelFlowHandler;
import org.example.flower_delivery.handler.CourierDepositHandler;
import org.example.flower_delivery.handler.CourierGeoHandler;
import org.example.flower_delivery.handler.CourierMenuHandler;
import org.example.flower_delivery.handler.CourierStatsHandler;

/**
 * Обработка callback курьера: просмотр/взятие заказа, смена статуса, отмена/возврат, связки, депозит, статистика.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourierCallbackHandler {

    private final CourierService courierService;
    private final OrderService orderService;
    private final OrderBundleService orderBundleService;
    private final CourierAvailableOrdersHandler courierAvailableOrdersHandler;
    private final CourierDepositHandler courierDepositHandler;
    private final CourierGeoHandler courierGeoHandler;
    private final CourierMenuHandler courierMenuHandler;
    private final CourierStatsHandler courierStatsHandler;
    private final CourierCancelFlowHandler courierCancelFlowHandler;
    private final ShopNotificationService shopNotificationService;

    public boolean handles(String callbackData) {
        return callbackData != null && callbackData.startsWith("courier_");
    }

    public void handle(CallbackQueryContext ctx) {
        String data = ctx.callbackData();
        CallbackQueryResponder r = ctx.responder();

        if (data.startsWith("courier_order_view:")) {
            String orderIdStr = data.replace("courier_order_view:", "");
            r.answerCallbackQuery(ctx.callbackQueryId(), "📋 Заказ");
            handleCourierOrderView(ctx, orderIdStr);
        } else if (data.startsWith("courier_order_take:")) {
            String orderIdStr = data.replace("courier_order_take:", "");
            r.answerCallbackQuery(ctx.callbackQueryId(), "✅ Назначаю заказ...");
            handleCourierOrderTake(ctx, orderIdStr);
        } else if (data.startsWith("courier_phone:")) {
            r.answerCallbackQuery(ctx.callbackQueryId(), "📞 Телефон");
            handleCourierPhoneRequest(ctx, data);
        } else if (data.startsWith("courier_orders_page:")) {
            String pageStr = data.replace("courier_orders_page:", "");
            int page = parseIntSafe(pageStr, 0);
            courierAvailableOrdersHandler.showAvailableOrdersPage(ctx.telegramId(), ctx.chatId(), page, ctx.messageId());
            r.answerCallbackQuery(ctx.callbackQueryId(), "Страница " + (page + 1));
        } else if (data.startsWith("courier_order_next:")) {
            String orderIdStr = data.replace("courier_order_next:", "");
            handleCourierOrderNextStatus(ctx, orderIdStr);
        } else if (data.startsWith("courier_stop_delivered:")) {
            String rest = data.replace("courier_stop_delivered:", "");
            handleCourierStopDelivered(ctx, rest);
        } else if ("courier_deposit_topup".equals(data)) {
            r.answerCallbackQuery(ctx.callbackQueryId(), "💳 Пополнение депозита");
            courierDepositHandler.startTopUp(ctx.telegramId(), ctx.chatId());
        } else if (data.startsWith("courier_tx_page:")) {
            String offsetStr = data.replace("courier_tx_page:", "");
            int offset = parseIntSafe(offsetStr, 0);
            courierStatsHandler.editCourierStatsMessage(ctx.chatId(), ctx.messageId(), ctx.telegramId(), offset);
            r.answerCallbackQuery(ctx.callbackQueryId(), "Обновляю операции...");
        } else if ("courier_cancel_select".equals(data)) {
            r.answerCallbackQuery(ctx.callbackQueryId(), "⛔ Выбери заказ для отмены");
            courierCancelFlowHandler.startCourierCancelSelection(ctx.telegramId(), ctx.chatId());
        } else if (data.startsWith("courier_order_cancel_ask:")) {
            String orderIdStr = data.replace("courier_order_cancel_ask:", "");
            r.answerCallbackQuery(ctx.callbackQueryId(), "❓ Отменить заказ?");
            handleCourierOrderCancelAsk(ctx, orderIdStr);
        } else if (data.startsWith("courier_order_cancel_ok_")) {
            String orderIdStr = data.replace("courier_order_cancel_ok_", "");
            handleCourierOrderCancelConfirm(ctx, orderIdStr, false);
        } else if ("courier_order_cancel_no".equals(data)) {
            r.answerCallbackQuery(ctx.callbackQueryId(), "Ок, не отменяем");
        } else if (data.startsWith("courier_order_return_ask:")) {
            String orderIdStr = data.replace("courier_order_return_ask:", "");
            r.answerCallbackQuery(ctx.callbackQueryId(), "❓ Вернуть заказ в магазин?");
            handleCourierOrderReturnAsk(ctx, orderIdStr);
        } else if (data.startsWith("courier_order_return_ok_")) {
            String orderIdStr = data.replace("courier_order_return_ok_", "");
            handleCourierOrderCancelConfirm(ctx, orderIdStr, true);
        } else if ("courier_order_return_no".equals(data)) {
            r.answerCallbackQuery(ctx.callbackQueryId(), "Ок, не возвращаем");
        } else if (data.startsWith("courier_bundle_take:")) {
            String indicesStr = data.replace("courier_bundle_take:", "");
            r.answerCallbackQuery(ctx.callbackQueryId(), "📦 Назначаю связку...");
            handleCourierBundleTake(ctx, indicesStr);
        }
    }

    private static int parseIntSafe(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private void handleCourierOrderView(CallbackQueryContext ctx, String orderIdStr) {
        UUID orderId = uuidOrNull(orderIdStr);
        if (orderId == null) {
            ctx.responder().sendMessage(ctx.chatId(), "❌ Неверный ID заказа.", "Markdown", null);
            return;
        }
        var orderOpt = orderService.getOrderWithShop(orderId);
        if (orderOpt.isEmpty()) {
            ctx.responder().sendMessage(ctx.chatId(), "❌ Заказ не найден.", "Markdown", null);
            return;
        }
        Order order = orderOpt.get();
        if (order.getStatus() != OrderStatus.NEW) {
            ctx.responder().sendMessage(ctx.chatId(), "❌ Этот заказ уже взят другим курьером.", "Markdown", null);
            return;
        }
        String shopName = order.getShop() != null && order.getShop().getShopName() != null ? order.getShop().getShopName() : "—";
        String timeRange = order.getDeliveryInterval() != null ? order.getDeliveryInterval().getTimeRange() : "—";
        StringBuilder sb = new StringBuilder();
        sb.append("*Заказ ").append(shopName).append("*\n\n");
        sb.append("👤 *Получатель:* ").append(order.getRecipientName()).append(" (").append(order.getRecipientPhone()).append(")\n");
        sb.append("📞 *Клиент:* ").append(order.getRecipientPhone()).append("\n");
        sb.append("📍 *Адрес:* ").append(order.getDeliveryAddress()).append("\n");
        sb.append("⏰ *Период доставки:* ").append(timeRange).append("\n");
        sb.append("💬 *Комментарий:* ").append(order.getComment() != null ? order.getComment() : "—").append("\n");
        sb.append("💰 *Стоимость доставки:* ").append(order.getDeliveryPrice()).append(" руб.");
        List<InlineKeyboardButton> row1 = List.of(
                InlineKeyboardButton.builder().text("📞 Получатель").callbackData("courier_phone:" + orderId + ":recipient").build(),
                InlineKeyboardButton.builder().text("📞 Заказчик").callbackData("courier_phone:" + orderId + ":client").build()
        );
        List<InlineKeyboardButton> row2 = List.of(InlineKeyboardButton.builder().text("✅ Забрать заказ").callbackData("courier_order_take:" + orderId).build());
        ctx.responder().sendMessage(ctx.chatId(), sb.toString(), "Markdown", new InlineKeyboardMarkup(List.of(row1, row2)));
    }

    private void handleCourierPhoneRequest(CallbackQueryContext ctx, String callbackData) {
        String rest = callbackData.replace("courier_phone:", "");
        String[] parts = rest.split(":", 2);
        if (parts.length < 2) return;
        UUID orderId = uuidOrNull(parts[0]);
        if (orderId == null) return;
        var orderOpt = orderService.findById(orderId);
        if (orderOpt.isEmpty()) return;
        String phone = orderOpt.get().getRecipientPhone();
        String label = "recipient".equals(parts[1]) ? "Получатель" : "Заказчик";
        ctx.responder().sendMessage(ctx.chatId(), "📞 *" + label + ":* " + phone + "\n\n_Нажми на номер, чтобы позвонить._", "Markdown", null);
    }

    private void handleCourierOrderTake(CallbackQueryContext ctx, String orderIdStr) {
        UUID orderId = uuidOrNull(orderIdStr);
        if (orderId == null) {
            ctx.responder().sendMessage(ctx.chatId(), "❌ Неверный ID заказа.", "Markdown", null);
            return;
        }
        courierAvailableOrdersHandler.takeOrderById(ctx.telegramId(), ctx.chatId(), orderId);
    }

    private void handleCourierOrderCancelAsk(CallbackQueryContext ctx, String orderIdStr) {
        log.info("Курьер запросил подтверждение отмены заказа: orderIdStr={}", orderIdStr);
        String text = "❓ *Отменить этот заказ как курьер?*\n\nПосле отмены заказ уйдёт из твоего списка и вернётся в работу магазину/админу.";
        InlineKeyboardButton btnYes = InlineKeyboardButton.builder().text("Да, отменить заказ").callbackData("courier_order_cancel_ok_" + orderIdStr).build();
        InlineKeyboardButton btnNo = InlineKeyboardButton.builder().text("Нет").callbackData("courier_order_cancel_no").build();
        ctx.responder().sendMessage(ctx.chatId(), text, "Markdown", new InlineKeyboardMarkup(List.of(List.of(btnYes, btnNo))));
    }

    private void handleCourierOrderReturnAsk(CallbackQueryContext ctx, String orderIdStr) {
        String text = "❓ *Вернуть заказ в магазин?*";
        InlineKeyboardButton btnYes = InlineKeyboardButton.builder().text("Да, вернуть").callbackData("courier_order_return_ok_" + orderIdStr).build();
        InlineKeyboardButton btnNo = InlineKeyboardButton.builder().text("Нет").callbackData("courier_order_return_no").build();
        ctx.responder().sendMessage(ctx.chatId(), text, "Markdown", new InlineKeyboardMarkup(List.of(List.of(btnYes, btnNo))));
    }

    private void handleCourierOrderCancelConfirm(CallbackQueryContext ctx, String orderIdStr, boolean isReturn) {
        UUID orderId = uuidOrNull(orderIdStr);
        if (orderId == null) {
            log.warn("Курьер прислал некорректный ID заказа: telegramId={}, raw={}", ctx.telegramId(), orderIdStr);
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "❌ Неверный ID заказа");
            return;
        }
        var courierOpt = courierService.findByTelegramId(ctx.telegramId());
        if (courierOpt.isEmpty() || !Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "❌ Нет активного профиля курьера");
            return;
        }
        ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), isReturn ? "📝 Укажи причину возврата" : "📝 Укажи причину отмены");
        courierCancelFlowHandler.startAwaitingCancelReason(ctx.telegramId(), ctx.chatId(), orderId, isReturn);
    }

    private void handleCourierOrderNextStatus(CallbackQueryContext ctx, String orderIdStr) {
        UUID orderId = uuidOrNull(orderIdStr);
        if (orderId == null) {
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "❌ Неверный ID заказа");
            return;
        }
        var courierOpt = courierService.findByTelegramId(ctx.telegramId());
        if (courierOpt.isEmpty() || !Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "❌ Нет активного профиля курьера");
            return;
        }
        var orderOpt = orderService.findById(orderId);
        if (orderOpt.isEmpty()) {
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "❌ Заказ не найден");
            return;
        }
        OrderStatus current = orderOpt.get().getStatus();
        OrderStatus next = nextStatusForCourier(current);
        if (next == null) {
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "❌ Для этого заказа смена статуса недоступна");
            return;
        }
        if (next == OrderStatus.IN_SHOP || next == OrderStatus.DELIVERED) {
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "📍 Отправьте геолокацию");
            courierGeoHandler.requestLocationForStatus(ctx.telegramId(), ctx.chatId(), orderId, next, ctx.messageId());
            return;
        }
        boolean updated = orderService.updateOrderStatusByCourier(orderId, courierOpt.get().getUser(), next);
        if (updated) {
            if (next == OrderStatus.ON_WAY && orderService.markShopPickupConfirmationRequested(orderId)) {
                orderService.getOrderForShopPickupMessage(orderId).ifPresent(shopNotificationService::sendShopPickupConfirmationRequest);
            }
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "✅ " + next.getDisplayName());
            courierMenuHandler.editCourierMyOrdersMessage(ctx.chatId(), ctx.messageId(), ctx.telegramId());
        } else {
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "❌ Не удалось обновить статус");
        }
    }

    private void handleCourierStopDelivered(CallbackQueryContext ctx, String rest) {
        String[] parts = rest.split(":", 2);
        if (parts.length != 2) {
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "❌ Ошибка данных");
            return;
        }
        UUID orderId = uuidOrNull(parts[0]);
        if (orderId == null) {
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "❌ Неверный ID заказа");
            return;
        }
        int stopNumber;
        try {
            stopNumber = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "❌ Неверный номер точки");
            return;
        }
        var courierOpt = courierService.findByTelegramId(ctx.telegramId());
        if (courierOpt.isEmpty() || !Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "❌ Нет активного профиля курьера");
            return;
        }
        var orderOpt = orderService.findById(orderId);
        if (orderOpt.isEmpty() || orderOpt.get().getStatus() != OrderStatus.ON_WAY) {
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "❌ Заказ не найден или не в пути");
            return;
        }
        List<OrderStop> stops = orderService.getOrderStops(orderId);
        OrderStop targetStop = stops.stream()
                .filter(s -> s.getStopNumber() != null && s.getStopNumber() == stopNumber)
                .findFirst()
                .orElse(null);
        if (targetStop == null || targetStop.isDelivered()) {
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "❌ Точка уже доставлена или не найдена");
            return;
        }
        ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "📍 Отправьте геолокацию");
        courierGeoHandler.requestLocationForStopDelivery(ctx.telegramId(), ctx.chatId(), orderId, stopNumber, ctx.messageId());
    }

    private void handleCourierBundleTake(CallbackQueryContext ctx, String indicesStr) {
        List<Integer> indices = new ArrayList<>();
        for (String s : indicesStr.split(",")) {
            try {
                indices.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {}
        }
        if (indices.size() < 2 || indices.size() > 3) {
            ctx.responder().sendMessage(ctx.chatId(), "❌ Некорректная связка. Попробуй снова через «📋 Доступные заказы».", "Markdown", null);
            return;
        }
        List<UUID> orderIds = courierAvailableOrdersHandler.getOrderIdsForIndices(ctx.telegramId(), indices);
        if (orderIds.size() != indices.size()) {
            ctx.responder().sendMessage(ctx.chatId(), "❌ Список заказов устарел.\nНажми «📋 Доступные заказы» и попробуй снова.", "Markdown", null);
            return;
        }
        var courierOpt = courierService.findByTelegramId(ctx.telegramId());
        if (courierOpt.isEmpty() || !Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
            ctx.responder().sendMessage(ctx.chatId(), "❌ Нет активного профиля курьера.", "Markdown", null);
            return;
        }
        List<Order> assigned = orderService.assignBundleToCourier(orderIds, courierOpt.get().getUser());
        if (assigned.isEmpty()) {
            ctx.responder().sendMessage(ctx.chatId(), "❌ Не удалось взять связку.\nВозможно: заказы уже заняты, превышен лимит (3 активных) или не хватает средств на депозите.", "Markdown", null);
            return;
        }
        List<Order> withShops = new ArrayList<>();
        for (Order o : assigned) {
            withShops.add(orderService.getOrderWithShop(o.getId()).orElse(o));
        }
        List<Order> forDisplay = orderBundleService.reorderByOptimalRoute(withShops);
        StringBuilder sb = new StringBuilder();
        sb.append("✅ *Связка взята!* (").append(forDisplay.size()).append(" заказов)\n\n");
        sb.append("_Порядок ниже = порядок по маршруту (сначала забор, потом доставка, затем следующий забор и т.д.)._\n\n");
        for (int i = 0; i < forDisplay.size(); i++) {
            Order o = forDisplay.get(i);
            sb.append(i + 1).append(". ").append(o.getRecipientName()).append(" — ").append(o.getDeliveryAddress()).append("\n");
            if (o.getEffectivePickupAddress() != null) {
                sb.append("   🏪 Забрать: ").append(o.getEffectivePickupAddress()).append("\n");
            }
        }
        sb.append("\nНажми «🚚 Мои заказы» для смены статусов.");
        InlineKeyboardMarkup markup = null;
        var courier = courierOpt.get();
        if (courier.getLastLatitude() != null && courier.getLastLongitude() != null) {
            double lat = courier.getLastLatitude().doubleValue();
            double lon = courier.getLastLongitude().doubleValue();
            Optional<OrderBundleService.OrderBundle> routeOpt = orderBundleService.buildRouteForOrders(forDisplay, lat, lon);
            if (routeOpt.isPresent()) {
                var bundle = routeOpt.get();
                markup = new InlineKeyboardMarkup(List.of(List.of(
                        InlineKeyboardButton.builder().text("🌍 Яндекс").url(bundle.yandexRouteUrl()).build(),
                        InlineKeyboardButton.builder().text("🗺 2ГИС").url(bundle.twoGisRouteUrl()).build()
                )));
            }
        }
        ctx.responder().sendMessage(ctx.chatId(), sb.toString(), "Markdown", markup);
    }

    private static UUID uuidOrNull(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static OrderStatus nextStatusForCourier(OrderStatus current) {
        return switch (current) {
            case ACCEPTED -> OrderStatus.IN_SHOP;
            case IN_SHOP -> OrderStatus.ON_WAY;
            case PICKED_UP -> OrderStatus.ON_WAY;
            case ON_WAY -> OrderStatus.DELIVERED;
            default -> null;
        };
    }
}
