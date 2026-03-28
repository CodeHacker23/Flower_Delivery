package org.example.flower_delivery.dispatcher;

import org.example.flower_delivery.Bot;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.springframework.context.annotation.Lazy;

/**
 * Обработчик текстовых сообщений: команды, кнопки меню, ввод в пошаговых сценариях.
 *
 * Вся логика с /start, /r, /k, «Создать заказ», «Мой магазин» и т.д. живёт в Bot.processTextUpdate().
 * Мы тут только: «если есть текст — это моё», и отдаём апдейт боту.
 *
 * @Lazy Bot — иначе цикл: Bot → Dispatcher → TextUpdateHandler → Bot. Spring даёт прокси, к первому вызову бот готов.
 */
public class TextUpdateHandler implements UpdateHandler {

    /** Ссылка на бота; через неё вызываем processTextUpdate(update). Передаётся с @Lazy из DispatcherConfig. */
    private final Bot bot;

    /**
     * Конструктор с @Lazy Bot — Spring подставит прокси, чтобы разорвать циклическую зависимость.
     * @param bot бот (прокси при создании, реальный объект при первом вызове).
     */
    public TextUpdateHandler(@Lazy Bot bot) {
        this.bot = bot;
    }

    @Override
    /** «Моё» только если в сообщении есть текст (hasText). Все команды и кнопки меню — это текст. */
    public boolean canHandle(Update update) {
        return update.hasMessage() && update.getMessage().hasText();
    }

    @Override
    /** Вся портянка с ветками по тексту — в Bot.processTextUpdate(). Мы только делегируем. */
    public void handle(Update update) {
        bot.processTextUpdate(update);
    }
}
