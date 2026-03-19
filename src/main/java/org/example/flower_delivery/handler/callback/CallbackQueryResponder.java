package org.example.flower_delivery.handler.callback;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

/**
 * Контракт для ответа на callback query: ответить на нажатие кнопки и отправить/редактировать сообщения.
 * Реализует главный CallbackQueryHandler (через bot.execute). Под-хендлеры получают контекст с этим интерфейсом.
 */
public interface CallbackQueryResponder {

    void answerCallbackQuery(String callbackQueryId, String text);

    void sendMessage(Long chatId, String text, String parseMode, InlineKeyboardMarkup markup);

    void editMessage(Long chatId, Integer messageId, String text, InlineKeyboardMarkup markup);
}
