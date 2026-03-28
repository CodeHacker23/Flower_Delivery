package org.example.flower_delivery.service;

import lombok.RequiredArgsConstructor;
import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.telegram.TelegramSender;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.List;

/**
 * Отправка Reply-клавиатур меню магазина и курьера (кнопки внизу экрана).
 * Фаза 8 рефакторинга — вынесено из Bot (см. docs/REFACTORING_STATUS.md).
 */
@Service
@RequiredArgsConstructor
public class MenuKeyboardService {

    private final TelegramSender telegramSender;

    /**
     * Показать меню магазина: «Создать заказ», «Мои заказы», «Мой магазин», «Информация».
     *
     * @param chatId     чат, куда шлём меню.
     * @param shop       магазин (для контекста; сейчас не используется в теле, но может понадобиться для персонального текста).
     * @param headerText текст над кнопками (например «Магазин активирован» или приветствие).
     */
    public void sendShopMenu(Long chatId, Shop shop, String headerText) {
        KeyboardRow row1 = new KeyboardRow();
        row1.add("📦 Создать заказ");
        row1.add("📋 Мои заказы");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("🏪 Мой магазин");
        row2.add("ℹ️ Информация");

        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(List.of(row1, row2));
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);
        telegramSender.sendMessageWithReplyKeyboard(chatId, headerText, "Markdown", keyboard);
    }

    /**
     * Показать меню курьера: «Доступные заказы», «Мои заказы», «Моя статистика», «Информация».
     * Текст с разметкой Markdown.
     *
     * @param chatId     чат.
     * @param headerText текст над кнопками (например «Профиль активирован»).
     */
    public void sendCourierMenu(Long chatId, String headerText) {
        ReplyKeyboardMarkup keyboard = buildCourierReplyKeyboard();
        telegramSender.sendMessageWithReplyKeyboard(chatId, headerText, "Markdown", keyboard);
    }

    /**
     * Отправить текст курьеру и показать меню кнопок без разметки Markdown.
     * Используется после геолокации и в сценариях, где в тексте могут быть спецсимволы * и _.
     *
     * @param chatId чат.
     * @param text   текст как есть (без *жирного*).
     */
    public void sendCourierMenuPlain(Long chatId, String text) {
        ReplyKeyboardMarkup keyboard = buildCourierReplyKeyboard();
        telegramSender.sendMessageWithReplyKeyboard(chatId, text, null, keyboard);
    }

    private static ReplyKeyboardMarkup buildCourierReplyKeyboard() {
        KeyboardRow row1 = new KeyboardRow();
        row1.add("📋 Доступные заказы");
        row1.add("🚚 Мои заказы");
        row1.add("💰 Моя статистика");
        KeyboardRow row2 = new KeyboardRow();
        row2.add("ℹ️ Информация");
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(List.of(row1, row2));
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);
        return keyboard;
    }
}
