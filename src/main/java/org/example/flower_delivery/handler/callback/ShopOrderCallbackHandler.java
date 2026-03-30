package org.example.flower_delivery.handler.callback;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.service.OrderService;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.example.flower_delivery.handler.MyOrdersSelectionHandler;
import org.example.flower_delivery.handler.OrderCreationHandler;
import org.example.flower_delivery.handler.OrderEditHandler;

/**
 * Обработка callback: создание заказа (дата, интервал, цена, мультиадрес), отмена/редактирование заказа магазином, выбор заказа, shop_pickup_confirm.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopOrderCallbackHandler {

    private final OrderService orderService;
    private final OrderCreationHandler orderCreationHandler;
    private final OrderEditHandler orderEditHandler;
    private final MyOrdersSelectionHandler myOrdersSelectionHandler;

    public boolean handles(String callbackData) {
        if (callbackData == null) return false;
        return callbackData.startsWith("delivery_date_") || callbackData.startsWith("delivery_interval_")
                || callbackData.startsWith("confirm_price_") || "order_creation_cancel".equals(callbackData)
                || "add_stop_yes".equals(callbackData) || "add_stop_no".equals(callbackData)
                || callbackData.startsWith("confirm_additional_price_")
                || callbackData.startsWith("order_cancel_ok_") || "order_cancel_no".equals(callbackData)
                || (callbackData.startsWith("order_cancel_") && !callbackData.startsWith("order_cancel_ok_") && !"order_cancel_no".equals(callbackData))
                || callbackData.startsWith("order_edit_") || "orders_select".equals(callbackData)
                || callbackData.startsWith("shop_pickup_confirm:");
    }

    public void handle(CallbackQueryContext ctx) {
        String data = ctx.callbackData();
        CallbackQueryResponder r = ctx.responder();

        if (data.startsWith("delivery_date_")) {
            r.answerCallbackQuery(ctx.callbackQueryId(), "📅 Дата выбрана");
            orderCreationHandler.handleDateSelection(ctx.telegramId(), ctx.chatId(), data);
        } else if (data.startsWith("delivery_interval_")) {
            r.answerCallbackQuery(ctx.callbackQueryId(), "⏰ Интервал выбран");
            orderCreationHandler.handleDeliveryIntervalSelection(ctx.telegramId(), ctx.chatId(), data);
        } else if (data.startsWith("confirm_price_")) {
            String priceStr = data.replace("confirm_price_", "");
            r.answerCallbackQuery(ctx.callbackQueryId(), "✅ Цена подтверждена");
            orderCreationHandler.handlePriceConfirmation(ctx.telegramId(), ctx.chatId(), new BigDecimal(priceStr));
        } else if ("order_creation_cancel".equals(data)) {
            r.answerCallbackQuery(ctx.callbackQueryId(), "Заказ отменён");
            orderCreationHandler.cancelOrderCreation(ctx.telegramId(), ctx.chatId());
        } else if ("add_stop_yes".equals(data)) {
            r.answerCallbackQuery(ctx.callbackQueryId(), "➕ Добавляем адрес...");
            orderCreationHandler.handleAddStopDecision(ctx.telegramId(), ctx.chatId(), true);
        } else if ("add_stop_no".equals(data)) {
            r.answerCallbackQuery(ctx.callbackQueryId(), "✅ Завершаем...");
            orderCreationHandler.handleAddStopDecision(ctx.telegramId(), ctx.chatId(), false);
        } else if (data.startsWith("confirm_additional_price_")) {
            String priceStr = data.replace("confirm_additional_price_", "");
            r.answerCallbackQuery(ctx.callbackQueryId(), "✅ Цена подтверждена");
            orderCreationHandler.handleAdditionalPriceConfirmation(ctx.telegramId(), ctx.chatId(), new BigDecimal(priceStr));
        } else if (data.startsWith("order_cancel_ok_")) {
            String orderIdStr = data.replace("order_cancel_ok_", "");
            r.answerCallbackQuery(ctx.callbackQueryId(), "Отменяю заказ...");
            handleOrderCancelConfirm(ctx, orderIdStr);
        } else if ("order_cancel_no".equals(data)) {
            r.answerCallbackQuery(ctx.callbackQueryId(), "Ок, заказ не отменён");
            r.sendMessage(ctx.chatId(), "✅ Заказ остаётся в силе.", "Markdown", null);
        } else if (data.startsWith("order_cancel_")) {
            String orderIdStr = data.replace("order_cancel_", "");
            r.answerCallbackQuery(ctx.callbackQueryId(), "Отменить заказ?");
            handleOrderCancelAsk(ctx, orderIdStr);
        } else if (data.startsWith("order_edit_")) {
            r.answerCallbackQuery(ctx.callbackQueryId(), "✏️ Редактирование");
            dispatchOrderEdit(ctx, data);
        } else if ("orders_select".equals(data)) {
            r.answerCallbackQuery(ctx.callbackQueryId(), "🔎 Выбор заказа");
            myOrdersSelectionHandler.startSelection(ctx.telegramId(), ctx.chatId());
        } else if (data.startsWith("shop_pickup_confirm:")) {
            handleShopPickupConfirmation(ctx);
        }
    }

    private void dispatchOrderEdit(CallbackQueryContext ctx, String callbackData) {
        if (callbackData.contains("_date_today") || callbackData.contains("_date_tomorrow")) {
            orderEditHandler.handleDateSelected(ctx.telegramId(), ctx.chatId(), callbackData);
        } else if (callbackData.contains("_date") && !callbackData.contains("_date_to")) {
            orderEditHandler.handleEditDateMenu(ctx.telegramId(), ctx.chatId(), callbackData);
        } else if (callbackData.contains("_address") || callbackData.contains("_phone") || callbackData.contains("_comment")) {
            orderEditHandler.handleSelectField(ctx.telegramId(), ctx.chatId(), callbackData);
        } else if (callbackData.contains("_stop_")) {
            orderEditHandler.handleSelectPoint(ctx.telegramId(), ctx.chatId(), callbackData);
        } else {
            orderEditHandler.handleEditMenu(ctx.telegramId(), ctx.chatId(), callbackData);
        }
    }

    private void handleOrderCancelAsk(CallbackQueryContext ctx, String orderIdStr) {
        InlineKeyboardButton btnYes = InlineKeyboardButton.builder().text("Да, отменить").callbackData("order_cancel_ok_" + orderIdStr).build();
        InlineKeyboardButton btnNo = InlineKeyboardButton.builder().text("Нет").callbackData("order_cancel_no").build();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(btnYes, btnNo)));
        ctx.responder().sendMessage(ctx.chatId(), "❓ *Точно отменить этот заказ?*", "Markdown", markup);
    }

    private void handleOrderCancelConfirm(CallbackQueryContext ctx, String orderIdStr) {
        UUID orderId;
        try {
            orderId = UUID.fromString(orderIdStr);
        } catch (IllegalArgumentException e) {
            ctx.responder().sendMessage(ctx.chatId(), "❌ Ошибка: неверный ID заказа.", "Markdown", null);
            return;
        }
        boolean cancelled = orderService.cancelOrder(orderId);
        if (cancelled) {
            ctx.responder().sendMessage(ctx.chatId(), "✅ *Заказ отменён.*\n\nНажми «📋 Мои заказы», чтобы обновить список.", "Markdown", null);
        } else {
            ctx.responder().sendMessage(ctx.chatId(), "❌ Не удалось отменить заказ.\nВозможно, он уже принят курьером или не найден.", "Markdown", null);
        }
    }

    private void handleShopPickupConfirmation(CallbackQueryContext ctx) {
        String data = ctx.callbackData();
        String rest = data.replace("shop_pickup_confirm:", "");
        String[] parts = rest.split(":", 2);
        if (parts.length != 2) {
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "❌ Ошибка данных");
            return;
        }
        UUID orderId;
        try {
            orderId = UUID.fromString(parts[0]);
        } catch (IllegalArgumentException e) {
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "❌ Неверный заказ");
            return;
        }
        boolean confirmed = "yes".equalsIgnoreCase(parts[1]);
        var orderOpt = orderService.getOrderForShopPickupMessage(orderId);
        if (orderOpt.isEmpty()) {
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "❌ Заказ не найден");
            return;
        }
        Order order = orderOpt.get();
        if (order.getShop() == null || order.getShop().getUser() == null
                || !Objects.equals(order.getShop().getUser().getTelegramId(), ctx.telegramId())) {
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "❌ Это не ваш заказ");
            return;
        }
        boolean updated = orderService.setShopPickupConfirmed(orderId, confirmed);
        if (!updated) {
            ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "Вы уже ответили на этот запрос");
            return;
        }
        String editText = confirmed ? "✅ Подтверждено: вы передали заказ курьеру." : "❌ Вы ответили: Нет — заказ курьеру не передан.";
        ctx.responder().editMessage(ctx.chatId(), ctx.messageId(), editText, null);
        ctx.responder().answerCallbackQuery(ctx.callbackQueryId(), "");
    }
}
