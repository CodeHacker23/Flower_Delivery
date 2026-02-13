package org.example.flower_delivery.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.Bot;
import org.example.flower_delivery.model.Courier;
import org.example.flower_delivery.model.CourierRegistrationData;
import org.example.flower_delivery.model.CourierRegistrationState;
import org.example.flower_delivery.service.CourierService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Contact;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Пошаговая регистрация курьера.
 *
 * Логика простая:
 * 1) Пользователь выбирает роль "Курьер"
 * 2) Мы просим отправить контакт (номер телефона)
 * 3) Сохраняем курьера в БД со статусом PENDING
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourierRegistrationHandler {

    private final CourierService courierService;

    @Autowired
    @Lazy
    private Bot bot;

    /**
     * Временные данные регистрации курьера.
     * Ключ: telegramId курьера.
     */
    private final Map<Long, CourierRegistrationData> registrationDataMap = new ConcurrentHashMap<>();

    /**
     * Запустить регистрацию курьера (вызывается из CallbackQueryHandler,
     * когда пользователь нажал кнопку "Курьер").
     */
    public void startRegistrationFromCallback(Long telegramId, Long chatId, String ignoredFullName) {
        log.info("Начало регистрации курьера: telegramId={}", telegramId);

        // Проверяем, не зарегистрирован ли курьер уже
        if (courierService.findByTelegramId(telegramId).isPresent()) {
            sendSimpleMessage(chatId, "❌ Ты уже зарегистрирован как курьер.");
            return;
        }

        // Создаём данные регистрации и ставим первый шаг — ждём ФИО
        CourierRegistrationData data = new CourierRegistrationData();
        data.setState(CourierRegistrationState.WAITING_FULL_NAME);
        registrationDataMap.put(telegramId, data);

        // Спрашиваем имя и фамилию
        sendSimpleMessage(chatId,
                "🚴 *Регистрация курьера*\n\n" +
                        "Шаг 1 из 3\n" +
                        "Напиши, пожалуйста, своё *имя и фамилию*.\n\n" +
                        "Пример: `Иван Петров`");
    }

    /**
     * Обработка текстовых сообщений во время регистрации курьера.
     *
     * @return true если сообщение относится к регистрации курьера.
     */
    public boolean handleText(Update update) {
        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText();

        CourierRegistrationData data = registrationDataMap.get(telegramId);
        if (data == null || data.getState() == CourierRegistrationState.NONE) {
            return false;
        }

        if (data.getState() == CourierRegistrationState.WAITING_FULL_NAME) {
            // Валидация имени
            if (text.length() < 3) {
                sendSimpleMessage(chatId, "❌ Имя слишком короткое. Введи имя и фамилию полностью:");
                return true;
            }
            if (text.length() > 255) {
                sendSimpleMessage(chatId, "❌ Имя слишком длинное. Максимум 255 символов.");
                return true;
            }

            data.setFullName(text);
            data.setState(CourierRegistrationState.WAITING_PHONE);

            // Просим номер телефона через кнопку контакта
            sendMessageWithContactButton(chatId,
                    "✅ Имя: *" + text + "*\n\n" +
                            "Шаг 2 из 3\n" +
                            "Теперь нажми кнопку ниже и поделись своим *номером телефона*.\n\n" +
                            "Этот номер будут видеть магазин и получатель.");
            return true;
        }

        if (data.getState() == CourierRegistrationState.WAITING_PHONE) {
            // Мы ждём контакт, а не текст
            sendSimpleMessage(chatId,
                    "👆 Сейчас нажми кнопку *\"Поделиться номером телефона\"* внизу экрана.");
            return true;
        }

        if (data.getState() == CourierRegistrationState.WAITING_PASSPORT_PHOTO) {
            sendSimpleMessage(chatId,
                    "📸 Осталось отправить *селфи с паспортом* как фото.\n" +
                            "Просто прикрепи фото и отправь его сюда.");
            return true;
        }

        return false;
    }

    /**
     * Обрабатываем контакт от пользователя.
     *
     * @return true если контакт был частью регистрации курьера
     */
    public boolean handleContact(Update update) {
        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();

        CourierRegistrationData data = registrationDataMap.get(telegramId);
        if (data == null || data.getState() != CourierRegistrationState.WAITING_PHONE) {
            return false;
        }

        Contact contact = update.getMessage().getContact();
        String phone = contact.getPhoneNumber();

        log.info("Регистрация курьера: получен телефон telegramId={}, phone={}", telegramId, phone);

        data.setPhone(phone);
        data.setState(CourierRegistrationState.WAITING_PASSPORT_PHOTO);

        try {
            // Убираем клавиатуру и просим селфи с паспортом
            sendMessageWithKeyboardRemove(chatId,
                    "✅ Телефон сохранён: *" + phone + "*\n\n" +
                            "Шаг 3 из 3\n" +
                            "Теперь отправь, пожалуйста, *селфи с паспортом*.\n" +
                            "Просто сделай фото, где видно тебя и разворот паспорта, и пришли сюда как обычное фото.");

        } catch (Exception e) {
            log.error("Ошибка регистрации курьера: telegramId={}", telegramId, e);
            sendMessageWithKeyboardRemove(chatId,
                    "❌ Ошибка при регистрации курьера: " + e.getMessage());
        }

        return true;
    }

    /**
     * Обработка фото (селфи с паспортом).
     */
    public boolean handlePhoto(Update update) {
        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();

        CourierRegistrationData data = registrationDataMap.get(telegramId);
        if (data == null || data.getState() != CourierRegistrationState.WAITING_PASSPORT_PHOTO) {
            return false;
        }

        if (update.getMessage().getPhoto() == null || update.getMessage().getPhoto().isEmpty()) {
            sendSimpleMessage(chatId, "❌ Не вижу фото. Пришли, пожалуйста, именно *фото*, не файл.");
            return true;
        }

        // Берём самое "большое" фото из списка (последний элемент)
        var photos = update.getMessage().getPhoto();
        String fileId = photos.get(photos.size() - 1).getFileId();

        data.setPassportPhotoFileId(fileId);

        try {
            Courier courier = courierService.registerCourier(
                    telegramId,
                    data.getFullName(),
                    data.getPhone(),
                    data.getPassportPhotoFileId()
            );
            log.info("Курьер успешно создан: courierId={}, telegramId={}",
                    courier.getId(), telegramId);

            // Чистим временные данные
            registrationDataMap.remove(telegramId);

            sendSimpleMessage(chatId,
                    "🎉 *Регистрация курьера завершена!*\n\n" +
                            "👤 Имя: " + courier.getFullName() + "\n" +
                            "📱 Телефон: " + courier.getPhone() + "\n\n" +
                            "⏳ Сейчас твой профиль ждёт *активации администратором*.\n" +
                            "После активации ты сможешь брать заказы.");

        } catch (Exception e) {
            log.error("Ошибка завершения регистрации курьера: telegramId={}", telegramId, e);
            registrationDataMap.remove(telegramId);
            sendSimpleMessage(chatId,
                    "❌ Ошибка при сохранении данных курьера: " + e.getMessage());
        }

        return true;
    }

    /**
     * Проверить, ждём ли мы сейчас телефон от этого юзера.
     */
    public boolean isWaitingForPhone(Long telegramId) {
        CourierRegistrationData data = registrationDataMap.get(telegramId);
        return data != null && data.getState() == CourierRegistrationState.WAITING_PHONE;
    }

    // ======= ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ОТПРАВКИ СООБЩЕНИЙ =======

    private void sendMessageWithContactButton(Long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setParseMode("Markdown");

        // Кнопка "Поделиться номером телефона"
        KeyboardButton contactButton = new KeyboardButton("📱 Поделиться номером телефона");
        contactButton.setRequestContact(true);

        KeyboardRow row = new KeyboardRow();
        row.add(contactButton);

        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(List.of(row));
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(true);

        message.setReplyMarkup(keyboard);

        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения с кнопкой контакта: chatId={}", chatId, e);
        }
    }

    private void sendMessageWithKeyboardRemove(Long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setParseMode("Markdown");
        message.setReplyMarkup(new ReplyKeyboardRemove(true));

        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения: chatId={}", chatId, e);
        }
    }

    private void sendSimpleMessage(Long chatId, String text) {
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
}