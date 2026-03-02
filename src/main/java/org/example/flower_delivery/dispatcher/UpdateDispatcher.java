package org.example.flower_delivery.dispatcher;

import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.List;

/**
 * Диспетчер апдейтов: раздаёт входящие сообщения по рукам, без гигантского if в Bot.
 *
 * Список обработчиков в заданном порядке. Прилетел апдейт — идём по списку, первый у кого
 * canHandle(update) == true получает handle(update) и на этом всё. Порядок важен: callback
 * раньше текста, иначе нажатие кнопки уедет в текст-хендлер.
 */
@Slf4j
/** Lombok: подставляет поле Logger log. Пока тут не используем, но при отладке можно log.debug("dispatch", ...). */
public class UpdateDispatcher {

    /** Список обработчиков в порядке приоритета. Final — присваивается в конструкторе, дальше не меняется. */
    private final List<UpdateHandler> handlers;

    /**
     * Конструктор: принимает готовый список обработчиков. Spring вызовет его из DispatcherConfig,
     * передав все пять хендлеров (callback, contact, photo, location, text) в нужном порядке.
     * @param handlers список UpdateHandler — порядок элементов = порядок проверки при dispatch.
     */
    public UpdateDispatcher(List<UpdateHandler> handlers) {
        this.handlers = handlers;
    }

    /**
     * Раздать апдейт первому, кто сказал «моё». Вызвал handle — выходим, остальных не трогаем.
     *
     * @param update объект от Telegram (сообщение, callback, фото, гео и т.д.).
     */
    public void dispatch(Update update) {
        for (UpdateHandler h : handlers) {
            if (h.canHandle(update)) {
                h.handle(update);
                return; // первый принял — выходим, остальных не дергаем
            }
        }
    }
}
