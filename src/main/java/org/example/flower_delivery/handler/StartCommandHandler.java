package org.example.flower_delivery.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.Bot;
import org.example.flower_delivery.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;


@Slf4j
@Component
@RequiredArgsConstructor
public class StartCommandHandler {
    // Spring автоматически найдет UserService и подставит сюда (Dependency Injection)
    private final UserService userService;

    // Spring автоматически найдет Bot и подставит сюда (Dependency Injection)
    // @Lazy - создаёт прокси для Bot, разрывая циклическую зависимость:
    // Bot → StartCommandHandler → Bot (без @Lazy был бы цикл!)
    // @Autowired на поле (не через конструктор, потому что @Lazy не работает с final полями в @RequiredArgsConstructor)
    @Autowired
    @Lazy
    private Bot bot;

    /**
     * Обработать команду /start
     *
     * @param update - объект Update от Telegram с информацией о команде
     */
    public void handle(Update update) {
        // Проверяем, что в Update есть сообщение
        if (!update.hasMessage()) {
            log.warn("Update не содержит сообщения: {}", update);
            return;
        }

        Message message = update.getMessage();

        // Проверяем, что сообщение содержит текст
        if (!message.hasText()) {
            log.warn("Сообщение не содержит текста: {}", message);
            return;
        }

        // Проверяем, что это команда /start
        if (!message.getText().equals("/start")) {
            log.warn("Получена команда не /start: {}", message.getText());
            return;
        }

        // Извлекаем данные пользователя
        Long telegramId = message.getFrom().getId();
        String firstName = message.getFrom().getFirstName();
        String lastName = message.getFrom().getLastName();

        // Формируем полное имя (если lastName null, используем только firstName)
        String fullName = lastName != null
                ? firstName + " " + lastName
                : firstName;

        log.info("Обработка команды /start от пользователя: telegramId={}, fullName={}",
                telegramId, fullName);

        // Получаем ID чата (куда отправлять ответ)
        Long chatId = message.getChatId();

        // Пытаемся зарегистрировать пользователя
        try {
            // Если пользователь уже зарегистрирован - registerUser выбросит IllegalArgumentException
            userService.registerUser(telegramId, fullName);

            // Если дошли сюда - пользователь успешно зарегистрирован
            log.info("Пользователь успешно зарегистрирован: telegramId={}", telegramId);

            // Отправляем приветственное сообщение
            sendWelcomeMessage(chatId, fullName, true);  // true = новый пользователь

        } catch (IllegalArgumentException e) {
            // Пользователь уже зарегистрирован - не проблема, просто отправляем приветствие
            log.debug("Пользователь уже зарегистрирован: telegramId={}", telegramId);
            sendWelcomeMessage(chatId, fullName, false);  // false = существующий пользователь

        } catch (Exception e) {
            // Серьёзная ошибка - логируем и отправляем сообщение об ошибке
            log.error("Ошибка при регистрации пользователя: telegramId={}", telegramId, e);
            sendErrorMessage(chatId);
        }
    }
    // что то хз
    /**
     * Отправить приветственное сообщение с кнопками выбора роли
     *
     * @param chatId - ID чата (куда отправить)
     * @param fullName - полное имя пользователя
     * @param isNewUser - true если пользователь новый, false если уже зарегистрирован
     */
    private void sendWelcomeMessage(Long chatId, String fullName, boolean isNewUser) {
        String text;

        if (isNewUser) {
            text = String.format(
                    "Привет, %s! 👋\n\n" +
                            "Добро пожаловать в Flower Delivery Bot!\n\n" +
                            "Выберите свою роль: ",
                    fullName
            );
        } else {
            // Проверяем, есть ли уже роль у пользователя
            Long telegramId = null;
            try {
                // Получаем telegramId из контекста (нужно будет передавать)
                // Пока что просто отправляем сообщение с кнопками
                text = String.format(
                        "Привет, %s! 👋\n\n" +
                                "Ты уже зарегистрирован в системе.\n\n" +
                                "Выбери свою роль:",
                        fullName
                );
            } catch (Exception e) {
                text = String.format(
                        "Привет, %s! 👋\n\n" +
                                "Выбери свою роль:",
                        fullName
                );
            }
        }

        try {
            // Создаем клавиатуру с кнопками
            InlineKeyboardMarkup keyboardMarkup = createRoleSelectionKeyboard();

            SendMessage message = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .replyMarkup(keyboardMarkup)  // Прикрепляем клавиатуру к сообщению
                    .build();

            bot.execute(message);
            log.info("Приветственное сообщение с кнопками отправлено: chatId={}", chatId);

        } catch (TelegramApiException e) {
            log.error("Ошибка при отправке приветственного сообщения: chatId={}", chatId, e);
        }
    }

    /**
     * Создать клавиатуру с кнопками выбора роли
     *
     * @return InlineKeyboardMarkup - клавиатура с кнопками "Магазин" и "Курьер"
     */
    private InlineKeyboardMarkup createRoleSelectionKeyboard() {
        // Создаем клавиатуру (это как контейнер для кнопок)
        InlineKeyboardMarkup keyboardMarkup = new InlineKeyboardMarkup();

        // Список строк кнопок (каждая строка - это List<InlineKeyboardButton>)
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        // Первая строка: кнопка "Магазин"
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton shopButton = new InlineKeyboardButton();
        shopButton.setText(" \uD83D\uDC90 Магазин");
        shopButton.setCallbackData("role_shop");  // Это данные которые вернутся при нажатии
        row1.add(shopButton);

        // Вторая строка: кнопка "Курьер"
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton courierButton = new InlineKeyboardButton();
        courierButton.setText("\uD83D\uDE97 Курьер");
        courierButton.setCallbackData("role_courier");  // Это данные которые вернутся при нажатии
        row2.add(courierButton);

        // Добавляем строки в клавиатуру
        keyboard.add(row1);
        keyboard.add(row2);

        // Устанавливаем клавиатуру
        keyboardMarkup.setKeyboard(keyboard);

        return keyboardMarkup;
    }

    /**
     * Отправить сообщение об ошибке
     *
     * @param chatId - ID чата (куда отправить)
     */
    private void sendErrorMessage(Long chatId) {
        String text = "❌ Произошла ошибка при регистрации.\n\n" +
                "Попробуй позже или свяжись с администратором.";

        try {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .build();

            bot.execute(message);
            log.info("Сообщение об ошибке отправлено: chatId={}", chatId);

        } catch (TelegramApiException e) {
            log.error("Ошибка при отправке сообщения об ошибке: chatId={}", chatId, e);
        }
    }


}
