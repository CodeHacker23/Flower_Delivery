package org.example.flower_delivery.handler.callback;

import org.telegram.telegrambots.meta.api.objects.CallbackQuery;

/**
 * Контекст обработки callback query: данные из Update и способ ответить.
 * Передаётся в под-хендлеры (RoleCallbackHandler, ShopOrderCallbackHandler, CourierCallbackHandler).
 */
public record CallbackQueryContext(
        Long telegramId,
        Long chatId,
        String callbackQueryId,
        Integer messageId,
        String callbackData,
        CallbackQuery callbackQuery,
        CallbackQueryResponder responder
) {}
