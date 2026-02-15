package org.example.flower_delivery.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.Bot;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.OrderStop;
import org.example.flower_delivery.service.CourierService;
import org.example.flower_delivery.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Хендлер выбора заказа курьером из списка "📋 Доступные заказы".
 *
 * Логика:
 * 1. Bot формирует список доступных заказов и вызывает saveLastAvailableOrders().
 * 2. При нажатии "🔎 Выбрать заказ" мы просим ввести номер или ID.
 * 3. Следующее текстовое сообщение интерпретируем как выбор заказа.
 * 4. Пытаемся назначить заказ курьеру через OrderService.assignOrderToCourier().
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourierAvailableOrdersHandler {

    private final OrderService orderService;
    private final CourierService courierService;

    @Autowired
    @Lazy
    private Bot bot;

    /** Последний показанный список доступных заказов для курьера (только ID). */
    private final Map<Long, List<UUID>> lastAvailableOrderIdsByUser = new ConcurrentHashMap<>();

    /** telegramId'ы, от которых мы сейчас ждём номер/ID заказа. */
    private final Map<Long, Boolean> awaitingSelection = new ConcurrentHashMap<>();

    /** Курьер нажал «Доступные заказы», но гео не «свежее» — ждём локацию, чтобы показать ближайшие. */
    private final Map<Long, Boolean> awaitingLocationForList = new ConcurrentHashMap<>();

    public void startAwaitingLocationForList(Long telegramId) {
        awaitingLocationForList.put(telegramId, true);
    }

    public boolean isAwaitingLocationForList(Long telegramId) {
        return awaitingLocationForList.getOrDefault(telegramId, false);
    }

    public void clearAwaitingLocationForList(Long telegramId) {
        awaitingLocationForList.remove(telegramId);
    }

    /**
     * Получили гео курьера для списка «Доступные заказы» — показываем список, отсортированный по расстоянию, с кнопками «Маршрут».
     */
    public void handleLocationForAvailableList(Long telegramId, Long chatId, double lat, double lon) {
        courierService.updateLastLocation(telegramId, lat, lon);
        // Честное распределение: первые 5 — ближайшие, следующие 5 — от магазинов, которым курьер мало отдавал за 24 ч
        List<Order> sorted;
        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isPresent() && courierOpt.get().getUser() != null) {
            sorted = orderService.getAvailableOrdersWithFairness(lat, lon, courierOpt.get().getUser(), 5, 5);
        } else {
            sorted = orderService.getAvailableOrdersSortedByDistanceFrom(lat, lon);
        }
        if (sorted.isEmpty()) {
            clearAwaitingLocationForList(telegramId);
            send(chatId, "📋 *Доступные заказы*\n\nСейчас нет свободных заказов.");
            return;
        }
        int limit = Math.min(10, sorted.size());
        List<Order> ordersToShow = sorted.subList(0, limit);
        saveLastAvailableOrders(telegramId, ordersToShow);
        var content = bot.buildAvailableOrdersContentWithLocation(ordersToShow, lat, lon);
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(content.text());
        message.setParseMode("Markdown");
        message.setReplyMarkup(content.markup());
        try {
            bot.execute(message);
            clearAwaitingLocationForList(telegramId); // сбрасываем только после успешной отправки
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки списка доступных заказов: chatId={}", chatId, e);
        }
    }

    /** Сохранить список доступных заказов для курьера. */
    public void saveLastAvailableOrders(Long telegramId, List<Order> orders) {
        List<UUID> ids = new ArrayList<>();
        for (Order order : orders) {
            ids.add(order.getId());
        }
        lastAvailableOrderIdsByUser.put(telegramId, ids);
        log.debug("Сохранён список {} доступных заказов для курьера {}", ids.size(), telegramId);
    }

    /** Начать процесс выбора заказа (после нажатия inline-кнопки "Выбрать заказ"). */
    public void startSelection(Long telegramId, Long chatId) {
        List<UUID> ids = lastAvailableOrderIdsByUser.get(telegramId);
        if (ids == null || ids.isEmpty()) {
            send(chatId, "❌ Нет актуального списка доступных заказов.\n" +
                    "Нажми ещё раз «📋 Доступные заказы».");
            return;
        }
        awaitingSelection.put(telegramId, true);
        send(chatId, "🔎 *Выбор заказа*\n\n" +
                "Введи *номер* заказа из списка (1-" + ids.size() + ").");
    }

    public boolean isAwaitingSelection(Long telegramId) {
        return awaitingSelection.getOrDefault(telegramId, false);
    }

    /**
     * Обработать текст курьера как выбор заказа.
     *
     * @return true если сообщение обработано
     */
    public boolean handleText(Long telegramId, Long chatId, String text) {
        if (!isAwaitingSelection(telegramId)) {
            return false;
        }

        List<UUID> ids = lastAvailableOrderIdsByUser.get(telegramId);
        if (ids == null || ids.isEmpty()) {
            awaitingSelection.remove(telegramId);
            send(chatId, "❌ Список доступных заказов устарел.\n" +
                    "Нажми ещё раз «📋 Доступные заказы».");
            return true;
        }

        UUID orderId = null;

        // Пытаемся считать номер из списка (1..N)
        try {
            int index = Integer.parseInt(text.trim());
            if (index >= 1 && index <= ids.size()) {
                orderId = ids.get(index - 1);
            }
        } catch (NumberFormatException ignored) {
            // не число
        }

        // Неверный ввод — НЕ выходим из режима выбора, чтобы можно было ввести номер снова
        if (orderId == null) {
            send(chatId, "❌ Не удалось распознать номер заказа.\n" +
                    "Введи число от 1 до " + ids.size() + ".");
            return true;
        }

        awaitingSelection.remove(telegramId);

        var orderOpt = orderService.findById(orderId);
        if (orderOpt.isEmpty()) {
            send(chatId, "❌ Заказ с таким ID не найден.\n" +
                    "Попробуй снова через «📋 Доступные заказы».");
            return true;
        }

        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty()) {
            send(chatId, "❌ У тебя нет активного профиля курьера.\n" +
                    "Выбери роль *Курьер* через /start и пройди регистрацию.");
            return true;
        }
        var courier = courierOpt.get();
        if (!Boolean.TRUE.equals(courier.getIsActive())) {
            send(chatId, "⏳ Твой профиль курьера ещё не активирован.\n" +
                    "Сначала активируй его командой /k (временно).");
            return true;
        }

        var assignResult = orderService.assignOrderToCourier(orderId, courier.getUser());
        if (assignResult.isEmpty()) {
            send(chatId, "❌ Не удалось взять этот заказ.\n" +
                    "Возможно, его уже забрал другой курьер или он больше не доступен.");
            return true;
        }

        Order order = assignResult.get();
        // Загружаем заказ с магазином для адреса забора
        Order orderWithShop = orderService.getOrderWithShop(order.getId()).orElse(order);

        Integer displayNumber = null;
        int idxInList = ids.indexOf(orderId);
        if (idxInList >= 0) {
            displayNumber = idxInList + 1;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("✅ *Заказ взят!*\n\n");
        if (displayNumber != null) {
            sb.append("📋 Номер в списке: `").append(displayNumber).append("`\n");
        }
        if (orderWithShop.getShop() != null && orderWithShop.getShop().getPickupAddress() != null) {
            sb.append("🏪 *Забрать:* ").append(orderWithShop.getShop().getPickupAddress()).append("\n");
        }
        if (orderWithShop.isMultiStopOrder()) {
            var stops = orderService.getOrderStops(orderWithShop.getId());
            if (!stops.isEmpty()) {
                for (OrderStop stop : stops) {
                    sb.append("📍 ").append(stop.getRecipientName()).append(" — ").append(stop.getDeliveryAddress())
                            .append(" (").append(stop.getRecipientPhone()).append(")\n");
                }
            } else {
                sb.append("📍 ").append(orderWithShop.getDeliveryAddress()).append("\n");
                sb.append("👤 ").append(orderWithShop.getRecipientName()).append(" (").append(orderWithShop.getRecipientPhone()).append(")\n");
            }
        } else {
            sb.append("📍 Адрес: ").append(orderWithShop.getDeliveryAddress()).append("\n");
            sb.append("👤 Получатель: ").append(orderWithShop.getRecipientName())
                    .append(" (").append(orderWithShop.getRecipientPhone()).append(")\n");
        }
        sb.append("💰 Оплата: ").append(orderWithShop.getDeliveryPrice()).append("₽\n");
        if (orderWithShop.getDeliveryDate() != null) {
            sb.append("📅 Дата доставки: ").append(orderWithShop.getDeliveryDate()).append("\n");
        }

        // Кнопка «Маршрут до магазина» — после выбора заказа (Яндекс.Карты)
        if (orderWithShop.getShop() != null
                && orderWithShop.getShop().getLatitude() != null
                && orderWithShop.getShop().getLongitude() != null
                && courier.getLastLatitude() != null
                && courier.getLastLongitude() != null) {
            String routeUrl = buildYandexRouteUrl(
                    courier.getLastLatitude().doubleValue(), courier.getLastLongitude().doubleValue(),
                    orderWithShop.getShop().getLatitude().doubleValue(), orderWithShop.getShop().getLongitude().doubleValue());
            InlineKeyboardButton routeBtn = InlineKeyboardButton.builder()
                    .text("🗺 Маршрут до магазина")
                    .url(routeUrl)
                    .build();
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(routeBtn)));
            send(chatId, sb.toString(), markup);
        } else {
            send(chatId, sb.toString());
        }
        return true;
    }

    private static String buildYandexRouteUrl(double fromLat, double fromLon, double toLat, double toLon) {
        return "https://yandex.ru/maps/?rtext=" + fromLat + "," + fromLon + "~" + toLat + "," + toLon + "&rtt=auto";
    }

    private void send(Long chatId, String text) {
        send(chatId, text, null);
    }

    private void send(Long chatId, String text, InlineKeyboardMarkup replyMarkup) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(text);
        msg.setParseMode("Markdown");
        if (replyMarkup != null) {
            msg.setReplyMarkup(replyMarkup);
        }
        try {
            bot.execute(msg);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения курьеру: chatId={}", chatId, e);
        }
    }
}

