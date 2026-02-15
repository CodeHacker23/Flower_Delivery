package org.example.flower_delivery.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.Bot;
import org.example.flower_delivery.model.OrderStatus;
import org.example.flower_delivery.model.Role;
import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.service.ShopService;
import org.example.flower_delivery.service.CourierService;
import org.example.flower_delivery.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;
import java.util.UUID;

import org.example.flower_delivery.model.OrderStop;

/**
 * Обработчик callback query - это когда пользователь нажимает на Inline кнопку
 *
 * <h2>Что это такое:</h2>
 * Когда пользователь нажимает на кнопку (например "Магазин" или "Курьер"),
 * Telegram отправляет нам CallbackQuery с данными кнопки (callback_data).
 *
 * <h2>Как это работает:</h2>
 * <ol>
 *   <li>Пользователь нажимает кнопку "🏪 Магазин"</li>
 *   <li>Telegram отправляет CallbackQuery с callback_data="role_shop"</li>
 *   <li>Мы обрабатываем это в handle()</li>
 *   <li>Сохраняем роль в БД через UserService</li>
 *   <li>Отправляем подтверждение пользователю</li>
 * </ol>
 *
 * <h2>Примеры использования:</h2>
 *
 * <h3>1. Обработка выбора роли:</h3>
 * <pre>{@code
 * // Пользователь нажал "Магазин"
 * CallbackQuery callbackQuery = update.getCallbackQuery();
 * String callbackData = callbackQuery.getData(); // "role_shop"
 *
 * // Извлекаем роль из callback_data
 * if (callbackData.equals("role_shop")) {
 *     userService.updateUserRole(telegramId, Role.SHOP);
 *     sendMessage(chatId, "Ты выбрал роль: Магазин");
 * }
 * }</pre>
 *
 * <h2>Важные моменты:</h2>
 * <ul>
 *   <li><b>AnswerCallbackQuery:</b> ОБЯЗАТЕЛЬНО нужно ответить на callback query,
 *       иначе кнопка будет "висеть" (показывать загрузку)</li>
 *   <li><b>callback_data:</b> Максимум 64 байта (короткие строки типа "role_shop")</li>
 *   <li><b>getFrom():</b> Получаем информацию о пользователе, который нажал кнопку</li>
 *   <li><b>getMessage():</b> Получаем сообщение, к которому прикреплена кнопка</li>
 * </ul>
 *
 * <h2>Связь с другими компонентами:</h2>
 * <ul>
 *   <li>{@code StartCommandHandler} - создает кнопки с callback_data="role_shop"/"role_courier"</li>
 *   <li>{@code UserService} - сохраняет выбранную роль в БД</li>
 *   <li>{@code Bot} - вызывает handle() когда приходит CallbackQuery</li>
 * </ul>
 *
 * @author Иларион
 * @version 1.0
 * @see org.example.flower_delivery.handler.StartCommandHandler
 * @see org.example.flower_delivery.service.UserService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallbackQueryHandler {

    // Spring автоматически найдет UserService и подставит сюда (Dependency Injection)
    private final UserService userService;

    // Spring автоматически найдет Bot и подставит сюда (Dependency Injection)
    // @Lazy - создаёт прокси для Bot, разрывая циклическую зависимость
    @Autowired
    @Lazy
    private Bot bot;

    // Обработчик регистрации магазина (для запуска после выбора роли "Магазин")
    @Autowired
    @Lazy
    private ShopRegistrationHandler shopRegistrationHandler;

    // Обработчик создания заказа
    @Autowired
    @Lazy
    private OrderCreationHandler orderCreationHandler;

    // Сервис магазинов (для показа информации о магазине)
    private final ShopService shopService;

    // Сервис курьеров (для назначения заказов)
    private final CourierService courierService;

    // Обработчик выбора доступных заказов курьером
    @Autowired
    @Lazy
    private CourierAvailableOrdersHandler courierAvailableOrdersHandler;

    // Сервис заказов (для отмены, редактирования и работы курьера)
    private final org.example.flower_delivery.service.OrderService orderService;

    @Autowired
    @Lazy
    private OrderEditHandler orderEditHandler;

    @Autowired
    @Lazy
    private MyOrdersSelectionHandler myOrdersSelectionHandler;

    // Обработчик регистрации курьера (после выбора роли "Курьер")
    @Autowired
    @Lazy
    private CourierRegistrationHandler courierRegistrationHandler;

    @Autowired
    @Lazy
    private CourierGeoHandler courierGeoHandler;

    /**
     * Обработать callback query (нажатие на кнопку)
     *
     * @param update - объект Update от Telegram с информацией о нажатии на кнопку
     */
    public void handle(Update update) {
        // Проверяем, что в Update есть CallbackQuery
        if (!update.hasCallbackQuery()) {
            log.warn("Update не содержит CallbackQuery: {}", update);
            return;
        }

        CallbackQuery callbackQuery = update.getCallbackQuery();
        if (callbackQuery.getMessage() == null) {
            answerCallbackQuery(callbackQuery.getId(), "❌ Сообщение недоступно");
            return;
        }
        String callbackData = callbackQuery.getData();
        if (callbackData == null || callbackData.isEmpty()) {
            answerCallbackQuery(callbackQuery.getId(), "❌ Пустая команда");
            return;
        }
        Long telegramId = callbackQuery.getFrom().getId();
        Long chatId = callbackQuery.getMessage().getChatId();

        log.info("Обработка callback query: telegramId={}, callbackData={}", telegramId, callbackData);

        try {
            // Обрабатываем разные типы callback_data
            if (callbackData.startsWith("role_")) {
                // Сразу отвечаем на callback query, чтобы кнопка не "висела"
                answerCallbackQuery(callbackQuery.getId(), "✅ Роль выбрана!");
                handleRoleSelection(callbackData, telegramId, chatId);
            } else if (callbackData.equals("create_order")) {
                // Магазин хочет создать заказ
                answerCallbackQuery(callbackQuery.getId(), "📦 Создаём заказ...");
                orderCreationHandler.startOrderCreation(telegramId, chatId);
            } else if (callbackData.equals("shop_info")) {
                // Магазин хочет посмотреть информацию о себе
                answerCallbackQuery(callbackQuery.getId(), "🏪 Информация о магазине");
                handleShopInfo(telegramId, chatId);
            } else if (callbackData.startsWith("delivery_date_")) {
                // Выбор даты доставки при создании заказа
                answerCallbackQuery(callbackQuery.getId(), "📅 Дата выбрана");
                orderCreationHandler.handleDateSelection(telegramId, chatId, callbackData);
            } else if (callbackData.startsWith("confirm_price_")) {
                // Подтверждение автоматически рассчитанной цены
                String priceStr = callbackData.replace("confirm_price_", "");
                answerCallbackQuery(callbackQuery.getId(), "✅ Цена подтверждена");
                orderCreationHandler.handlePriceConfirmation(telegramId, chatId, new java.math.BigDecimal(priceStr));
                
            // ===== МУЛЬТИАДРЕСНЫЕ ЗАКАЗЫ =====
            } else if (callbackData.equals("add_stop_yes")) {
                // Пользователь хочет добавить ещё одну точку
                answerCallbackQuery(callbackQuery.getId(), "➕ Добавляем адрес...");
                orderCreationHandler.handleAddStopDecision(telegramId, chatId, true);
                
            } else if (callbackData.equals("add_stop_no")) {
                // Пользователь не хочет добавлять больше точек
                answerCallbackQuery(callbackQuery.getId(), "✅ Завершаем...");
                orderCreationHandler.handleAddStopDecision(telegramId, chatId, false);
                
            } else if (callbackData.startsWith("confirm_additional_price_")) {
                // Подтверждение цены дополнительной точки
                String priceStr = callbackData.replace("confirm_additional_price_", "");
                answerCallbackQuery(callbackQuery.getId(), "✅ Цена подтверждена");
                orderCreationHandler.handleAdditionalPriceConfirmation(telegramId, chatId, new java.math.BigDecimal(priceStr));

            // ===== МОИ ЗАКАЗЫ: ОТМЕНА И РЕДАКТИРОВАНИЕ =====
            } else if (callbackData.startsWith("order_cancel_ok_")) {
                // Пользователь подтвердил отмену заказа
                String orderIdStr = callbackData.replace("order_cancel_ok_", "");
                answerCallbackQuery(callbackQuery.getId(), "Отменяю заказ...");
                handleOrderCancelConfirm(chatId, orderIdStr);

            } else if (callbackData.equals("order_cancel_no")) {
                // Пользователь передумал отменять
                answerCallbackQuery(callbackQuery.getId(), "Ок, заказ не отменён");
                sendMessage(chatId, "✅ Заказ остаётся в силе.");

            } else if (callbackData.startsWith("order_cancel_")) {
                // Нажали "Отменить" под заказом — показываем подтверждение
                String orderIdStr = callbackData.replace("order_cancel_", "");
                answerCallbackQuery(callbackQuery.getId(), "Отменить заказ?");
                handleOrderCancelAsk(chatId, orderIdStr);

            } else if (callbackData.startsWith("order_edit_")) {
                answerCallbackQuery(callbackQuery.getId(), "✏️ Редактирование");
                dispatchOrderEdit(telegramId, chatId, callbackData);

            } else if (callbackData.equals("orders_select")) {
                // Начать выбор заказа по номеру / ID из последнего списка
                answerCallbackQuery(callbackQuery.getId(), "🔎 Выбор заказа");
                myOrdersSelectionHandler.startSelection(telegramId, chatId);

            // ===== КУРЬЕР: ВЫБРАТЬ ЗАКАЗ ИЗ СПИСКА =====
            } else if (callbackData.equals("courier_orders_select")) {
                answerCallbackQuery(callbackQuery.getId(), "🔎 Выбор заказа");
                courierAvailableOrdersHandler.startSelection(telegramId, chatId);

            // ===== КУРЬЕР: СМЕНА СТАТУСА ЗАКАЗА =====
            } else if (callbackData.startsWith("courier_order_next:")) {
                String orderIdStr = callbackData.replace("courier_order_next:", "");
                Integer listMessageId = callbackQuery.getMessage().getMessageId();
                handleCourierOrderNextStatus(telegramId, chatId, callbackQuery.getId(), orderIdStr, listMessageId);
            } else if (callbackData.startsWith("courier_stop_delivered:")) {
                String rest = callbackData.replace("courier_stop_delivered:", "");
                Integer listMessageId = callbackQuery.getMessage().getMessageId();
                handleCourierStopDelivered(telegramId, chatId, callbackQuery.getId(), rest, listMessageId);
            } else {
                log.warn("Неизвестный callback_data: {}", callbackData);
                answerCallbackQuery(callbackQuery.getId(), "❌ Неизвестная команда");
            }

        } catch (Exception e) {
            log.error("Ошибка при обработке callback query: telegramId={}, callbackData={}",
                    telegramId, callbackData, e);
            answerCallbackQuery(callbackQuery.getId(), "❌ Произошла ошибка. Попробуй позже.");
        }
    }

    /**
     * Обработать выбор роли (Магазин или Курьер)
     *
     * @param callbackData - данные кнопки ("role_shop" или "role_courier")
     * @param telegramId - Telegram ID пользователя
     * @param chatId - ID чата (куда отправить ответ)
     */
    private void handleRoleSelection(String callbackData, Long telegramId, Long chatId) {
        Role selectedRole;

        // Определяем роль из callback_data
        if (callbackData.equals("role_shop")) {
            selectedRole = Role.SHOP;
        } else if (callbackData.equals("role_courier")) {
            selectedRole = Role.COURIER;
        } else {
            log.warn("Неизвестная роль в callback_data: {}", callbackData);
            sendMessage(chatId, "❌ Неизвестная роль. Попробуй еще раз.");
            return;
        }

        try {
            // Обновляем роль пользователя в БД
            userService.updateUserRole(telegramId, selectedRole);
            log.info("Роль успешно обновлена: telegramId={}, role={}", telegramId, selectedRole);

            // Разная логика для разных ролей
            if (selectedRole == Role.SHOP) {
                // Для магазина — сразу запускаем регистрацию магазина
                sendMessage(chatId, "✅ Отлично! Ты выбрал роль: *Магазин*\n\n" +
                        "Теперь давай заполним информацию о твоём магазине.");
                shopRegistrationHandler.startRegistrationFromCallback(telegramId, chatId);
            } else if (selectedRole == Role.COURIER) {
                // Для курьера — запускаем регистрацию курьера (запрос телефона)
                sendMessage(chatId, "✅ Отлично! Ты выбрал роль: *Курьер*.\n\n" +
                        "Сейчас зарегистрируем тебя как курьера.\n" +
                        "Нажми кнопку ниже и поделись номером телефона.");

                courierRegistrationHandler.startRegistrationFromCallback(telegramId, chatId, null);
            }

        } catch (IllegalArgumentException e) {
            // Пользователь не найден (не должен случиться, но на всякий случай)
            log.error("Пользователь не найден при обновлении роли: telegramId={}", telegramId);
            sendMessage(chatId, "❌ Ошибка: пользователь не найден. Попробуй /start");
        } catch (Exception e) {
            log.error("Ошибка при обновлении роли: telegramId={}, role={}", telegramId, selectedRole, e);
            sendMessage(chatId, "❌ Произошла ошибка при сохранении роли. Попробуй позже.");
        }
    }

    /**
     * Показать информацию о магазине (личный кабинет).
     */
    private void handleShopInfo(Long telegramId, Long chatId) {
        var shopOptional = shopService.findByUserTelegramId(telegramId);
        
        if (shopOptional.isEmpty()) {
            sendMessage(chatId, "❌ У тебя нет зарегистрированного магазина.");
            return;
        }
        
        Shop shop = shopOptional.get();
        
        String status = shop.getIsActive() ? "✅ Активен" : "⏳ Ожидает активации";
        
        sendMessage(chatId, "🏪 *Мой магазин*\n\n" +
                "📋 *Информация:*\n" +
                "• Название: " + shop.getShopName() + "\n" +
                "• Адрес забора: " + shop.getPickupAddress() + "\n" +
                "• Телефон: " + shop.getPhone() + "\n" +
                "• Статус: " + status + "\n\n" +
                "📅 Зарегистрирован: " + shop.getCreatedAt().toLocalDate());
    }

    /**
     * Отправить сообщение пользователю
     *
     * @param chatId - ID чата (куда отправить)
     * @param text - текст сообщения
     */
    private void sendMessage(Long chatId, String text) {
        try {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .parseMode("Markdown")  // Поддержка Markdown (жирный текст, курсив и т.д.)
                    .build();

            bot.execute(message);
            log.debug("Сообщение отправлено: chatId={}", chatId);

        } catch (TelegramApiException e) {
            log.error("Ошибка при отправке сообщения: chatId={}", chatId, e);
        }
    }

    /**
     * Направить callback order_edit_* в нужный метод OrderEditHandler.
     */
    private void dispatchOrderEdit(Long telegramId, Long chatId, String callbackData) {
        if (callbackData.contains("_date_today") || callbackData.contains("_date_tomorrow")) {
            orderEditHandler.handleDateSelected(telegramId, chatId, callbackData);
        } else if (callbackData.contains("_date") && !callbackData.contains("_date_to")) {
            orderEditHandler.handleEditDateMenu(telegramId, chatId, callbackData);
        } else if (callbackData.contains("_address") || callbackData.contains("_phone") || callbackData.contains("_comment")) {
            orderEditHandler.handleSelectField(telegramId, chatId, callbackData);
        } else if (callbackData.contains("_stop_")) {
            orderEditHandler.handleSelectPoint(telegramId, chatId, callbackData);
        } else {
            orderEditHandler.handleEditMenu(telegramId, chatId, callbackData);
        }
    }

    /**
     * Показать подтверждение отмены заказа: "Точно отменить?" и кнопки [Да] [Нет].
     */
    private void handleOrderCancelAsk(Long chatId, String orderIdStr) {
        String text = "❓ *Точно отменить этот заказ?*";
        InlineKeyboardButton btnYes = InlineKeyboardButton.builder()
                .text("Да, отменить")
                .callbackData("order_cancel_ok_" + orderIdStr)
                .build();
        InlineKeyboardButton btnNo = InlineKeyboardButton.builder()
                .text("Нет")
                .callbackData("order_cancel_no")
                .build();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(List.of(btnYes, btnNo)));
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(markup)
                .build();
        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки подтверждения отмены: chatId={}", chatId, e);
        }
    }

    /**
     * Выполнить отмену заказа (после нажатия "Да, отменить").
     */
    private void handleOrderCancelConfirm(Long chatId, String orderIdStr) {
        UUID orderId;
        try {
            orderId = UUID.fromString(orderIdStr);
        } catch (IllegalArgumentException e) {
            sendMessage(chatId, "❌ Ошибка: неверный ID заказа.");
            return;
        }
        boolean cancelled = orderService.cancelOrder(orderId);
        if (cancelled) {
            sendMessage(chatId, "✅ *Заказ отменён.*\n\nНажми «📋 Мои заказы», чтобы обновить список.");
        } else {
            sendMessage(chatId, "❌ Не удалось отменить заказ.\nВозможно, он уже принят курьером или не найден.");
        }
    }

    /**
     * Курьер нажал «следующий статус» у заказа — переводим заказ в следующий статус.
     * listMessageId — message_id сообщения «Мои заказы», чтобы отредактировать его вместо нового сообщения.
     */
    private void handleCourierOrderNextStatus(Long telegramId, Long chatId, String callbackQueryId, String orderIdStr, Integer listMessageId) {
        UUID orderId;
        try {
            orderId = UUID.fromString(orderIdStr);
        } catch (IllegalArgumentException e) {
            answerCallbackQuery(callbackQueryId, "❌ Неверный ID заказа");
            return;
        }
        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty() || !Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
            answerCallbackQuery(callbackQueryId, "❌ Нет активного профиля курьера");
            return;
        }
        var orderOpt = orderService.findById(orderId);
        if (orderOpt.isEmpty()) {
            answerCallbackQuery(callbackQueryId, "❌ Заказ не найден");
            return;
        }
        OrderStatus current = orderOpt.get().getStatus();
        OrderStatus next = nextStatusForCourier(current);
        if (next == null) {
            answerCallbackQuery(callbackQueryId, "❌ Для этого заказа смена статуса недоступна");
            return;
        }
        // «В магазине» и «Вручил» — подтверждаем через геолокацию (2 в 1)
        if (next == OrderStatus.IN_SHOP || next == OrderStatus.DELIVERED) {
            answerCallbackQuery(callbackQueryId, "📍 Отправьте геолокацию");
            courierGeoHandler.requestLocationForStatus(telegramId, chatId, orderId, next, listMessageId);
            return;
        }
        boolean updated = orderService.updateOrderStatusByCourier(orderId, courierOpt.get().getUser(), next);
        if (updated) {
            answerCallbackQuery(callbackQueryId, "✅ " + next.getDisplayName());
            bot.editCourierMyOrdersMessage(chatId, listMessageId, telegramId);
        } else {
            answerCallbackQuery(callbackQueryId, "❌ Не удалось обновить статус");
        }
    }

    /**
     * Курьер нажал «Доставлено в точку N» (вариант B — поточное подтверждение мультиадреса).
     * Запрашиваем геолокацию, после неё в CourierGeoHandler вызывается markStopDelivered.
     */
    private void handleCourierStopDelivered(Long telegramId, Long chatId, String callbackQueryId, String rest, Integer listMessageId) {
        String[] parts = rest.split(":", 2);
        if (parts.length != 2) {
            answerCallbackQuery(callbackQueryId, "❌ Ошибка данных");
            return;
        }
        UUID orderId;
        try {
            orderId = UUID.fromString(parts[0]);
        } catch (IllegalArgumentException e) {
            answerCallbackQuery(callbackQueryId, "❌ Неверный ID заказа");
            return;
        }
        int stopNumber;
        try {
            stopNumber = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            answerCallbackQuery(callbackQueryId, "❌ Неверный номер точки");
            return;
        }
        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty() || !Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
            answerCallbackQuery(callbackQueryId, "❌ Нет активного профиля курьера");
            return;
        }
        var orderOpt = orderService.findById(orderId);
        if (orderOpt.isEmpty() || orderOpt.get().getStatus() != OrderStatus.ON_WAY) {
            answerCallbackQuery(callbackQueryId, "❌ Заказ не найден или не в пути");
            return;
        }
        List<OrderStop> stops = orderService.getOrderStops(orderId);
        OrderStop targetStop = stops.stream()
                .filter(s -> s.getStopNumber() != null && s.getStopNumber() == stopNumber)
                .findFirst()
                .orElse(null);
        if (targetStop == null || targetStop.isDelivered()) {
            answerCallbackQuery(callbackQueryId, "❌ Точка уже доставлена или не найдена");
            return;
        }
        answerCallbackQuery(callbackQueryId, "📍 Отправьте геолокацию");
        courierGeoHandler.requestLocationForStopDelivery(telegramId, chatId, orderId, stopNumber, listMessageId);
    }

    private static OrderStatus nextStatusForCourier(OrderStatus current) {
        return switch (current) {
            case ACCEPTED -> OrderStatus.IN_SHOP;
            case IN_SHOP -> OrderStatus.ON_WAY;
            case PICKED_UP -> OrderStatus.ON_WAY;
            case ON_WAY -> OrderStatus.DELIVERED;
            default -> null;
        };
    }

    /**
     * Ответить на callback query (ОБЯЗАТЕЛЬНО! иначе кнопка будет "висеть")
     *
     * @param callbackQueryId - ID callback query (нужен для ответа)
     * @param text - текст уведомления (показывается пользователю как всплывающее сообщение)
     */
    private void answerCallbackQuery(String callbackQueryId, String text) {
        try {
            AnswerCallbackQuery answer = AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQueryId)
                    .text(text)
                    .showAlert(false)  // false = маленькое уведомление, true = всплывающее окно
                    .build();

            bot.execute(answer);
            log.debug("Callback query ответ отправлен: callbackQueryId={}", callbackQueryId);

        } catch (TelegramApiException e) {
            log.error("Ошибка при отправке ответа на callback query: callbackQueryId={}", callbackQueryId, e);
        }
    }
}
