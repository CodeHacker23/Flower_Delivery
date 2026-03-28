package org.example.flower_delivery.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Courier;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.OrderStatus;
import org.example.flower_delivery.model.OrderStop;
import org.example.flower_delivery.service.CourierService;
import org.example.flower_delivery.service.OrderBundleService;
import org.example.flower_delivery.service.OrderService;
import org.example.flower_delivery.telegram.TelegramSender;
import org.example.flower_delivery.util.TextFormattingUtil;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.example.flower_delivery.model.OrderStatus.*;

/**
 * Обработка кнопок меню курьера: «📋 Доступные заказы» и «🚚 Мои заказы» (рефакторинг, фаза 5).
 *
 * Вынесено из Bot: показ списков, построение контента, запрос гео, редактирование сообщений
 * при пагинации/смене статуса. См. docs/REFACTORING_STATUS.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourierMenuHandler {

    private final TelegramSender telegramSender;
    private final CourierService courierService;
    private final OrderService orderService;
    private final OrderBundleService orderBundleService;
    private final CourierAvailableOrdersHandler courierAvailableOrdersHandler;

    // ---------- Доступные заказы ----------

    /**
     * Кнопка «📋 Доступные заказы»: проверка курьера, при наличии свежей гео — список, иначе запрос геолокации.
     */
    public void handleCourierAvailableOrdersButton(Update update) {
        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();

        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty()) {
            sendSimple(chatId, "❌ У тебя ещё нет профиля курьера.\n\n" +
                    "Выбери роль *Курьер* через /start и пройди регистрацию.");
            return;
        }
        var courier = courierOpt.get();
        if (!Boolean.TRUE.equals(courier.getIsActive())) {
            sendSimple(chatId, "⏳ Твой профиль курьера ещё не активирован.\n\n" +
                    "Сначала активируй его командой /k (временно),\n" +
                    "позже это будет делать админ.");
            return;
        }

        long activeCount = orderService.countActiveOrdersForCourier(courier.getUser());
        int maxActive = 3;
        if (activeCount >= maxActive) {
            sendSimple(chatId, "🚫 У тебя уже " + activeCount + " активных заказов.\n\n" +
                    "Сначала довези текущие (кнопка \"🚚 Мои заказы\"),\n" +
                    "потом можно брать новые.");
            return;
        }

        boolean hasFreshLocation = courier.getLastLocationAt() != null
                && courier.getLastLocationAt().isAfter(LocalDateTime.now().minusMinutes(30))
                && courier.getLastLatitude() != null && courier.getLastLongitude() != null;

        List<Order> availableOrders;
        if (hasFreshLocation) {
            availableOrders = orderService.getAvailableOrdersWithFairness(
                    courier.getLastLatitude().doubleValue(), courier.getLastLongitude().doubleValue(),
                    courier.getUser(), 10, 10);
        } else {
            availableOrders = orderService.getAvailableOrdersWithShop();
        }

        if (availableOrders.isEmpty()) {
            sendSimple(chatId, "📋 *Доступные заказы*\n\n" +
                    "Сейчас нет свободных заказов.\n" +
                    "Загляни сюда чуть позже.");
            return;
        }

        if (hasFreshLocation) {
            courierAvailableOrdersHandler.saveLastAvailableOrders(telegramId, availableOrders);
            courierAvailableOrdersHandler.saveLastAvailableCourierLocation(telegramId,
                    courier.getLastLatitude().doubleValue(), courier.getLastLongitude().doubleValue());
            courierAvailableOrdersHandler.showAvailableOrdersPage(telegramId, chatId, 0, null);
            return;
        }

        courierAvailableOrdersHandler.startAwaitingLocationForList(telegramId);
        sendLocationRequestForAvailableOrders(chatId);
    }

    /**
     * Запрос геолокации для списка «Доступные заказы».
     */
    public void sendLocationRequestForAvailableOrders(Long chatId) {
        KeyboardButton locationButton = new KeyboardButton("📍 Отправить геолокацию");
        locationButton.setRequestLocation(true);
        KeyboardRow row = new KeyboardRow();
        row.add(locationButton);
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(List.of(row));
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(true);
        telegramSender.sendMessageWithReplyKeyboard(chatId,
                "📍 Отправьте геолокацию, чтобы показать *ближайшие* заказы и построить маршрут до магазина.",
                "Markdown", keyboard);
    }

    /**
     * Текст и клавиатура списка «Доступные заказы». Вызывается из CourierAvailableOrdersHandler при показе/пагинации.
     */
    public record AvailableOrdersContent(String text, InlineKeyboardMarkup markup) {}

    public AvailableOrdersContent buildAvailableOrdersContentWithLocation(List<Order> ordersToShow,
                                                                          List<Order> fullListForBundles,
                                                                          double courierLat, double courierLon,
                                                                          int page, int totalPages, int totalCount) {
        long startedAt = System.currentTimeMillis();
        LocalDate today = LocalDate.now();
        String dateStr = today.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        StringBuilder sb = new StringBuilder();
        sb.append("📋 *Список заказов* (").append(page + 1).append("/").append(totalPages)
                .append(") на ").append(dateStr).append("\n\n")
                .append("Нажми на заказ, чтобы увидеть детали и взять его.");

        List<OrderBundleService.OrderBundle> bundles = orderBundleService.findRecommendedBundles(
                fullListForBundles != null && !fullListForBundles.isEmpty() ? fullListForBundles : ordersToShow,
                courierLat, courierLon);
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        for (int i = 0; i < ordersToShow.size(); i++) {
            Order order = ordersToShow.get(i);
            int number = page * CourierAvailableOrdersHandler.ORDERS_PER_PAGE + i + 1;
            String timeStr = TextFormattingUtil.shortTimeForButton(order.getDeliveryInterval());
            String shopAddr = order.getEffectivePickupAddress() != null
                    ? TextFormattingUtil.streetAndHouseOnly(order.getEffectivePickupAddress())
                    : "—";
            String deliveryAddr;
            if (order.isMultiStopOrder()) {
                String route = order.getRouteDescription();
                deliveryAddr = Arrays.stream(route.split(" → "))
                        .map(String::trim)
                        .map(addr -> TextFormattingUtil.streetAndHouseOnly(addr))
                        .reduce((a, b) -> a + "→" + b)
                        .orElse("—");
            } else {
                deliveryAddr = TextFormattingUtil.streetAndHouseOnly(order.getDeliveryAddress());
            }
            if (shopAddr.length() > 20) shopAddr = TextFormattingUtil.truncateForButton(shopAddr, 20);
            if (deliveryAddr.length() > 20) deliveryAddr = TextFormattingUtil.truncateForButton(deliveryAddr, 20);
            String btnText = "Заказ " + number + " (" + timeStr + ") " + shopAddr + "→" + deliveryAddr;
            if (btnText.length() > 64) {
                btnText = btnText.substring(0, 61) + ".";
            }
            keyboard.add(List.of(
                    InlineKeyboardButton.builder().text(btnText).callbackData("courier_order_view:" + order.getId()).build()
            ));
        }

        for (int b = 0; b < bundles.size(); b++) {
            OrderBundleService.OrderBundle bundle = bundles.get(b);
            String indicesStr = String.join(",", bundle.indicesInList().stream().map(String::valueOf).toList());
            List<String> displayNumbers = bundle.indicesInList().stream().map(String::valueOf).toList();
            String indicesDisplay = String.join(", ", displayNumbers);
            String btnText = (b == 0) ? "📦 Взять связку (" + indicesDisplay + ")" : "📦 Альтернативная связка (" + indicesDisplay + ")";
            keyboard.add(List.of(
                    InlineKeyboardButton.builder().text(btnText).callbackData("courier_bundle_take:" + indicesStr).build()
            ));
        }

        if (totalPages > 1) {
            List<InlineKeyboardButton> navRow = new ArrayList<>();
            if (page > 0) {
                navRow.add(InlineKeyboardButton.builder().text("← Назад").callbackData("courier_orders_page:" + (page - 1)).build());
            }
            if (page < totalPages - 1) {
                navRow.add(InlineKeyboardButton.builder().text("Дальше →").callbackData("courier_orders_page:" + (page + 1)).build());
            }
            if (!navRow.isEmpty()) {
                keyboard.add(navRow);
            }
        }
        long finishedAt = System.currentTimeMillis();
        log.info("CourierMenuHandler.buildAvailableOrdersContentWithLocation: bundlesCount={}, orders={}, durationMs={}",
                bundles.size(), ordersToShow.size(), (finishedAt - startedAt));
        return new AvailableOrdersContent(sb.toString(), new InlineKeyboardMarkup(keyboard));
    }

    /**
     * Редактировать сообщение «Доступные заказы» (пагинация).
     */
    public void editAvailableOrdersMessage(Long chatId, Integer messageId, String text, InlineKeyboardMarkup markup) {
        telegramSender.editMessage(chatId, messageId, text, markup);
    }

    // ---------- Мои заказы курьера ----------

    /**
     * Кнопка «🚚 Мои заказы»: список заказов курьера с кнопками смены статуса.
     */
    public void handleCourierMyOrdersButton(Update update) {
        Long chatId = update.getMessage().getChatId();
        Long telegramId = update.getMessage().getFrom().getId();

        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty()) {
            sendSimple(chatId, "❌ У тебя ещё нет профиля курьера.\n\n" +
                    "Выбери роль *Курьер* через /start и пройди регистрацию.");
            return;
        }
        var courier = courierOpt.get();
        if (!Boolean.TRUE.equals(courier.getIsActive())) {
            sendSimple(chatId, "⏳ Твой профиль курьера ещё не активирован.\n\n" +
                    "Сначала активируй его командой /k (временно),\n" +
                    "позже это будет делать админ.");
            return;
        }

        List<Order> allOrders = orderService.getOrdersByCourierWithShop(courier.getUser());
        if (allOrders.isEmpty()) {
            sendSimple(chatId, "🚚 *Мои заказы (курьер)*\n\n" +
                    "У тебя пока нет заказов.\n" +
                    "Зайди в «📋 Доступные заказы» и возьми первый заказ.");
            return;
        }

        CourierMyOrdersContent content = buildCourierMyOrdersContent(courier, allOrders);
        telegramSender.sendMessage(chatId, content.text, "Markdown", content.replyMarkup);
    }

    /**
     * Собрать текст и клавиатуру списка «Мои заказы» курьера (для отправки и редактирования).
     */
    public record CourierMyOrdersContent(String text, InlineKeyboardMarkup replyMarkup) {}

    public CourierMyOrdersContent buildCourierMyOrdersContent(Courier courier, List<Order> allOrders) {
        List<Order> active = allOrders.stream()
                .filter(o -> o.getStatus() == OrderStatus.ACCEPTED || o.getStatus() == OrderStatus.IN_SHOP
                        || o.getStatus() == OrderStatus.PICKED_UP || o.getStatus() == OrderStatus.ON_WAY)
                .toList();
        List<Order> completed = allOrders.stream()
                .filter(o -> !active.contains(o))
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
                    if (a.getCreatedAt() == null) return 1;
                    if (b.getCreatedAt() == null) return -1;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .toList();
        int rest = Math.max(0, 6 - active.size());
        List<Order> orders = new ArrayList<>(active);
        orders.addAll(completed.stream().limit(rest).toList());

        StringBuilder sb = new StringBuilder();
        sb.append("🚚 *Мои заказы (курьер)*\n\n");
        sb.append("Всего: ").append(allOrders.size());
        if (!active.isEmpty()) {
            sb.append(", активных: ").append(active.size());
        }
        sb.append(", показано: ").append(orders.size()).append("\n\n");

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            if (order.getEffectivePickupAddress() != null) {
                sb.append("*").append(i + 1).append(". ").append(order.getRecipientName()).append("*\n");
                sb.append("   🏪 Забрать: ").append(order.getEffectivePickupAddress()).append("\n");
            } else {
                sb.append("*").append(i + 1).append(". ").append(order.getRecipientName()).append("*\n");
            }
            if (order.isMultiStopOrder()) {
                List<OrderStop> stops = orderService.getOrderStops(order.getId());
                if (!stops.isEmpty()) {
                    for (OrderStop stop : stops) {
                        String pointIcon = stop.isDelivered() ? "✅" : "📍";
                        sb.append("   ").append(pointIcon).append(" Точка ").append(stop.getStopNumber())
                                .append(": ").append(stop.getRecipientName()).append(" — ").append(stop.getDeliveryAddress()).append("\n");
                    }
                } else {
                    sb.append("   📍 ").append(order.getDeliveryAddress()).append("\n");
                }
            } else {
                sb.append("   📍 ").append(order.getDeliveryAddress()).append("\n");
            }
            sb.append("   💰 ").append(order.getDeliveryPrice()).append("₽\n");
            String statusIcon;
            switch (order.getStatus()) {
                case ACCEPTED, IN_SHOP, PICKED_UP, ON_WAY -> statusIcon = "🔥";
                case DELIVERED -> statusIcon = "✅";
                case RETURNED -> statusIcon = "↩️";
                case CANCELLED -> statusIcon = "⛔";
                case NEW -> statusIcon = "🆕";
                default -> statusIcon = "ℹ️";
            }
            sb.append("   📊 Статус: *").append(order.getStatus().getDisplayName()).append("* ").append(statusIcon).append("\n");
            if (order.getCreatedAt() != null) {
                sb.append("   📅 Создан: ").append(order.getCreatedAt().format(fmt)).append("\n");
            }
            sb.append("\n");
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> statusRow = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            OrderStatus next = nextStatusForCourier(order.getStatus());
            if (next == null) continue;
            if (next == DELIVERED && order.isMultiStopOrder()) {
                List<OrderStop> stops = orderService.getOrderStops(order.getId());
                for (OrderStop stop : stops) {
                    if (!stop.isDelivered()) {
                        InlineKeyboardButton btn = new InlineKeyboardButton();
                        String shortAddr = TextFormattingUtil.shortAddressForButton(stop.getDeliveryAddress());
                        btn.setText((i + 1) + ". " + (shortAddr.isEmpty() ? "Точка " + stop.getStopNumber() : shortAddr) + " → Вручил");
                        btn.setCallbackData("courier_stop_delivered:" + order.getId() + ":" + stop.getStopNumber());
                        statusRow.add(btn);
                    }
                }
            } else {
                InlineKeyboardButton btn = new InlineKeyboardButton();
                String shortAddr;
                if (next == OrderStatus.IN_SHOP && order.getShop() != null) {
                    shortAddr = TextFormattingUtil.shortAddressForButton(order.getEffectivePickupAddress());
                } else {
                    shortAddr = TextFormattingUtil.shortAddressForButton(order.getDeliveryAddress());
                    if (order.isMultiStopOrder()) {
                        var stops = orderService.getOrderStops(order.getId());
                        if (!stops.isEmpty()) shortAddr = TextFormattingUtil.shortAddressForButton(stops.get(0).getDeliveryAddress());
                    }
                }
                btn.setText((i + 1) + ". " + (shortAddr.isEmpty() ? "" : shortAddr + " ") + "→ " + next.getDisplayName());
                btn.setCallbackData("courier_order_next:" + order.getId());
                statusRow.add(btn);
            }
        }
        for (int r = 0; r < statusRow.size(); r += 4) {
            rows.add(statusRow.subList(r, Math.min(r + 4, statusRow.size())));
        }

        boolean hasActiveOrders = orders.stream().anyMatch(o ->
                o.getStatus() == OrderStatus.ACCEPTED
                        || o.getStatus() == OrderStatus.IN_SHOP
                        || o.getStatus() == OrderStatus.PICKED_UP
                        || o.getStatus() == OrderStatus.ON_WAY);
        if (hasActiveOrders) {
            InlineKeyboardButton cancelBtn = new InlineKeyboardButton();
            cancelBtn.setText("⛔ Отменить заказ");
            cancelBtn.setCallbackData("courier_cancel_select");
            rows.add(List.of(cancelBtn));
        }
        markup.setKeyboard(rows);
        return new CourierMyOrdersContent(sb.toString(), markup);
    }

    /**
     * Редактировать сообщение «Мои заказы» курьера (после смены статуса и т.д.).
     */
    public void editCourierMyOrdersMessage(Long chatId, Integer messageId, Long telegramId) {
        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty()) return;
        List<Order> allOrders = orderService.getOrdersByCourierWithShop(courierOpt.get().getUser());
        if (allOrders.isEmpty()) return;
        CourierMyOrdersContent content = buildCourierMyOrdersContent(courierOpt.get(), allOrders);
        telegramSender.editMessage(chatId, messageId, content.text, content.replyMarkup);
    }

    private static OrderStatus nextStatusForCourier(OrderStatus current) {
        return switch (current) {
            case ACCEPTED -> IN_SHOP;
            case IN_SHOP -> ON_WAY;
            case PICKED_UP -> ON_WAY;
            case ON_WAY -> DELIVERED;
            default -> null;
        };
    }

    private void sendSimple(Long chatId, String text) {
        telegramSender.sendMessage(chatId, text, "Markdown", null);
    }
}
