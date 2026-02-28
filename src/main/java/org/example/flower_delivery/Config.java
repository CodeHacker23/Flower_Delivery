package org.example.flower_delivery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

@Slf4j
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
            log.info("Бот успешно зарегистрирован в Telegram API");
        } catch (TelegramApiException e) {
            log.error("Не удалось зарегистрировать бота в Telegram API: {}", e.getMessage(), e);
            throw new RuntimeException("Не удалось зарегистрировать Telegram бота. Проверьте токен и подключение к интернету.", e);
        }
        return telegramBotsApi;
    }
}
