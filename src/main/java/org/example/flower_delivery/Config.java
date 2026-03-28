package org.example.flower_delivery;

import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.telegram.BotTelegramSender;
import org.example.flower_delivery.telegram.TelegramSender;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class Config {
    @Value("${app.telegram.proxy.enabled:false}")
    private boolean proxyEnabled;

    @Value("${app.telegram.proxy.type:https}")
    private String proxyType;

    @Value("${app.telegram.proxy.host:}")
    private String proxyHost;

    @Value("${app.telegram.proxy.port:0}")
    private int proxyPort;

    @Value("${app.telegram.proxy.username:}")
    private String proxyUsername;

    @Value("${app.telegram.proxy.password:}")
    private String proxyPassword;

    /**
     * Бин «отправителя» сообщений в Telegram.
     * Нужен для хендлеров/сервисов, которые шлют сообщения через TelegramSender.
     */
    @Bean
    TelegramSender telegramSender(@Lazy Bot bot,
                                   @Qualifier("telegramExecutor") Executor telegramExecutor) {
        return new BotTelegramSender(bot, telegramExecutor);
    }

    /**
     * Регистрирует Telegram-бота как Spring-бин
     *
     * @param bot — твой основной Bot
     * @return TelegramBotsApi
     */
    @Bean
    TelegramBotsApi telegramBotsApi(Bot bot) throws TelegramApiException {
        configureProxyIfEnabled();
        TelegramBotsApi telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
        registerBotWithRetry(telegramBotsApi, bot);
        return telegramBotsApi;
    }

    private void configureProxyIfEnabled() {
        if (!proxyEnabled) {
            return;
        }
        if (!StringUtils.hasText(proxyHost) || proxyPort <= 0) {
            log.warn("Прокси включен, но host/port не заданы корректно. Пропускаю настройку прокси.");
            return;
        }

        String normalizedType = proxyType == null ? "https" : proxyType.trim().toLowerCase();
        switch (normalizedType) {
            case "http", "https" -> {
                System.setProperty("http.proxyHost", proxyHost);
                System.setProperty("http.proxyPort", String.valueOf(proxyPort));
                System.setProperty("https.proxyHost", proxyHost);
                System.setProperty("https.proxyPort", String.valueOf(proxyPort));
            }
            case "socks" -> {
                System.setProperty("socksProxyHost", proxyHost);
                System.setProperty("socksProxyPort", String.valueOf(proxyPort));
                // Для SOCKS-аутентификации Java использует отдельные свойства.
                if (StringUtils.hasText(proxyUsername)) {
                    System.setProperty("java.net.socks.username", proxyUsername);
                    if (StringUtils.hasText(proxyPassword)) {
                        System.setProperty("java.net.socks.password", proxyPassword);
                    }
                }
            }
            default -> {
                log.warn("Неизвестный тип прокси '{}'. Поддерживаются: http/https/socks.", normalizedType);
                return;
            }
        }

        if (StringUtils.hasText(proxyUsername)) {
            System.setProperty("http.proxyUser", proxyUsername);
            System.setProperty("https.proxyUser", proxyUsername);
            if (StringUtils.hasText(proxyPassword)) {
                System.setProperty("http.proxyPassword", proxyPassword);
                System.setProperty("https.proxyPassword", proxyPassword);
            }
            // Разрешаем Basic-аутентификацию при HTTPS CONNECT через прокси.
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
            System.setProperty("jdk.http.auth.proxying.disabledSchemes", "");

            Authenticator.setDefault(new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(
                            proxyUsername,
                            proxyPassword == null ? new char[0] : proxyPassword.toCharArray()
                    );
                }
            });
        }

        log.info("Прокси для Telegram включен: type={}, host={}, port={}", normalizedType, proxyHost, proxyPort);
    }

    private void registerBotWithRetry(TelegramBotsApi telegramBotsApi, Bot bot) {
        final int maxAttempts = 5;
        final long retryDelayMs = 2000L;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                telegramBotsApi.registerBot(bot);
                log.info("Бот успешно зарегистрирован в Telegram API (attempt={})", attempt);
                return;
            } catch (TelegramApiException e) {
                boolean lastAttempt = attempt == maxAttempts;
                if (lastAttempt) {
                    log.error("Не удалось зарегистрировать бота в Telegram API после {} попыток. " +
                                    "Приложение продолжит работу, но бот не будет принимать апдейты. " +
                                    "Проверьте токен, сеть и отключение webhook.",
                            maxAttempts, e);
                    return;
                }

                log.warn("Ошибка регистрации бота (attempt {}/{}): {}. Повтор через {} мс.",
                        attempt, maxAttempts, e.getMessage(), retryDelayMs);
                sleepQuietly(retryDelayMs);
            }
        }
    }

    private void sleepQuietly(long retryDelayMs) {
        try {
            TimeUnit.MILLISECONDS.sleep(retryDelayMs);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            log.warn("Ожидание перед повторной регистрацией бота было прервано.");
        }
    }
}
