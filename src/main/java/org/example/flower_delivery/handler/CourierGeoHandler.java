package org.example.flower_delivery.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.Bot;
import org.example.flower_delivery.model.Courier;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.OrderStatus;
import org.example.flower_delivery.model.OrderStop;
import org.example.flower_delivery.util.GeoUtil;
import org.example.flower_delivery.service.CourierGeoService;
import org.example.flower_delivery.service.CourierService;
import org.example.flower_delivery.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Обработчик геолокации курьера: «2 в 1» — подтверждение статуса «В магазине» / «Вручил» через отправку локации.
 * Состояние: ожидание локации для заказа (orderId + следующий статус).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourierGeoHandler {

    private final CourierService courierService;
    private final CourierGeoService courierGeoService;
    private final OrderService orderService;

    @Autowired
    @Lazy
    private Bot bot;

    /** telegramId -> (orderId, nextStatus) — курьер ожидает отправить локацию для подтверждения статуса. */
    private final Map<Long, PendingGeoConfirmation> awaitingLocation = new ConcurrentHashMap<>();

    public static class PendingGeoConfirmation {
        public final UUID orderId;
        public final OrderStatus nextStatus;
        /** message_id сообщения «Мои заказы», чтобы его отредактировать после гео. */
        public final Integer listMessageId;
        /** Для мультиадреса: номер точки (1, 2, …). null = смена статуса всего заказа (один «Вручил»). */
        public final Integer stopNumber;

        public PendingGeoConfirmation(UUID orderId, OrderStatus nextStatus, Integer listMessageId) {
            this(orderId, nextStatus, listMessageId, null);
        }

        public PendingGeoConfirmation(UUID orderId, OrderStatus nextStatus, Integer listMessageId, Integer stopNumber) {
            this.orderId = orderId;
            this.nextStatus = nextStatus;
            this.listMessageId = listMessageId;
            this.stopNumber = stopNumber;
        }
    }

    /** После подтверждения «В магазине» показываем кнопку «В путь», чтобы курьер не забыл перевести статус. */
    public static class PendingOnWay {
        public final UUID orderId;
        public final Integer listMessageId;

        public PendingOnWay(UUID orderId, Integer listMessageId) {
            this.orderId = orderId;
            this.listMessageId = listMessageId;
        }
    }

    private final Map<Long, PendingOnWay> awaitingOnWay = new ConcurrentHashMap<>();

    public boolean isAwaitingLocation(Long telegramId) {
        return awaitingLocation.containsKey(telegramId);
    }

    public boolean isAwaitingOnWay(Long telegramId) {
        return awaitingOnWay.containsKey(telegramId);
    }

    /**
     * Обработка нажатия кнопки «🚗 В путь» после подтверждения гео «В магазине».
     * Переводит заказ в ON_WAY, обновляет список «Мои заказы», показывает меню курьера.
     *
     * @return true если сообщение обработано
     */
    public boolean handleOnWayButton(Long telegramId, Long chatId) {
        PendingOnWay pending = awaitingOnWay.remove(telegramId);
        if (pending == null) {
            return false;
        }
        Optional<Courier> courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty() || !Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
            sendCourierMenuPlain(chatId, "❌ Нет активного профиля курьера.");
            return true;
        }
        boolean updated = orderService.updateOrderStatusByCourier(pending.orderId, courierOpt.get().getUser(), OrderStatus.ON_WAY);
        if (updated && pending.listMessageId != null) {
            bot.editCourierMyOrdersMessage(chatId, pending.listMessageId, telegramId);
        }
        sendCourierMenuPlain(chatId, "✅ В путь. Меню ниже.");
        return true;
    }

    /**
     * Запросить у курьера геолокацию для подтверждения статуса (IN_SHOP или DELIVERED).
     * listMessageId — message_id сообщения «Мои заказы», чтобы после гео отредактировать его, а не слать новое.
     */
    public void requestLocationForStatus(Long telegramId, Long chatId, UUID orderId, OrderStatus nextStatus, Integer listMessageId) {
        awaitingLocation.put(telegramId, new PendingGeoConfirmation(orderId, nextStatus, listMessageId));
        String prompt = nextStatus == OrderStatus.IN_SHOP
                ? "📍 Отправьте геолокацию, чтобы подтвердить, что вы *в магазине*."
                : "📍 Отправьте геолокацию, чтобы подтвердить, что вы *у получателя* (вручили заказ).";
        sendMessageWithLocationKeyboard(chatId, prompt);
    }

    /**
     * Запросить геолокацию для подтверждения доставки в конкретную точку мультиадресного заказа (вариант B).
     */
    public void requestLocationForStopDelivery(Long telegramId, Long chatId, UUID orderId, int stopNumber, Integer listMessageId) {
        awaitingLocation.put(telegramId, new PendingGeoConfirmation(orderId, OrderStatus.DELIVERED, listMessageId, stopNumber));
        String prompt = "📍 Отправьте геолокацию, чтобы подтвердить доставку *в точку " + stopNumber + "*.";
        sendMessageWithLocationKeyboard(chatId, prompt);
    }

    /**
     * Обработать присланную геолокацию.
     * Если курьер в режиме ожидания — сохранить снимок гео, обновить статус заказа, убрать клавиатуру.
     * Иначе — просто обновить последнюю известную точку курьера.
     *
     * @return true если локация обработана в контексте подтверждения статуса (снимок + смена статуса)
     */
    public boolean handleLocation(Long telegramId, Long chatId, double latitude, double longitude) {
        PendingGeoConfirmation pending = awaitingLocation.remove(telegramId);
        log.info("Геолокация от курьера: telegramId={}, pending={}", telegramId, pending != null ? pending.orderId : null);
        if (pending != null) {
            // Сразу убираем кнопку «Отправить геолокацию» и отвечаем (БД может работать долго)
            sendMessageWithKeyboardRemovePlain(chatId, "⏳ Проверяю геолокацию...");
            Optional<Courier> courierOpt = courierService.findByTelegramId(telegramId);
            if (courierOpt.isEmpty() || !Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
                sendCourierMenuPlain(chatId, "❌ Нет активного профиля курьера.");
                return true;
            }
            try {
                courierService.updateLastLocation(telegramId, latitude, longitude);
                // TODO: раскомментировать после тестов — проверка 200 м: курьер в радиусе от магазина (IN_SHOP) или адреса доставки (DELIVERED)
                // double[] ref = getReferenceCoordinatesForPending(pending);
                // if (ref != null && !GeoUtil.isWithinRadiusKm(latitude, longitude, ref[0], ref[1], GeoUtil.RADIUS_200_M_KM)) {
                //     awaitingLocation.put(telegramId, pending);
                //     sendMessageWithLocationKeyboard(chatId,
                //             "❌ Вы слишком далеко от точки (более 200 м). Подойдите ближе и отправьте геолокацию снова.");
                //     return true;
                // }
                // Вариант B: доставка по точке мультиадреса (подтверждаем только эту точку)
                if (pending.stopNumber != null) {
                    courierGeoService.saveSnapshot(pending.orderId, OrderStatus.DELIVERED, latitude, longitude);
                    orderService.markStopDelivered(pending.orderId, pending.stopNumber);
                    if (pending.listMessageId != null) {
                        bot.editCourierMyOrdersMessage(chatId, pending.listMessageId, telegramId);
                    }
                    boolean allDelivered = orderService.findById(pending.orderId)
                            .map(o -> o.getStatus() == OrderStatus.DELIVERED)
                            .orElse(false);
                    if (allDelivered) {
                        sendCourierMenuPlain(chatId, "✅ Заказ полностью доставлен.");
                    } else {
                        sendCourierMenuPlain(chatId, "✅ Доставлено в точку " + pending.stopNumber + ".");
                    }
                    return true;
                }
                // Обычная смена статуса всего заказа (IN_SHOP или один «Вручил»)
                courierGeoService.saveSnapshot(pending.orderId, pending.nextStatus, latitude, longitude);
                boolean updated = orderService.updateOrderStatusByCourier(pending.orderId, courierOpt.get().getUser(), pending.nextStatus);
                if (updated && pending.listMessageId != null) {
                    bot.editCourierMyOrdersMessage(chatId, pending.listMessageId, telegramId);
                }
                if (updated && pending.nextStatus == OrderStatus.IN_SHOP) {
                    awaitingOnWay.put(telegramId, new PendingOnWay(pending.orderId, pending.listMessageId));
                    sendMessageWithOnWayButton(chatId, "✅ Вы в магазине. Нажмите *«🚗 В путь»*, когда поедете к получателю.");
                } else if (updated) {
                    sendCourierMenuPlain(chatId, "✅ Обновлено.");
                } else {
                    sendCourierMenuPlain(chatId, "❌ Не удалось обновить статус заказа. Попробуйте ещё раз из «🚚 Мои заказы».");
                }
            } catch (Exception e) {
                log.error("Ошибка при обработке геолокации для заказа {}: ", pending.orderId, e);
                sendCourierMenuPlain(chatId, "❌ Ошибка при обновлении. Попробуйте ещё раз из «🚚 Мои заказы».");
            }
            return true;
        }
        courierService.updateLastLocation(telegramId, latitude, longitude);
        // Состояние «ожидаю гео» потеряно (рестарт или таймаут) — всё равно отвечаем курьеру и возвращаем меню
        sendCourierMenuPlain(chatId, "📍 Локация сохранена. Чтобы подтвердить «В магазине» или «Вручил» — нажмите нужную кнопку в «🚚 Мои заказы» и снова отправьте геолокацию.");
        return true;
    }

    /**
     * Координаты точки, от которой проверяем радиус 200 м:
     * IN_SHOP — магазин (адрес забора), DELIVERED — адрес доставки (заказа или точки мультиадреса).
     * Возвращает null, если координаты не заданы (проверку не делаем).
     */
    private double[] getReferenceCoordinatesForPending(PendingGeoConfirmation pending) {
        if (pending.nextStatus == OrderStatus.IN_SHOP) {
            return orderService.getOrderWithShop(pending.orderId)
                    .filter(o -> o.getShop() != null && o.getShop().getLatitude() != null && o.getShop().getLongitude() != null)
                    .map(o -> new double[]{o.getShop().getLatitude().doubleValue(), o.getShop().getLongitude().doubleValue()})
                    .orElse(null);
        }
        if (pending.nextStatus == OrderStatus.DELIVERED) {
            Optional<Order> orderOpt = orderService.findById(pending.orderId);
            if (orderOpt.isEmpty()) return null;
            Order order = orderOpt.get();
            if (pending.stopNumber != null) {
                return order.getStops().stream()
                        .filter(s -> pending.stopNumber.equals(s.getStopNumber()))
                        .findFirst()
                        .filter(OrderStop::hasCoordinates)
                        .map(s -> new double[]{s.getDeliveryLatitude().doubleValue(), s.getDeliveryLongitude().doubleValue()})
                        .orElse(null);
            }
            if (order.getDeliveryLatitude() != null && order.getDeliveryLongitude() != null) {
                return new double[]{order.getDeliveryLatitude().doubleValue(), order.getDeliveryLongitude().doubleValue()};
            }
        }
        return null;
    }

    private void sendMessageWithLocationKeyboard(Long chatId, String text) {
        KeyboardButton locationButton = new KeyboardButton("📍 Отправить геолокацию");
        locationButton.setRequestLocation(true);
        KeyboardRow row = new KeyboardRow();
        row.add(locationButton);
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(List.of(row));
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(true);

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build();
        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения с кнопкой геолокации: chatId={}", chatId, e);
        }
    }

    /** Кнопка «В путь» вместо обычного меню — чтобы курьер не забыл перевести статус после «В магазине». */
    private void sendMessageWithOnWayButton(Long chatId, String text) {
        KeyboardRow row = new KeyboardRow();
        row.add("🚗 В путь");
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(List.of(row));
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(keyboard)
                .build();
        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения с кнопкой «В путь»: chatId={}", chatId, e);
        }
    }

    private void sendMessageWithKeyboardRemove(Long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(new ReplyKeyboardRemove(true))
                .build();
        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения: chatId={}", chatId, e);
        }
    }

    /** Отправка без Markdown и с убиранием клавиатуры — чтобы не падать из-за спецсимволов. */
    private void sendMessageWithKeyboardRemovePlain(Long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .replyMarkup(new ReplyKeyboardRemove(true))
                .build();
        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки финального сообщения курьеру: chatId={}", chatId, e);
            try {
                bot.execute(SendMessage.builder().chatId(chatId.toString()).text(text).build());
            } catch (TelegramApiException e2) {
                log.error("Запасная отправка без клавиатуры тоже не удалась: chatId={}", chatId, e2);
            }
        }
    }

    /** Быстрая отправка без Markdown (для «Проверяю геолокацию...»). */
    private void sendMessagePlain(Long chatId, String text) {
        try {
            bot.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения: chatId={}", chatId, e);
        }
    }

    /** Отправить текст курьеру и вернуть меню (📋 Доступные заказы, 🚚 Мои заказы, 💰 Моя статистика). */
    private void sendCourierMenuPlain(Long chatId, String text) {
        bot.sendCourierMenuPlain(chatId, text);
    }

    private void sendMessage(Long chatId, String text) {
        try {
            bot.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .parseMode("Markdown")
                    .build());
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения: chatId={}", chatId, e);
        }
    }
}
