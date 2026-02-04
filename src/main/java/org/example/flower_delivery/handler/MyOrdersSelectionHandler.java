package org.example.flower_delivery.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.Bot;
import org.example.flower_delivery.model.Order;
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
 * Хендлер для выбора конкретного заказа из списка \"Мои заказы\".
 *
 * Логика:
 * 1. Bot формирует список (максимум N заказов) и передаёт сюда список ID.
 * 2. Мы сохраняем список в памяти по telegramId.
 * 3. При нажатии кнопки \"Выбрать заказ\" просим ввести номер из списка или ID.
 * 4. Следующее текстовое сообщение интерпретируем как выбор заказа и показываем по нему меню.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MyOrdersSelectionHandler {

    private final OrderService orderService;

    @Autowired
    @Lazy
    private Bot bot;

    /** Последний показанный список заказов по пользователю (только ID, чтобы не держать целые сущности). */
    private final Map<Long, List<UUID>> lastOrderIdsByUser = new ConcurrentHashMap<>();

    /** telegramId'ы, от которых мы сейчас ждём номер/ID заказа. */
    private final Map<Long, Boolean> awaitingSelection = new ConcurrentHashMap<>();

    /** Сохранить список последних заказов для пользователя. */
    public void saveLastOrders(Long telegramId, List<Order> orders) {
        List<UUID> ids = new ArrayList<>();
        for (Order order : orders) {
            ids.add(order.getId());
        }
        lastOrderIdsByUser.put(telegramId, ids);
        log.debug("Сохранён список {} заказов для пользователя {}", ids.size(), telegramId);
    }

    /** Начать процесс выбора заказа (после нажатия inline-кнопки). */
    public void startSelection(Long telegramId, Long chatId) {
        List<UUID> ids = lastOrderIdsByUser.get(telegramId);
        if (ids == null || ids.isEmpty()) {
            send(chatId, "❌ Нет сохранённого списка заказов.\nНажми ещё раз «📋 Мои заказы».");
            return;
        }
        awaitingSelection.put(telegramId, true);
        send(chatId, "🔎 *Выбор заказа*\n\n" +
                "Введи *номер* заказа из списка (1-" + ids.size() + ") или *ID заказа* целиком.");
    }

    public boolean isAwaitingSelection(Long telegramId) {
        return awaitingSelection.getOrDefault(telegramId, false);
    }

    /**
     * Обработать текст пользователя как выбор заказа.
     *
     * @return true если сообщение обработано (мы занимались выбором заказа)
     */
    public boolean handleText(Long telegramId, Long chatId, String text) {
        if (!isAwaitingSelection(telegramId)) {
            return false;
        }

        awaitingSelection.remove(telegramId);

        List<UUID> ids = lastOrderIdsByUser.get(telegramId);
        if (ids == null || ids.isEmpty()) {
            send(chatId, "❌ Список заказов устарел.\nНажми ещё раз «📋 Мои заказы».");
            return true;
        }

        UUID orderId = null;

        // Пытаемся сначала считать номер из списка (1..N)
        try {
            int index = Integer.parseInt(text.trim());
            if (index >= 1 && index <= ids.size()) {
                orderId = ids.get(index - 1);
            }
        } catch (NumberFormatException ignored) {
            // Не число — пробуем как UUID
        }

        // Если номер не подошёл — пробуем UUID
        if (orderId == null) {
            try {
                orderId = UUID.fromString(text.trim());
            } catch (IllegalArgumentException e) {
                send(chatId, "❌ Не удалось распознать номер или ID заказа.\n" +
                        "Введи число от 1 до " + ids.size() + " или корректный UUID.");
                return true;
            }
        }

        var orderOpt = orderService.findById(orderId);
        if (orderOpt.isEmpty()) {
            send(chatId, "❌ Заказ с таким ID не найден.\nПопробуй снова через «📋 Мои заказы».");
            return true;
        }

        Order order = orderOpt.get();
        showOrderActions(chatId, order);
        return true;
    }

    /** Показать краткую карточку заказа с кнопками Редактировать / Отменить. */
    private void showOrderActions(Long chatId, Order order) {
        StringBuilder sb = new StringBuilder();
        sb.append("📦 *Заказ*\n\n");
        sb.append("ID: `").append(order.getId()).append("`\n");
        sb.append("Статус: ").append(order.getStatus().getDisplayName()).append("\n");
        sb.append("Адрес: ").append(order.getDeliveryAddress()).append("\n");
        sb.append("Сумма доставки: ").append(order.getDeliveryPrice()).append("₽\n");

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        if (order.getStatus() == org.example.flower_delivery.model.OrderStatus.NEW) {
            InlineKeyboardButton editBtn = InlineKeyboardButton.builder()
                    .text("✏️ Редактировать")
                    .callbackData("order_edit_" + order.getId())
                    .build();
            InlineKeyboardButton cancelBtn = InlineKeyboardButton.builder()
                    .text("❌ Отменить")
                    .callbackData("order_cancel_" + order.getId())
                    .build();
            rows.add(List.of(editBtn, cancelBtn));
        }

        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(sb.toString());
        msg.setParseMode("Markdown");
        if (!rows.isEmpty()) {
            msg.setReplyMarkup(new InlineKeyboardMarkup(rows));
        }
        try {
            bot.execute(msg);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки карточки заказа: chatId={}", chatId, e);
        }
    }

    private void send(Long chatId, String text) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(text);
        msg.setParseMode("Markdown");
        try {
            bot.execute(msg);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения: chatId={}", chatId, e);
        }
    }
}

