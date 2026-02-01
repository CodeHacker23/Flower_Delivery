package org.example.flower_delivery.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.Bot;
import org.example.flower_delivery.model.RegistrationState;

import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.model.ShopRegistrationData;
import org.example.flower_delivery.service.ShopService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Contact;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Обработчик пошаговой регистрации магазина.
 *
 * Управляет диалогом:
 * 1. /register_shop → спрашиваем название
 * 2. Юзер вводит название → спрашиваем адрес
 * 3. Юзер вводит адрес → спрашиваем телефон
 * 4. Юзер вводит телефон → создаём магазин
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopRegistrationHandler {

    private final ShopService shopService;

    @Autowired
    @Lazy
    private Bot bot;

    /**
     * Хранилище состояний регистрации.
     * Ключ: telegramId пользователя
     * Значение: данные регистрации (текущий шаг + введённые данные)
     *
     * ConcurrentHashMap — потокобезопасный, т.к. Telegram может слать
     * сообщения от разных юзеров одновременно.
     */
    private final Map<Long, ShopRegistrationData> registrationDataMap = new ConcurrentHashMap<>();

    /**
     * Начать регистрацию магазина (команда /register_shop).
     */
    public void startRegistration(Update update) {
        Long chatId = update.getMessage().getChatId();
        Long telegramId = update.getMessage().getFrom().getId();

        log.info("Начало регистрации магазина: telegramId={}", telegramId);

        // Проверяем, нет ли уже магазина у этого юзера
        if (shopService.findByUserTelegramId(telegramId).isPresent()) {
            sendMessage(chatId, "❌ У тебя уже есть зарегистрированный магазин!");
            return;
        }

        // Создаём данные регистрации и ставим первый шаг
        ShopRegistrationData data = new ShopRegistrationData();
        data.setState(RegistrationState.WAITING_SHOP_NAME);
        registrationDataMap.put(telegramId, data);

        sendMessage(chatId, "🏪 *Регистрация магазина*\n\n" +
                "Шаг 1 из 3\n" +
                "Введите *название* вашего магазина:");
    }

    /**
     * Обработать текстовое сообщение от юзера (ответ на вопрос бота).
     *
     * @return true если сообщение обработано (юзер в процессе регистрации),
     *         false если юзер не в процессе регистрации
     */
    public boolean handleMessage(Update update) {
        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText();

        // Проверяем, есть ли юзер в процессе регистрации
        ShopRegistrationData data = registrationDataMap.get(telegramId);
        if (data == null || data.getState() == RegistrationState.NONE) {
            return false; // Юзер не в процессе регистрации
        }

        log.debug("Обработка шага регистрации: telegramId={}, state={}, text={}",
                telegramId, data.getState(), text);

        // Обрабатываем в зависимости от текущего шага
        switch (data.getState()) {
            case WAITING_SHOP_NAME:
                handleShopName(chatId, telegramId, text, data);
                break;
            case WAITING_PICKUP_ADDRESS:
                handlePickupAddress(chatId, telegramId, text, data);
                break;
            case WAITING_PHONE:
                // Ожидаем контакт (кнопку), а не текст — игнорируем текстовые сообщения
                sendMessage(chatId, "👆 Нажми кнопку *\"Поделиться номером телефона\"* внизу экрана!");
                break;
            default:
                return false;
        }

        return true; // Сообщение обработано
    }

    /**
     * Шаг 1: Обработка названия магазина.
     */
    private void handleShopName(Long chatId, Long telegramId, String text, ShopRegistrationData data) {
        // Валидация: название не должно быть пустым или слишком коротким
        if (text.length() < 2) {
            sendMessage(chatId, "❌ Название слишком короткое. Введи минимум 2 символа:");
            return;
        }
        if (text.length() > 255) {
            sendMessage(chatId, "❌ Название слишком длинное. Максимум 255 символов:");
            return;
        }

        // Сохраняем название и переходим к следующему шагу
        data.setShopName(text);
        data.setState(RegistrationState.WAITING_PICKUP_ADDRESS);

        sendMessage(chatId, "✅ Название: *" + text + "*\n\n" +
                "Шаг 2 из 3\n" +
                "Введи *адрес забора* заказов\n" +
                "(откуда курьер будет забирать цветы):");
    }

    /**
     * Шаг 2: Обработка адреса забора.
     */
    private void handlePickupAddress(Long chatId, Long telegramId, String text, ShopRegistrationData data) {
        // Валидация адреса
        if (text.length() < 5) {
            sendMessage(chatId, "❌ Адрес слишком короткий. Введи полный адрес:");
            return;
        }
        if (text.length() > 500) {
            sendMessage(chatId, "❌ Адрес слишком длинный. Максимум 500 символов:");
            return;
        }

        // Сохраняем адрес и переходим к следующему шагу
        data.setPickupAddress(text);
        data.setState(RegistrationState.WAITING_PHONE);

        // Отправляем сообщение с кнопкой "Поделиться контактом"
        sendMessageWithContactButton(chatId, "✅ Адрес: *" + text + "*\n\n" +
                "Шаг 3 из 3\n" +
                "Нажми кнопку ниже, чтобы поделиться номером телефона 👇");
    }

    /**
     * Шаг 3: Обработка контакта (кнопка "Поделиться номером") и завершение регистрации.
     *
     * Вызывается из Bot.java когда приходит update с Contact.
     *
     * @return true если контакт обработан (юзер был на шаге WAITING_PHONE),
     *         false если контакт не ожидался
     */
    public boolean handleContact(Update update) {
        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();

        // Проверяем, есть ли юзер в процессе регистрации на шаге телефона
        ShopRegistrationData data = registrationDataMap.get(telegramId);
        if (data == null || data.getState() != RegistrationState.WAITING_PHONE) {
            return false; // Контакт не ожидался
        }

        // Получаем контакт из сообщения
        Contact contact = update.getMessage().getContact();
        String phone = contact.getPhoneNumber();

        log.info("Получен контакт: telegramId={}, phone={}", telegramId, phone);

        data.setPhone(phone);

        // Создаём магазин через сервис
        try {
            Shop shop = shopService.createShopForUser(
                    telegramId,
                    data.getShopName(),
                    data.getPickupAddress(),
                    data.getPhone()
            );

            log.info("Магазин успешно создан: shopId={}, telegramId={}", shop.getId(), telegramId);

            // Очищаем данные регистрации
            registrationDataMap.remove(telegramId);

            // Убираем Reply-клавиатуру и показываем сообщение об ожидании активации
            sendMessageWithKeyboardRemove(chatId, "🎉 *Магазин успешно зарегистрирован!*\n\n" +
                    "📋 *Данные:*\n" +
                    "• Название: " + data.getShopName() + "\n" +
                    "• Адрес забора: " + data.getPickupAddress() + "\n" +
                    "• Телефон: " + phone + "\n\n" +
                    "⏳ *Ожидай активации администратором.*\n" +
                    "После активации ты сможешь создавать заказы!");

        } catch (Exception e) {
            log.error("Ошибка создания магазина: telegramId={}", telegramId, e);
            registrationDataMap.remove(telegramId);
            sendMessageWithKeyboardRemove(chatId, "❌ Ошибка при создании магазина: " + e.getMessage());
        }

        return true;
    }

    /**
     * Начать регистрацию магазина (вызов из CallbackQueryHandler).
     * 
     * Используется когда юзер нажал кнопку "Магазин" после /start.
     */
    public void startRegistrationFromCallback(Long telegramId, Long chatId) {
        log.info("Начало регистрации магазина (из callback): telegramId={}", telegramId);

        // Проверяем, нет ли уже магазина у этого юзера
        if (shopService.findByUserTelegramId(telegramId).isPresent()) {
            sendMessage(chatId, "❌ У тебя уже есть зарегистрированный магазин!");
            return;
        }

        // Создаём данные регистрации и ставим первый шаг
        ShopRegistrationData data = new ShopRegistrationData();
        data.setState(RegistrationState.WAITING_SHOP_NAME);
        registrationDataMap.put(telegramId, data);

        sendMessage(chatId, "🏪 *Регистрация магазина*\n\n" +
                "Шаг 1 из 3\n" +
                "Введи *название* твоего магазина:");
    }

    /**
     * Проверить, находится ли юзер в процессе регистрации.
     */
    public boolean isUserInRegistration(Long telegramId) {
        ShopRegistrationData data = registrationDataMap.get(telegramId);
        return data != null && data.getState() != RegistrationState.NONE;
    }

    /**
     * Отменить регистрацию (если юзер передумал).
     */
    public void cancelRegistration(Long telegramId, Long chatId) {
        registrationDataMap.remove(telegramId);
        sendMessage(chatId, "❌ Регистрация отменена.");
    }

    /**
     * Отправить сообщение с кнопкой "Поделиться контактом".
     *
     * ReplyKeyboardMarkup — это клавиатура ВМЕСТО обычной (внизу экрана).
     * KeyboardButton с requestContact=true — запрашивает у Telegram контакт юзера.
     */
    private void sendMessageWithContactButton(Long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setParseMode("Markdown");

        // Создаём кнопку "Поделиться контактом"
        KeyboardButton contactButton = new KeyboardButton("📱 Поделиться номером телефона");
        contactButton.setRequestContact(true);  // Магия! Telegram сам попросит номер

        // Создаём ряд с кнопкой
        KeyboardRow row = new KeyboardRow();
        row.add(contactButton);

        // Создаём клавиатуру
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(List.of(row));
        keyboard.setResizeKeyboard(true);   // Подогнать размер под текст кнопки
        keyboard.setOneTimeKeyboard(true);  // Скрыть после нажатия

        message.setReplyMarkup(keyboard);

        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения с кнопкой контакта: chatId={}", chatId, e);
        }
    }

    /**
     * Отправить сообщение и убрать ReplyKeyboard.
     *
     * ReplyKeyboardRemove — убирает кастомную клавиатуру,
     * возвращает обычную клавиатуру телефона.
     */
    private void sendMessageWithKeyboardRemove(Long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setParseMode("Markdown");
        message.setReplyMarkup(new ReplyKeyboardRemove(true));  // Убираем клавиатуру

        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения: chatId={}", chatId, e);
        }
    }

    /**
     * Отправить простое текстовое сообщение.
     */
    private void sendMessage(Long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setParseMode("Markdown");

        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения: chatId={}", chatId, e);
        }
    }

    /**
     * Отправить сообщение с кнопкой "Создать заказ".
     */
    private void sendMessageWithCreateOrderButton(Long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setParseMode("Markdown");

        // Создаём inline-кнопку "Создать заказ"
        InlineKeyboardButton createOrderButton = InlineKeyboardButton.builder()
                .text("📦 Создать заказ")
                .callbackData("create_order")
                .build();

        // Создаём клавиатуру
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(createOrderButton))
                .build();

        message.setReplyMarkup(keyboard);

        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения с кнопкой: chatId={}", chatId, e);
        }
    }
}





