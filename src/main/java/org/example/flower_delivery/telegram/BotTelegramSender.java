package org.example.flower_delivery.telegram;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.Bot;
import org.springframework.beans.factory.annotation.Qualifier;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Реализация TelegramSender — под капотом дергает нашего бота и его execute().
 *
 * Кто такой этот класс: прослойка между «кто хочет отправить сообщение» (Bot, хендлеры) и самим Bot.
 * Интерфейс TelegramSender — контракт «умею слать». BotTelegramSender — живая реализация: внутри
 * ссылка на Bot, при sendMessage() собираем SendMessage и вызываем bot.execute(sendMessage).
 * Зачем ссылка на Bot: execute() — метод TelegramLongPollingBot. В Telegram API шлёт сообщения
 * только зарегистрированный бот. В тестах подставляем мок — тот же интерфейс, без execute().
 */
@Slf4j
/** Lombok: генерирует private static final Logger log. Позволяет писать log.error() без объявления поля. */
@RequiredArgsConstructor
/** Lombok: конструктор по всем final-полям (здесь только Bot). Spring передаёт бота при создании бина. */
public class BotTelegramSender implements TelegramSender {

    /** Ссылка на бота. final — присваивается раз в конструкторе, дальше не меняется. Через неё вызываем execute(). */
    private final Bot bot;

    /** Executor для отправки в Telegram (чтобы не блокировать потоки обработки апдейтов). */
    @Qualifier("telegramExecutor")
    private final Executor telegramExecutor;

    /** latest seq per (chatId + operation kind). */
    private ConcurrentHashMap<String, AtomicLong> latestSeqByKey = new ConcurrentHashMap<>();

    private static final String KIND_SEND = "send";
    private static final String KIND_EDIT = "edit";
    private static final String KIND_SEND_REPLY = "sendReply";

    private static String seqKey(Long chatId, String kind) {
        return kind + ":" + (chatId == null ? "null" : chatId.toString());
    }

    /**
     * Отправка сообщения с опциональным Markdown и инлайн-кнопками.
     * Что делает по шагам: собираем SendMessage (chatId в API — строка, поэтому toString()),
     * вызываем bot.execute(msg). При TelegramApiException логируем и не пробрасываем — чтобы одно
     * падение отправки не роняло всё приложение.
     */
    @Override
    public void sendMessage(Long chatId, String text, String parseMode, InlineKeyboardMarkup markup) {
        if (chatId == null) {
            log.warn("BotTelegramSender.sendMessage: chatId=null, skip");
            return;
        }

        String key = seqKey(chatId, KIND_SEND);
        long seq = latestSeqByKey.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();

        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode(parseMode)
                .replyMarkup(markup)
                .build();

        telegramExecutor.execute(() -> {
            AtomicLong latest = latestSeqByKey.get(key);
            if (latest == null || latest.get() != seq) {
                log.info("BotTelegramSender.sendMessage: discard outdated send chatId={}, seq={}", chatId, seq);
                return;
            }

            long startedAt = System.currentTimeMillis();
            try {
                bot.execute(msg);
            } catch (TelegramApiException e) {
                log.error("Не удалось отправить сообщение в chatId={}: {}", chatId, e.getMessage(), e);
            } finally {
                long finishedAt = System.currentTimeMillis();
                log.info("BotTelegramSender.sendMessage: chatId={}, durationMs={}, textPreview='{}'",
                        chatId, (finishedAt - startedAt),
                        text != null && text.length() > 40 ? text.substring(0, 40) + "..." : text);
            }
        });
    }

    /**
     * Редактирование уже отправленного сообщения.
     * EditMessageText — тип запроса в Telegram API «изменить текст и/или reply_markup у сообщения».
     * messageId — какое сообщение в чате менять (приходит в Update при нажатии инлайн-кнопки).
     * setParseMode("Markdown") — редактируемый текст интерпретируем как Markdown.
     */
    @Override
    public void editMessage(Long chatId, Integer messageId, String text, InlineKeyboardMarkup markup) {
        if (chatId == null) {
            log.warn("BotTelegramSender.editMessage: chatId=null, skip");
            return;
        }

        String key = seqKey(chatId, KIND_EDIT);
        long seq = latestSeqByKey.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();

        EditMessageText edit = new EditMessageText();
        edit.setChatId(chatId.toString());
        edit.setMessageId(messageId);
        edit.setText(text);
        edit.setParseMode("Markdown");
        if (markup != null) {
            edit.setReplyMarkup(markup);
        }

        telegramExecutor.execute(() -> {
            AtomicLong latest = latestSeqByKey.get(key);
            if (latest == null || latest.get() != seq) {
                log.info("BotTelegramSender.editMessage: discard outdated edit chatId={}, seq={}", chatId, seq);
                return;
            }

            long startedAt = System.currentTimeMillis();
            try {
                bot.execute(edit);
            } catch (TelegramApiException e) {
                log.error("Не удалось отредактировать сообщение chatId={}, messageId={}: {}", chatId, messageId, e.getMessage(), e);
            } finally {
                long finishedAt = System.currentTimeMillis();
                log.info("BotTelegramSender.editMessage: chatId={}, messageId={}, durationMs={}",
                        chatId, messageId, (finishedAt - startedAt));
            }
        });
    }

    /**
     * Отправка «просто текста» без разметки и без кнопок.
     * Вызов делегируем в sendMessage с null, null — не дублируем логику сборки SendMessage.
     */
    @Override
    public void sendMessagePlain(Long chatId, String text) {
        sendMessage(chatId, text, null, null);
    }

    /**
     * Отправка сообщения с Reply-клавиатурой (кнопки внизу экрана).
     * То же что sendMessage, но replyMarkup имеет тип ReplyKeyboardMarkup. API Telegram принимает
     * либо InlineKeyboardMarkup, либо ReplyKeyboardMarkup; тип задаётся полем replyMarkup.
     */
    @Override
    public void sendMessageWithReplyKeyboard(Long chatId, String text, String parseMode, ReplyKeyboardMarkup replyMarkup) {
        if (chatId == null) {
            log.warn("BotTelegramSender.sendMessageWithReplyKeyboard: chatId=null, skip");
            return;
        }

        String key = seqKey(chatId, KIND_SEND_REPLY);
        long seq = latestSeqByKey.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();

        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode(parseMode)
                .replyMarkup(replyMarkup)
                .build();

        telegramExecutor.execute(() -> {
            AtomicLong latest = latestSeqByKey.get(key);
            if (latest == null || latest.get() != seq) {
                log.info("BotTelegramSender.sendMessageWithReplyKeyboard: discard outdated send chatId={}, seq={}", chatId, seq);
                return;
            }

            long startedAt = System.currentTimeMillis();
            try {
                bot.execute(msg);
            } catch (TelegramApiException e) {
                log.error("Не удалось отправить сообщение с Reply-клавиатурой в chatId={}: {}", chatId, e.getMessage(), e);
            } finally {
                long finishedAt = System.currentTimeMillis();
                log.info("BotTelegramSender.sendMessageWithReplyKeyboard: chatId={}, durationMs={}, textPreview='{}'",
                        chatId, (finishedAt - startedAt),
                        text != null && text.length() > 40 ? text.substring(0, 40) + "..." : text);
            }
        });
    }
}
