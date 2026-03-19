package org.example.flower_delivery.handler;

import lombok.RequiredArgsConstructor;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.service.OrderService;
import org.example.flower_delivery.service.ShopService;
import org.example.flower_delivery.telegram.TelegramSender;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.example.flower_delivery.model.OrderStatus.ACCEPTED;
import static org.example.flower_delivery.model.OrderStatus.DELIVERED;
import static org.example.flower_delivery.model.OrderStatus.IN_SHOP;

/**
 * Обработка кнопок меню магазина: «Мой магазин» и «Мои заказы».
 *
 * Вынесено из Bot (рефакторинг, фаза 4). Логика та же: по telegramId находим магазин,
 * для «Мой магазин» — инфо, для «Мои заказы» — список заказов и кнопка «Выбрать заказ».
 * См. docs/REFACTORING_STATUS.md.
 */
@Component
@RequiredArgsConstructor
public class ShopMenuHandler {

    private final TelegramSender telegramSender;
    private final ShopService shopService;
    private final OrderService orderService;
    private final MyOrdersSelectionHandler myOrdersSelectionHandler;

    /**
     * Кнопка «Мой магазин»: показать название, адрес, телефон, статус магазина.
     */
    public void handleShopInfo(Update update) {
        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();

        var shopOptional = shopService.findByUserTelegramId(telegramId);
        if (shopOptional.isEmpty()) {
            sendSimple(chatId, "❌ У тебя нет зарегистрированного магазина.");
            return;
        }

        Shop shop = shopOptional.get();
        String status = shop.getIsActive() ? "✅ Активен" : "⏳ Ожидает активации";
        sendSimple(chatId, "🏪 *Мой магазин*\n\n" +
                "📋 *Информация:*\n" +
                "• Название: " + shop.getShopName() + "\n" +
                "• Адрес забора: " + shop.getPickupAddress() + "\n" +
                "• Телефон: " + shop.getPhone() + "\n" +
                "• Статус: " + status + "\n\n" +
                "📅 Зарегистрирован: " + shop.getCreatedAt().toLocalDate());
    }

    /**
     * Кнопка «Мои заказы»: список заказов магазина (последние 20), кнопка «Выбрать заказ».
     */
    public void handleMyOrders(Update update) {
        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();

        var shopOptional = shopService.findByUserTelegramId(telegramId);
        if (shopOptional.isEmpty()) {
            sendSimple(chatId, "❌ У тебя нет зарегистрированного магазина.");
            return;
        }

        Shop shop = shopOptional.get();
        List<Order> allOrders = orderService.getOrdersByShop(shop);
        if (allOrders.isEmpty()) {
            sendSimple(chatId, "📋 *Мои заказы*\n\n" +
                    "У тебя пока нет заказов.\n" +
                    "Нажми \"📦 Создать заказ\" чтобы создать первый!");
            return;
        }

        int max = 20;
        int fromIndex = Math.max(0, allOrders.size() - max);
        List<Order> orders = allOrders.subList(fromIndex, allOrders.size());

        StringBuilder sb = new StringBuilder();
        sb.append("📋 *Мои заказы* (").append(allOrders.size()).append(" всего, показаны последние ")
                .append(orders.size()).append(")\n\n");

        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            if (order.isMultiStopOrder()) {
                sb.append("*").append(i + 1).append(". 📦 Мультиадрес (").append(order.getTotalStops()).append(" точек)*\n");
                List<org.example.flower_delivery.model.OrderStop> stops = orderService.getOrderStops(order.getId());
                if (!stops.isEmpty()) {
                    for (org.example.flower_delivery.model.OrderStop stop : stops) {
                        String statusIcon = stop.isDelivered() ? "✅" : "📍";
                        sb.append("   ").append(statusIcon).append(" ").append(stop.getRecipientName());
                        sb.append(" — ").append(stop.getDeliveryAddress()).append("\n");
                    }
                } else {
                    sb.append("   📍 ").append(order.getDeliveryAddress()).append("\n");
                }
            } else {
                sb.append("*").append(i + 1).append(". ").append(order.getRecipientName()).append("*\n");
                sb.append("   📍 ").append(order.getDeliveryAddress()).append("\n");
            }
            sb.append("   💰 ").append(order.getDeliveryPrice()).append("₽\n");
            String statusIcon = (order.getStatus() == ACCEPTED || order.getStatus() == IN_SHOP) ? " 🚴" : (order.getStatus() == DELIVERED ? " ✅" : "");
            sb.append("   📊 Статус: *").append(order.getStatus().getDisplayName()).append("*").append(statusIcon).append("\n");
            if (order.getCreatedAt() != null) {
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
                sb.append("   📅 Создан: ").append(order.getCreatedAt().format(fmt)).append("\n");
            }
            sb.append("\n");
        }

        myOrdersSelectionHandler.saveLastOrders(telegramId, orders);

        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        keyboard.add(List.of(InlineKeyboardButton.builder()
                .text("🔎 Выбрать заказ")
                .callbackData("orders_select")
                .build()));
        telegramSender.sendMessage(chatId, sb.toString(), "Markdown", new InlineKeyboardMarkup(keyboard));
    }

    private void sendSimple(Long chatId, String text) {
        telegramSender.sendMessage(chatId, text, "Markdown", null);
    }
}
