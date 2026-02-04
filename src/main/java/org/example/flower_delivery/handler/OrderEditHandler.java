package org.example.flower_delivery.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.Bot;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.OrderEditState;
import org.example.flower_delivery.model.OrderStop;
import org.example.flower_delivery.service.OrderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Обработчик редактирования заказа: меню (точка → поле), ввод нового значения, сохранение.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEditHandler {

    private final OrderService orderService;

    @Autowired
    @Lazy
    private Bot bot;

    /** Состояние редактирования по telegramId: ждём текстовое сообщение с новым значением */
    private final Map<Long, OrderEditState> editStateMap = new ConcurrentHashMap<>();

    private static final String PREFIX_EDIT = "order_edit_";
    private static final String SUF_STOP = "_stop_";
    private static final String SUF_ADDRESS = "_address";
    private static final String SUF_PHONE = "_phone";
    private static final String SUF_COMMENT = "_comment";
    private static final String SUF_DATE = "_date";
    private static final String SUF_DATE_TODAY = "_date_today";
    private static final String SUF_DATE_TOMORROW = "_date_tomorrow";

    public boolean isEditing(Long telegramId) {
        return editStateMap.containsKey(telegramId);
    }

    /**
     * Обработка callback: открыть меню редактирования заказа.
     * callbackData = order_edit_{orderId}
     */
    public void handleEditMenu(Long telegramId, Long chatId, String callbackData) {
        String orderIdStr = callbackData.substring(PREFIX_EDIT.length());
        UUID orderId = parseUuid(orderIdStr);
        if (orderId == null) {
            send(chatId, "❌ Ошибка: неверный ID заказа.");
            return;
        }
        Optional<Order> opt = orderService.findById(orderId);
        if (opt.isEmpty()) {
            send(chatId, "❌ Заказ не найден.");
            return;
        }
        List<OrderStop> stops = orderService.getOrderStops(orderId);

        if (stops.size() > 1) {
            // Мультиадрес: "Какую точку редактировать?"
            sendChoicePoint(chatId, orderId, stops);
        } else {
            // Одна точка: сразу "Что изменить?"
            sendChoiceField(chatId, orderId, 1);
        }
    }

    /**
     * Обработка callback: выбрана точка — показать поля для этой точки.
     * callbackData = order_edit_{orderId}_stop_{N}
     */
    public void handleSelectPoint(Long telegramId, Long chatId, String callbackData) {
        // order_edit_UUID_stop_N
        String rest = callbackData.substring(PREFIX_EDIT.length());
        int idx = rest.indexOf(SUF_STOP);
        if (idx < 0) return;
        UUID orderId = parseUuid(rest.substring(0, idx));
        if (orderId == null) return;
        String numPart = rest.substring(idx + SUF_STOP.length());
        int stopNum;
        try {
            stopNum = Integer.parseInt(numPart);
        } catch (NumberFormatException e) {
            return;
        }
        sendChoiceField(chatId, orderId, stopNum);
    }

    /**
     * Обработка callback: выбрано поле — запомнить состояние и попросить ввести значение.
     * callbackData = order_edit_{orderId}_stop_{N}_address | _phone | _comment
     */
    public void handleSelectField(Long telegramId, Long chatId, String callbackData) {
        // order_edit_UUID_stop_N_field
        String rest = callbackData.substring(PREFIX_EDIT.length());
        int stopIdx = rest.indexOf(SUF_STOP);
        if (stopIdx < 0) return;
        UUID orderId = parseUuid(rest.substring(0, stopIdx));
        if (orderId == null) return;
        String afterStop = rest.substring(stopIdx + SUF_STOP.length());
        int under = afterStop.indexOf('_');
        if (under < 0) return;
        int stopNum;
        try {
            stopNum = Integer.parseInt(afterStop.substring(0, under));
        } catch (NumberFormatException e) {
            return;
        }
        String field = afterStop.substring(under + 1);

        OrderEditState state = new OrderEditState();
        state.setOrderId(orderId);
        state.setStopNumber(stopNum);
        state.setField(field);
        editStateMap.put(telegramId, state);

        String prompt;
        if ("address".equals(field)) {
            prompt = "✏️ Введите *новый адрес доставки* для точки " + stopNum + ":";
        } else if ("phone".equals(field)) {
            prompt = "✏️ Введите *новый телефон* для точки " + stopNum + ":";
        } else if ("comment".equals(field)) {
            prompt = "✏️ Введите *новый комментарий* для точки " + stopNum + ":\n_Или отправьте /skip чтобы очистить_";
        } else {
            editStateMap.remove(telegramId);
            send(chatId, "❌ Неизвестное поле.");
            return;
        }
        send(chatId, prompt);
    }

    /**
     * Обработка callback: показать выбор даты (сегодня/завтра).
     * callbackData = order_edit_{orderId}_date
     */
    public void handleEditDateMenu(Long telegramId, Long chatId, String callbackData) {
        // callbackData = order_edit_UUID_date  -> нужен UUID
        String rest = callbackData.substring(PREFIX_EDIT.length());
        if (rest.endsWith("_date")) rest = rest.substring(0, rest.length() - 5);
        UUID orderId = parseUuid(rest);
        if (orderId == null) {
            send(chatId, "❌ Ошибка: неверный ID заказа.");
            return;
        }
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        DateTimeFormatter f = DateTimeFormatter.ofPattern("dd.MM");
        InlineKeyboardButton btnToday = InlineKeyboardButton.builder()
                .text("📅 Сегодня (" + today.format(f) + ")")
                .callbackData("order_edit_" + orderId + SUF_DATE_TODAY)
                .build();
        InlineKeyboardButton btnTomorrow = InlineKeyboardButton.builder()
                .text("📅 Завтра (" + tomorrow.format(f) + ")")
                .callbackData("order_edit_" + orderId + SUF_DATE_TOMORROW)
                .build();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(btnToday, btnTomorrow)));
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText("📅 Выберите *новую дату доставки*:");
        msg.setParseMode("Markdown");
        msg.setReplyMarkup(markup);
        execute(chatId, msg);
    }

    /**
     * Обработка callback: дата выбрана (сегодня или завтра).
     */
    public void handleDateSelected(Long telegramId, Long chatId, String callbackData) {
        boolean isToday = callbackData.endsWith(SUF_DATE_TODAY);
        String rest = callbackData.substring(PREFIX_EDIT.length());
        rest = isToday ? rest.replace(SUF_DATE_TODAY, "") : rest.replace(SUF_DATE_TOMORROW, "");
        UUID orderId = parseUuid(rest);
        if (orderId == null) {
            send(chatId, "❌ Ошибка: неверный ID заказа.");
            return;
        }
        LocalDate newDate = isToday ? LocalDate.now() : LocalDate.now().plusDays(1);
        boolean ok = orderService.updateOrderDeliveryDate(orderId, newDate);
        if (ok) {
            send(chatId, "✅ Дата доставки изменена на *" + newDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + "*.");
        } else {
            send(chatId, "❌ Не удалось изменить дату.");
        }
    }

    /**
     * Обработка текстового сообщения: новое значение поля (адрес/телефон/комментарий).
     * Вызывается из Bot, когда isEditing(telegramId) == true.
     *
     * @return true если сообщение обработано (редактирование), false иначе
     */
    public boolean handleText(Long telegramId, Long chatId, String text) {
        OrderEditState state = editStateMap.get(telegramId);
        if (state == null) return false;

        editStateMap.remove(telegramId);

        if ("/skip".equals(text) && "comment".equals(state.getField())) {
            text = "";
        }

        String field = state.getField();
        boolean ok;
        if ("address".equals(field)) {
            ok = orderService.updateStopAddress(state.getOrderId(), state.getStopNumber(), text);
        } else if ("phone".equals(field)) {
            ok = orderService.updateStopPhone(state.getOrderId(), state.getStopNumber(), text);
        } else if ("comment".equals(field)) {
            ok = orderService.updateStopComment(state.getOrderId(), state.getStopNumber(), text);
        } else {
            send(chatId, "❌ Неизвестное поле.");
            return true;
        }

        if (ok) {
            String label = "address".equals(field) ? "Адрес" : "phone".equals(field) ? "Телефон" : "Комментарий";
            send(chatId, "✅ *" + label + "* обновлён.\n\nНажми «📋 Мои заказы», чтобы увидеть изменения.");
        } else {
            send(chatId, "❌ Не удалось сохранить изменение.");
        }
        return true;
    }

    private void sendChoicePoint(Long chatId, UUID orderId, List<OrderStop> stops) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (OrderStop stop : stops) {
            InlineKeyboardButton btn = InlineKeyboardButton.builder()
                    .text("📍 Точка " + stop.getStopNumber() + ": " + stop.getRecipientName())
                    .callbackData("order_edit_" + orderId + SUF_STOP + stop.getStopNumber())
                    .build();
            rows.add(List.of(btn));
        }
        InlineKeyboardButton btnDate = InlineKeyboardButton.builder()
                .text("📅 Дата доставки")
                .callbackData("order_edit_" + orderId + SUF_DATE)
                .build();
        rows.add(List.of(btnDate));
        sendWithKeyboard(chatId, "✏️ *Какую точку редактировать?*", new InlineKeyboardMarkup(rows));
    }

    private void sendChoiceField(Long chatId, UUID orderId, int stopNumber) {
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        row1.add(InlineKeyboardButton.builder().text("📍 Адрес").callbackData("order_edit_" + orderId + SUF_STOP + stopNumber + SUF_ADDRESS).build());
        row1.add(InlineKeyboardButton.builder().text("📞 Телефон").callbackData("order_edit_" + orderId + SUF_STOP + stopNumber + SUF_PHONE).build());
        row1.add(InlineKeyboardButton.builder().text("💬 Комментарий").callbackData("order_edit_" + orderId + SUF_STOP + stopNumber + SUF_COMMENT).build());
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        rows.add(row1);
        if (stopNumber == 1) {
            rows.add(List.of(InlineKeyboardButton.builder().text("📅 Дата доставки").callbackData("order_edit_" + orderId + SUF_DATE).build()));
        }
        String title = stopNumber == 1 ? "✏️ *Что изменить?*" : "✏️ *Что изменить в точке " + stopNumber + "?*";
        sendWithKeyboard(chatId, title, new InlineKeyboardMarkup(rows));
    }

    private void send(Long chatId, String text) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(text);
        msg.setParseMode("Markdown");
        execute(chatId, msg);
    }

    private void sendWithKeyboard(Long chatId, String text, InlineKeyboardMarkup markup) {
        SendMessage msg = new SendMessage();
        msg.setChatId(chatId.toString());
        msg.setText(text);
        msg.setParseMode("Markdown");
        msg.setReplyMarkup(markup);
        execute(chatId, msg);
    }

    private void execute(Long chatId, SendMessage msg) {
        try {
            bot.execute(msg);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки: chatId={}", chatId, e);
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
