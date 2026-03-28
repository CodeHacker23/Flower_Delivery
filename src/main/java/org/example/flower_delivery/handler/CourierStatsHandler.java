package org.example.flower_delivery.handler;

import lombok.RequiredArgsConstructor;
import org.example.flower_delivery.model.Courier;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.service.CourierTransactionService;
import org.example.flower_delivery.service.OrderService;
import org.example.flower_delivery.service.CourierService;
import org.example.flower_delivery.telegram.TelegramSender;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.example.flower_delivery.model.CourierTransaction;

import static org.example.flower_delivery.model.OrderStatus.DELIVERED;

/**
 * Обработка кнопки «💰 Моя статистика» в меню курьера (рефакторинг, фаза 5).
 *
 * Показывает: всего доставлено, сумма, за сегодня/7 дней/месяц, баланс депозита, комиссию,
 * последние операции депозита с пагинацией. Вынесено из Bot. См. docs/REFACTORING_STATUS.md.
 */
@Component
@RequiredArgsConstructor
public class CourierStatsHandler {

    private final TelegramSender telegramSender;
    private final CourierService courierService;
    private final OrderService orderService;
    private final CourierTransactionService courierTransactionService;

    /**
     * Кнопка «Моя статистика»: проверка курьера, сбор заказов, показ экрана.
     */
    public void handleCourierStatsButton(Update update) {
        Long chatId = update.getMessage().getChatId();
        Long telegramId = update.getMessage().getFrom().getId();

        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty()) {
            sendSimple(chatId, "❌ У тебя ещё нет профиля курьера.\n\nВыбери роль *Курьер* через /start и пройди регистрацию.");
            return;
        }
        if (!Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
            sendSimple(chatId, "⏳ Твой профиль курьера ещё не активирован.\n\nСначала активируй его командой /k.");
            return;
        }

