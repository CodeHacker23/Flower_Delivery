package org.example.flower_delivery.dispatcher;

import lombok.RequiredArgsConstructor;
import org.example.flower_delivery.handler.CallbackQueryHandler;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Обработчик нажатий на инлайн-кнопки (callback query).
 *
 * Юзер жмёт кнопку под сообщением — в Update прилетает callbackQuery с data типа
 * "order_edit:uuid" или "courier_order_take:uuid". Всю разборку делает CallbackQueryHandler.
 * Мы тут только: «если есть callback — это моё», и отдаём апдейт тому хендлеру.
 */
@RequiredArgsConstructor
/** Lombok: генерирует конструктор по всем final-полям. Spring передаст callbackQueryHandler при создании бина. */
public class CallbackQueryUpdateHandler implements UpdateHandler {

    /** Тот самый большой хендлер, который разбирает все callback_data и дергает сервисы/бот. */
    private final CallbackQueryHandler callbackQueryHandler;

    @Override
    /** Диспетчер спрашивает: «это твоё?» — мы отвечаем true только если в апдейте есть callback (нажатие инлайн-кнопки). */
    public boolean canHandle(Update update) {
        return update.hasCallbackQuery();
    }

    @Override
    /** Отдаём апдейт в CallbackQueryHandler — он там сам разберёт callbackData и сделает что надо. */
    public void handle(Update update) {
        callbackQueryHandler.handle(update);
    }
}
