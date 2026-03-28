package org.example.flower_delivery.dispatcher;

import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Контракт: «я умею понять, моё ли это сообщение, и если моё — обработать».
 *
 * Раньше в Bot был пиздец какой if: if (callback) ... else if (contact) ... else if (photo) ...
 * Порядок веток решал всё, добавить новый тип апдейта = влезть в эту портянку и не сломать соседей.
 * Теперь каждый тип апдейта — отдельный класс. Диспетчер по очереди спрашивает: «ты можешь?» —
 * первый, кто сказал «да», получает апдейт и пашет. Остальные даже не вызываются.
 *
 * canHandle(update) — «это моё?» Например у CallbackQuery — update.hasCallbackQuery().
 * handle(update) — «ок, делаю». Вызывается только если canHandle уже вернул true.
 *
 * Аналогия: как охранник на входе в клуб. «Кто разбирает нажатия на кнопки?» — CallbackQuery.
 * «Кто контакт (номер телефона)?» — Contact. Первый кто поднял руку — тот и в работе. Остальные спят.
 */
public interface UpdateHandler {

    /** «Этот апдейт мой?» true = буду обрабатывать сам. */
    boolean canHandle(Update update);

    /** Обработать апдейт. Вызывается только если canHandle(update) вернул true. */
    void handle(Update update);
}
