package org.example.flower_delivery.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.service.YooKassaPaymentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class CourierDepositHandler {

    private final YooKassaPaymentService yooKassaPaymentService;

    @Autowired
    @Lazy
    private org.example.flower_delivery.Bot bot;

    /** telegramId курьера -> ждём ввод суммы пополнения. */
    private final Map<Long, Boolean> awaitingAmount = new ConcurrentHashMap<>();

    public void startTopUp(Long telegramId, Long chatId) {
        awaitingAmount.put(telegramId, true);
        log.info("🛰️ Старт пополнения депозита курьера: telegramId={}, chatId={}", telegramId, chatId);
        SendMessage msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text("💳 *Пополнение депозита*\n\n" +
                        "Введи сумму пополнения в рублях (минимум 300).\n\n" +
                        "Если передумал — отправь /cancel или нажми любую кнопку меню внизу.")
                .parseMode("Markdown")
                .build();
        try {
            bot.execute(msg);
        } catch (TelegramApiException e) {
            log.error("Ошибка при запросе суммы пополнения: chatId={}", chatId, e);
        }
    }

    public boolean handleText(Update update) {
        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText();

        if (!awaitingAmount.getOrDefault(telegramId, false)) {
            return false;
        }

        // Если курьер нажал кнопку меню или /cancel — выходим из режима пополнения и отдаём управление дальше.
        if ("📋 Доступные заказы".equals(text)
                || "🚚 Мои заказы".equals(text)
                || "💰 Моя статистика".equals(text)
                || "ℹ️ Информация".equals(text)
                || "/start".equals(text)
                || "/cancel".equalsIgnoreCase(text.trim())) {
            log.info("Курьер вышел из режима пополнения депозита: telegramId={}, reason='{}'", telegramId, text);
            awaitingAmount.remove(telegramId);
            return false;
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            log.warn("🚨 Неверный ввод суммы пополнения: telegramId={}, raw='{}'", telegramId, text);
            sendSimple(chatId, "❌ Не удалось распознать сумму.\nВведи число, например: 500");
            return true;
        }

        if (amount.compareTo(new BigDecimal("300")) < 0) {
            log.debug("Попытка пополнения ниже минимума: telegramId={}, amount={}", telegramId, amount);
            sendSimple(chatId, "❌ Минимальная сумма пополнения — 300 ₽.\nВведи сумму не меньше 300.");
            return true;
        }

        awaitingAmount.remove(telegramId);

        try {
            String confirmationUrl = yooKassaPaymentService.createCourierDepositPayment(telegramId, amount);
            if (confirmationUrl == null || confirmationUrl.isBlank()) {
                log.error("❌ ЮKassa не вернула ссылку на оплату: telegramId={}, amount={}", telegramId, amount);
                sendSimple(chatId, "❌ Не удалось создать платёж в ЮKassa. Попробуй позже.");
                return true;
            }

            InlineKeyboardButton payBtn = InlineKeyboardButton.builder()
                    .text("💳 Оплатить через ЮKassa / СБП")
                    .url(confirmationUrl)
                    .build();
            InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(payBtn)));

            SendMessage msg = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text("✅ Платёж на *" + amount + " ₽* создан.\n\n" +
                            "Нажми кнопку ниже, чтобы перейти к оплате.\n" +
                            "После успешной оплаты депозит будет пополнен автоматически (по webhook).")
                    .parseMode("Markdown")
                    .replyMarkup(markup)
                    .build();
            bot.execute(msg);
        } catch (Exception e) {
            log.error("Ошибка при создании платежа ЮKassa: telegramId={}, amount={}", telegramId, amount, e);
            sendSimple(chatId, "❌ Произошла ошибка при создании платежа. Попробуй позже.");
        }

        return true;
    }

    private void sendSimple(Long chatId, String text) {
        try {
            bot.execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения (CourierDepositHandler): chatId={}", chatId, e);
        }
    }
}

