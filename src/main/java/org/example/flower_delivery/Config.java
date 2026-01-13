package org.example.flower_delivery;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Configuration
public class Config {
    /**
     * Регистрирует Telegram-бота как Spring-бин
     *
     * @param bot — твой основной Bot
     * @return TelegramBotsApi
     */
    @Bean
    TelegramBotsApi telegramBotsApi(Bot bot) throws TelegramApiException {
        TelegramBotsApi telegramBotsApi;
        try {
            telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
            telegramBotsApi.registerBot(bot);
            System.out.println("[Config] telegramBotsApi() — бот успешно зарегистрирован!");
        }catch (TelegramApiException e) {
            System.err.println("[Config] КРИТИЧЕСКАЯ ОШИБКА: Не удалось зарегистрировать бота в Telegram API!");
            System.err.println("[Config] Детали ошибки: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException("Не удалось зарегистрировать Telegram бота. Проверьте токен и подключение к интернету.", e);
        }
        return telegramBotsApi;

        // --- Советы по расширению ---
        // 1. Новый бин? Добавь новый @Bean-метод.
        // 2. Не пихай бизнес-логику — только конфигурация.
        // 3. Если добавишь бин без комментария — Иларион лично напишет тебе в Telegram.

    }
}