        List<Order> allOrders = orderService.getOrdersByCourierWithStops(courierOpt.get().getUser());
        Courier courier = courierOpt.get();
        CourierStatsContent content = buildCourierStatsContent(courier, allOrders, 0);
        telegramSender.sendMessage(chatId, content.text, "Markdown", content.replyMarkup);
    }

    /**
     * Редактировать сообщение «Моя статистика» (пагинация операций депозита по callback courier_tx_page).
     */
    public void editCourierStatsMessage(Long chatId, Integer messageId, Long telegramId, int txOffset) {
        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty()) return;
        Courier courier = courierOpt.get();
        List<Order> allOrders = orderService.getOrdersByCourierWithStops(courier.getUser());
        CourierStatsContent content = buildCourierStatsContent(courier, allOrders, txOffset);
        telegramSender.editMessage(chatId, messageId, content.text, content.replyMarkup);
    }

    public CourierStatsContent buildCourierStatsContent(Courier courier, List<Order> allOrders, int txOffset) {
        final int PAGE_SIZE = 6;
        LocalDate now = LocalDate.now();
        LocalDateTime startOfToday = now.atStartOfDay();
        LocalDateTime startOfWeek = now.minusDays(6).atStartOfDay();
        YearMonth thisMonth = YearMonth.from(now);

        var deliveredOrders = allOrders.stream().filter(o -> o.getStatus() == DELIVERED).toList();

        long totalDelivered = deliveredOrders.size();
        BigDecimal totalSum = deliveredOrders.stream()
                .map(Order::getTotalDeliveryPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long todayDelivered = deliveredOrders.stream()
                .filter(o -> o.getDeliveredAt() != null && !o.getDeliveredAt().isBefore(startOfToday))
                .count();
        BigDecimal todaySum = deliveredOrders.stream()
                .filter(o -> o.getDeliveredAt() != null && !o.getDeliveredAt().isBefore(startOfToday))
                .map(Order::getTotalDeliveryPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long weekDelivered = deliveredOrders.stream()
                .filter(o -> o.getDeliveredAt() != null && !o.getDeliveredAt().isBefore(startOfWeek))
                .count();
        BigDecimal weekSum = deliveredOrders.stream()
                .filter(o -> o.getDeliveredAt() != null && !o.getDeliveredAt().isBefore(startOfWeek))
                .map(Order::getTotalDeliveryPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long monthDelivered = deliveredOrders.stream()
                .filter(o -> o.getDeliveredAt() != null
                        && YearMonth.from(o.getDeliveredAt()).equals(thisMonth))
                .count();
        BigDecimal monthSum = deliveredOrders.stream()
                .filter(o -> o.getDeliveredAt() != null
                        && YearMonth.from(o.getDeliveredAt()).equals(thisMonth))
                .map(Order::getTotalDeliveryPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String monthName = thisMonth.getMonth().getDisplayName(TextStyle.FULL, new Locale("ru"));
        BigDecimal balance = courier.getBalance() != null ? courier.getBalance() : BigDecimal.ZERO;
        BigDecimal percent = courier.getCommissionPercent() != null
                ? courier.getCommissionPercent()
                : new BigDecimal("20.00");

        StringBuilder sb = new StringBuilder();
        sb.append("💰 *Моя статистика*\n\n")
                .append("📦 *Всего доставлено:* ").append(totalDelivered).append(" заказов\n")
                .append("💵 *Сумма:* ").append(totalSum).append(" ₽\n\n")
                .append("📆 *Сегодня:* ").append(todayDelivered).append(" заказов, ").append(todaySum).append(" ₽\n")
                .append("📅 *За 7 дней:* ").append(weekDelivered).append(" заказов, ").append(weekSum).append(" ₽\n")
                .append("📅 *За ").append(monthName).append(":* ").append(monthDelivered)
                .append(" заказов, ").append(monthSum).append(" ₽\n\n")
                .append("💳 *Баланс депозита:* ").append(balance).append(" ₽\n")
                .append("📈 *Комиссия с заказа:* ").append(percent).append(" %");

        var allTx = courierTransactionService.getLastTransactions(courier, 100);
        int total = allTx.size();
        if (total > 0) {
            int safeOffset = Math.max(0, Math.min(txOffset, Math.max(0, total - 1)));
            int end = Math.min(safeOffset + PAGE_SIZE, total);
            List<CourierTransaction> pageTx = allTx.subList(safeOffset, end);

            sb.append("\n\n📜 *Последние операции депозита:*\n");
            DateTimeFormatter txFmt = DateTimeFormatter.ofPattern("dd.MM HH:mm");
            pageTx.forEach(tx -> {
                String type = tx.getType();
                String humanType;
                if ("DEPOSIT_TOP_UP".equals(type)) {
                    humanType = "Пополнение";
                } else if ("COMMISSION_CHARGE".equals(type)) {
                    humanType = "Комиссия";
                } else if ("COMMISSION_REFUND".equals(type)) {
                    humanType = "Возврат комиссии";
                } else if (type != null && type.startsWith("PENALTY")) {
                    humanType = "Штраф";
                } else {
                    humanType = type != null ? type : "Операция";
                }
                String when = tx.getCreatedAt() != null ? tx.getCreatedAt().format(txFmt) : "";
                sb.append("• ").append(when).append(" — ")
                        .append(tx.getAmount()).append(" ₽ (").append(humanType).append(")\n");
            });

            if (total > PAGE_SIZE) {
                sb.append("\nСтроки ").append(safeOffset + 1).append("–").append(end)
                        .append(" из ").append(total).append(".");
            }
        }

        String text = sb.toString();

        InlineKeyboardButton topUpBtn = InlineKeyboardButton.builder()
                .text("💳 Пополнить депозит")
                .callbackData("courier_deposit_topup")
                .build();

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        if (allTx.size() > 6) {
            int safeOffset = Math.max(0, Math.min(txOffset, Math.max(0, allTx.size() - 1)));
            List<InlineKeyboardButton> navRow = new ArrayList<>();
            if (safeOffset > 0) {
                int prevOffset = Math.max(0, safeOffset - 6);
                navRow.add(InlineKeyboardButton.builder()
                        .text("⬆️ Новее")
                        .callbackData("courier_tx_page:" + prevOffset)
                        .build());
            }
            if (safeOffset + 6 < allTx.size()) {
                int nextOffset = safeOffset + 6;
                navRow.add(InlineKeyboardButton.builder()
                        .text("⬇️ Раньше")
                        .callbackData("courier_tx_page:" + nextOffset)
                        .build());
            }
            if (!navRow.isEmpty()) {
                rows.add(navRow);
            }
        }

        rows.add(List.of(topUpBtn));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(rows);
        return new CourierStatsContent(text, markup);
    }

    private void sendSimple(Long chatId, String text) {
        telegramSender.sendMessage(chatId, text, "Markdown", null);
    }

    public record CourierStatsContent(String text, InlineKeyboardMarkup replyMarkup) {}
}
