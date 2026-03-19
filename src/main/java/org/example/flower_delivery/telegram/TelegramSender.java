package org.example.flower_delivery.telegram;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;

/**
 * Контракт «как слать сообщения в Telegram» — без привязки к конкретному боту.
 *
 * ЗАЧЕМ ЭТО НУЖНО (на пальцах):
 * Сейчас все хендлеры и сервисы тащат за собой самого Bot и вызывают bot.execute(msg).
 * Бот — жирный, один на весь проект, и каждый кто хочет что-то отправить знает про него.
 * По принципу Dependency Inversion (D из SOLID): мы хотим зависеть от абстракции
 * («кто-то умеет слать»), а не от конкретного класса Bot. Тогда в тестах подсовываем
 * заглушку (мок), которая не лезет в сеть, а просто пишет «вызвали sendMessage с такими-то
 * аргументами» — и проверяем логику без реального Telegram.
 *
 * ИНТЕРФЕЙС = контракт без реализации.
 * Тут только сигнатуры методов (имя + параметры + возвращаемый тип), без тел в фигурных скобках.
 * Класс, который implements TelegramSender, обязан реализовать все три метода.
 * Кто вызывает — ему всё равно, бот это или мок: главное что у объекта есть sendMessage,
 * editMessage, sendMessagePlain.
 *
 * Аналогия: интерфейс — это как «правила для водителя такси»: довезти из А в Б.
 * Реализация — конкретная машина (BotTelegramSender = наша живая бот-машина, мок = игрушечная
 * машинка, которая никуда не едет, но мы проверяем что «вызвали довезти» с нужным адресом).
 */
public interface TelegramSender {

    /**
     * Шлёт сообщение в чат с опциональной разметкой Markdown и инлайн-кнопками.
     * Возвращает void — успех/ошибка обрабатываются внутри реализации (логирование, не проброс выше).
     *
     * @param chatId    чей чат (Long — телеграмовский id юзера/чата). Не null при нормальном вызове.
     * @param text      тело сообщения. Может содержать *жирный* и _курсив_ если parseMode = "Markdown".
     * @param parseMode "Markdown" или null. null = текст как есть, без интерпретации подчёркиваний и звёздочек.
     * @param markup    инлайн-кнопки под сообщением (InlineKeyboardMarkup) или null, если кнопок нет.
     */
    void sendMessage(Long chatId, String text, String parseMode, InlineKeyboardMarkup markup);

    /**
     * Редактирует уже отправленное сообщение (текст и/или клавиатуру).
     * Используется для пагинации списков («Мои заказы», «Доступные заказы», «Статистика») без нового сообщения.
     *
     * @param chatId    чат, в котором лежит сообщение.
     * @param messageId id сообщения в чате (Message.getMessageId() из Update). Без него API не поймёт, что править.
     * @param text      новый текст сообщения.
     * @param markup    новые инлайн-кнопки или null (если null, клавиатура у сообщения не меняется).
     */
    void editMessage(Long chatId, Integer messageId, String text, InlineKeyboardMarkup markup);

    /**
     * Шлёт сообщение без разметки и без кнопок — просто текст.
     * Удобная обёртка: sendMessage(chatId, text, null, null), чтобы не таскать два null по коду.
     *
     * @param chatId чат.
     * @param text   текст как есть (спецсимволы * и _ не интерпретируются).
     */
    void sendMessagePlain(Long chatId, String text);

    /**
     * Шлёт сообщение с Reply-клавиатурой (кнопки внизу экрана: «Создать заказ», «Мои заказы» и т.д.).
     * ReplyKeyboard в Telegram показывается под полем ввода и остаётся до следующей смены клавиатуры.
     * В отличие от InlineKeyboard — привязана к чату, а не к конкретному сообщению.
     *
     * @param chatId       чат.
     * @param text         текст сообщения.
     * @param parseMode    "Markdown" или null.
     * @param replyMarkup  клавиатура (ряды кнопок). Не null при вызове из меню магазина/курьера.
     */
    void sendMessageWithReplyKeyboard(Long chatId, String text, String parseMode, ReplyKeyboardMarkup replyMarkup);
}
