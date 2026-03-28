package org.example.flower_delivery.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.Bot;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.OrderStop;
import org.example.flower_delivery.service.CourierService;
import org.example.flower_delivery.service.OrderBundleService;
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
 * РЕАЛИЗОВАНО:
 * - Пагинация: 10 заказов на страницу, кнопки «← Назад» / «Дальше →»
 * - showAvailableOrdersPage() редактирует сообщение (EditMessageText)
 * - Номера при выборе — локальные на странице (1–10)
 * - Связки и «Выбрать заказ» учитывают текущую страницу
 *
 * Логика:
 * 1. Bot формирует список и вызывает saveLastAvailableOrders() + saveLastAvailableCourierLocation().
 * 2. При нажатии "🔎 Выбрать заказ" — ввод номера (1–10 на странице).
 * 3. OrderService.assignOrderToCourier().
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourierAvailableOrdersHandler {

    private final OrderService orderService;
    private final CourierService courierService;
    private final OrderBundleService orderBundleService;

    @Autowired
    @Lazy
    private Bot bot;

    /** Последний показанный список доступных заказов для курьера (только ID). */
    private final Map<Long, List<UUID>> lastAvailableOrderIdsByUser = new ConcurrentHashMap<>();

    /** Текущая страница списка «Доступные заказы» (0-based). */
    private final Map<Long, Integer> lastAvailableOrdersPageByUser = new ConcurrentHashMap<>();

    /** Координаты курьера при последнем показе списка (для пагинации). */
    private final Map<Long, double[]> lastAvailableCourierLocation = new ConcurrentHashMap<>();

    /** Заказов на страницу. */
    public static final int ORDERS_PER_PAGE = 10;

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
        // Честное распределение: 10 ближайших + 10 от «других» магазинов
        List<Order> sorted;
        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isPresent() && courierOpt.get().getUser() != null) {
            sorted = orderService.getAvailableOrdersWithFairness(lat, lon, courierOpt.get().getUser(), 10, 10);
        } else {
            sorted = orderService.getAvailableOrdersSortedByDistanceFrom(lat, lon);
        }
        if (sorted.isEmpty()) {
            clearAwaitingLocationForList(telegramId);
            send(chatId, "📋 *Доступные заказы*\n\nСейчас нет свободных заказов.");
            return;
        }
        saveLastAvailableOrders(telegramId, sorted);
        lastAvailableOrdersPageByUser.put(telegramId, 0);
        lastAvailableCourierLocation.put(telegramId, new double[]{lat, lon});
        showAvailableOrdersPage(telegramId, chatId, 0, null);
        clearAwaitingLocationForList(telegramId);
    }

    /**
     * Показать страницу списка «Доступные заказы». Редактирует сообщение, если messageId задан.
     */
    public void showAvailableOrdersPage(Long telegramId, Long chatId, int page, Integer messageId) {
        List<UUID> ids = lastAvailableOrderIdsByUser.get(telegramId);
        if (ids == null || ids.isEmpty()) {
            send(chatId, "❌ Список доступных заказов устарел.\nНажми ещё раз «📋 Доступные заказы».");
            return;
        }
        double[] loc = lastAvailableCourierLocation.get(telegramId);
        if (loc == null || loc.length < 2) {
            send(chatId, "❌ Нет геолокации для списка.\nНажми «📋 Доступные заказы» и отправь гео.");
            return;
        }
        int totalPages = (ids.size() + ORDERS_PER_PAGE - 1) / ORDERS_PER_PAGE;
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;
        lastAvailableOrdersPageByUser.put(telegramId, page);

        int from = page * ORDERS_PER_PAGE;
        int to = Math.min(from + ORDERS_PER_PAGE, ids.size());
        List<UUID> pageIds = ids.subList(from, to);
        List<Order> ordersToShow = orderService.findByIdsWithShop(pageIds);

        // Для связок — полный список (до 20 заказов), чтобы искать лучшие маршруты по всему списку
        int bundleListSize = Math.min(20, ids.size());
        List<UUID> bundleIds = ids.subList(0, bundleListSize);
        List<Order> fullListForBundles = orderService.findByIdsWithShop(bundleIds);

        var content = bot.buildAvailableOrdersContentWithLocation(ordersToShow, fullListForBundles, loc[0], loc[1],
                page, totalPages, ids.size());
        try {
            if (messageId != null) {
                bot.editAvailableOrdersMessage(chatId, messageId, content.text(), content.markup());
            } else {
                SendMessage message = new SendMessage();
                message.setChatId(chatId.toString());
                message.setText(content.text());
                message.setParseMode("Markdown");
                message.setReplyMarkup(content.markup());
                bot.execute(message);
            }
        } catch (TelegramApiException e) {
            log.error("Ошибка показа страницы доступных заказов: chatId={}", chatId, e);
        }
    }

    public int getCurrentPage(Long telegramId) {
        return lastAvailableOrdersPageByUser.getOrDefault(telegramId, 0);
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

    /** Сохранить геолокацию курьера для списка (нужно для пагинации). */
    public void saveLastAvailableCourierLocation(Long telegramId, double lat, double lon) {
        lastAvailableCourierLocation.put(telegramId, new double[]{lat, lon});
    }

    /** Получить ID заказов по индексам (индексы 1-based в полном списке «Доступные заказы»). */
    public List<UUID> getOrderIdsForIndices(Long telegramId, List<Integer> indices) {
        List<UUID> ids = lastAvailableOrderIdsByUser.get(telegramId);
        if (ids == null || ids.isEmpty()) return List.of();
        List<UUID> result = new ArrayList<>();
        for (int idx : indices) {
            int globalIdx = idx - 1; // 1-based в полном списке
            if (globalIdx >= 0 && globalIdx < ids.size()) {
                result.add(ids.get(globalIdx));
            }
        }
        return result;
    }

    /**
     * Взять заказ по ID (при нажатии «Забрать заказ» в детальном просмотре).
     */
    public boolean takeOrderById(Long telegramId, Long chatId, UUID orderId) {
        var orderOpt = orderService.findById(orderId);
        if (orderOpt.isEmpty()) {
            send(chatId, "❌ Заказ не найден.\nПопробуй снова через «📋 Доступные заказы».");
            return false;
        }
        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty()) {
            send(chatId, "❌ У тебя нет активного профиля курьера.");
            return false;
        }
        var courier = courierOpt.get();
        if (!Boolean.TRUE.equals(courier.getIsActive())) {
            send(chatId, "⏳ Твой профиль курьера ещё не активирован.");
            return false;
        }
        var assignResult = orderService.assignOrderToCourier(orderId, courier.getUser());
        if (assignResult.isEmpty()) {
            if (orderService.isInsufficientBalanceForOrder(orderId, courier.getUser())) {
                send(chatId, "❌ Не хватает денег на депозите для комиссии.\nПополни депозит и попробуй снова.");
            } else {
                send(chatId, "❌ Не удалось взять заказ.\nВозможно, его уже забрал другой курьер.");
            }
            return false;
        }
        Order order = assignResult.get();
        Order orderWithShop = orderService.getOrderWithShop(order.getId()).orElse(order);
        StringBuilder sb = new StringBuilder();
        sb.append("✅ *Заказ взят!*\n\n");
        if (orderWithShop.getEffectivePickupAddress() != null) {
            sb.append("🏪 *Забрать:* ").append(orderWithShop.getEffectivePickupAddress()).append("\n");
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
            sb.append("📅 Дата: ").append(orderWithShop.getDeliveryDate()).append("\n");
        }
        if (courier.getLastLatitude() != null && courier.getLastLongitude() != null) {
            double fromLat = courier.getLastLatitude().doubleValue();
            double fromLon = courier.getLastLongitude().doubleValue();
            var routeOpt = orderBundleService.buildRouteForSingleOrder(orderWithShop, fromLat, fromLon);
            if (routeOpt.isPresent()) {
                var urls = routeOpt.get();
                InlineKeyboardButton yandexBtn = InlineKeyboardButton.builder().text("🌍 Яндекс.Карты").url(urls.yandexUrl()).build();
                InlineKeyboardButton twoGisBtn = InlineKeyboardButton.builder().text("🗺 2ГИС").url(urls.twoGisUrl()).build();
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(yandexBtn, twoGisBtn)));
                send(chatId, sb.toString(), markup);
            } else {
                send(chatId, sb.toString());
            }
        } else {
            send(chatId, sb.toString());
        }
        return true;
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
        int page = lastAvailableOrdersPageByUser.getOrDefault(telegramId, 0);
        int from = page * ORDERS_PER_PAGE;
        int to = Math.min(from + ORDERS_PER_PAGE, ids.size());
        int maxOnPage = to - from;
        send(chatId, "🔎 *Выбор заказа*\n\n" +
                "Введи *номер* заказа из списка на этой странице (1-" + maxOnPage + ").");
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
        int page = lastAvailableOrdersPageByUser.getOrDefault(telegramId, 0);
        int from = page * ORDERS_PER_PAGE;
        int to = Math.min(from + ORDERS_PER_PAGE, ids.size());
        int maxOnPage = to - from;

        // Пытаемся считать номер из списка на текущей странице (1..maxOnPage)
        try {
            int index = Integer.parseInt(text.trim());
            if (index >= 1 && index <= maxOnPage) {
                int globalIdx = from + (index - 1);
                orderId = ids.get(globalIdx);
            }
        } catch (NumberFormatException ignored) {
            // не число
        }

        // Неверный ввод — НЕ выходим из режима выбора, чтобы можно было ввести номер снова
        if (orderId == null) {
            send(chatId, "❌ Не удалось распознать номер заказа.\n" +
                    "Введи число от 1 до " + maxOnPage + ".");
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
            // Проверяем, не упираемся ли в недостаток баланса курьера.
            if (orderService.isInsufficientBalanceForOrder(orderId, courier.getUser())) {
                send(chatId, "❌ Не удалось взять этот заказ.\n" +
                        "Похоже, не хватает денег на депозите для комиссии.\n\n" +
                        "Пополни депозит и попробуй снова.");
            } else {
                send(chatId, "❌ Не удалось взять этот заказ.\n" +
                        "Возможно, его уже забрал другой курьер или он больше не доступен.");
            }
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
        if (orderWithShop.getEffectivePickupAddress() != null) {
            sb.append("🏪 *Забрать:* ").append(orderWithShop.getEffectivePickupAddress()).append("\n");
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

        // Кнопки «Яндекс» и «2ГИС» — маршрут из 3 точек: курьер → забор → доставка
        if (courier.getLastLatitude() != null && courier.getLastLongitude() != null) {
            double fromLat = courier.getLastLatitude().doubleValue();
            double fromLon = courier.getLastLongitude().doubleValue();
            var routeOpt = orderBundleService.buildRouteForSingleOrder(orderWithShop, fromLat, fromLon);
            if (routeOpt.isPresent()) {
                var urls = routeOpt.get();
                InlineKeyboardButton yandexBtn = InlineKeyboardButton.builder()
                        .text("🌍 Яндекс")
                        .url(urls.yandexUrl())
                        .build();
                InlineKeyboardButton twoGisBtn = InlineKeyboardButton.builder()
                        .text("🗺 2ГИС")
                        .url(urls.twoGisUrl())
                        .build();
                InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(yandexBtn, twoGisBtn)));
                send(chatId, sb.toString(), markup);
            } else {
                send(chatId, sb.toString());
            }
        } else {
            send(chatId, sb.toString());
        }
        return true;
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

