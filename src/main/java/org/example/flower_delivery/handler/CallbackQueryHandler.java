package org.example.flower_delivery.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.Bot;
import org.example.flower_delivery.model.Order;
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
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    @Autowired
    private org.example.flower_delivery.handler.CourierDepositHandler courierDepositHandler;

    // Сервис заказов (для отмены, редактирования и работы курьера)
    private final org.example.flower_delivery.service.OrderService orderService;

    private final org.example.flower_delivery.service.OrderBundleService orderBundleService;

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

        log.debug("Callback: telegramId={}, data={}", telegramId, callbackData);

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
            } else if (callbackData.startsWith("delivery_interval_")) {
                // Выбор интервала доставки при создании заказа
                answerCallbackQuery(callbackQuery.getId(), "⏰ Интервал выбран");
                orderCreationHandler.handleDeliveryIntervalSelection(telegramId, chatId, callbackData);
            } else if (callbackData.startsWith("confirm_price_")) {
                // Подтверждение автоматически рассчитанной цены
                String priceStr = callbackData.replace("confirm_price_", "");
                answerCallbackQuery(callbackQuery.getId(), "✅ Цена подтверждена");
                orderCreationHandler.handlePriceConfirmation(telegramId, chatId, new java.math.BigDecimal(priceStr));
            } else if (callbackData.equals("order_creation_cancel")) {
                answerCallbackQuery(callbackQuery.getId(), "Заказ отменён");
                orderCreationHandler.cancelOrderCreation(telegramId, chatId);
                
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

            // ===== КУРЬЕР: ПРОСМОТР ЗАКАЗА (нажали на кнопку заказа) =====
            } else if (callbackData.startsWith("courier_order_view:")) {
                String orderIdStr = callbackData.replace("courier_order_view:", "");
                answerCallbackQuery(callbackQuery.getId(), "📋 Заказ");
                handleCourierOrderView(telegramId, chatId, orderIdStr);
            // ===== КУРЬЕР: ЗАБРАТЬ ЗАКАЗ (из детального просмотра) =====
            } else if (callbackData.startsWith("courier_order_take:")) {
                String orderIdStr = callbackData.replace("courier_order_take:", "");
                answerCallbackQuery(callbackQuery.getId(), "✅ Назначаю заказ...");
                handleCourierOrderTake(telegramId, chatId, orderIdStr);
            } else if (callbackData.startsWith("courier_phone:")) {
                answerCallbackQuery(callbackQuery.getId(), "📞 Телефон");
                handleCourierPhoneRequest(chatId, callbackData);
            // РЕАЛИЗОВАНО: пагинация «Доступные заказы» — редактируем сообщение
            } else if (callbackData.startsWith("courier_orders_page:")) {
                String pageStr = callbackData.replace("courier_orders_page:", "");
                int page = 0;
                try {
                    page = Integer.parseInt(pageStr);
                } catch (NumberFormatException ignored) {}
                Integer messageId = callbackQuery.getMessage().getMessageId();
                courierAvailableOrdersHandler.showAvailableOrdersPage(telegramId, chatId, page, messageId);
                answerCallbackQuery(callbackQuery.getId(), "Страница " + (page + 1));

            // ===== КУРЬЕР: СМЕНА СТАТУСА ЗАКАЗА =====
            } else if (callbackData.startsWith("courier_order_next:")) {
                String orderIdStr = callbackData.replace("courier_order_next:", "");
                Integer listMessageId = callbackQuery.getMessage().getMessageId();
                handleCourierOrderNextStatus(telegramId, chatId, callbackQuery.getId(), orderIdStr, listMessageId);
            } else if (callbackData.startsWith("courier_stop_delivered:")) {
                String rest = callbackData.replace("courier_stop_delivered:", "");
                Integer listMessageId = callbackQuery.getMessage().getMessageId();
                handleCourierStopDelivered(telegramId, chatId, callbackQuery.getId(), rest, listMessageId);
            } else if (callbackData.equals("courier_deposit_topup")) {
                answerCallbackQuery(callbackQuery.getId(), "💳 Пополнение депозита");
                courierDepositHandler.startTopUp(telegramId, chatId);
            } else if (callbackData.startsWith("shop_pickup_confirm:")) {
                handleShopPickupConfirmation(telegramId, chatId, callbackQuery);
            } else if (callbackData.startsWith("courier_tx_page:")) {
                String offsetStr = callbackData.replace("courier_tx_page:", "");
                int offset = 0;
                try {
                    offset = Integer.parseInt(offsetStr);
                } catch (NumberFormatException ignored) {
                }
                Integer messageId = callbackQuery.getMessage().getMessageId();
                bot.editCourierStatsMessage(chatId, messageId, telegramId, offset);
                answerCallbackQuery(callbackQuery.getId(), "Обновляю операции...");
            // ===== КУРЬЕР: ОТМЕНА ЗАКАЗА (выбор по номеру) =====
            } else if (callbackData.equals("courier_cancel_select")) {
                answerCallbackQuery(callbackQuery.getId(), "⛔ Выбери заказ для отмены");
                bot.startCourierCancelSelection(telegramId, chatId);
            } else if (callbackData.startsWith("courier_order_cancel_ask:")) {
                String orderIdStr = callbackData.replace("courier_order_cancel_ask:", "");
                answerCallbackQuery(callbackQuery.getId(), "❓ Отменить заказ?");
                handleCourierOrderCancelAsk(chatId, orderIdStr);
            } else if (callbackData.startsWith("courier_order_cancel_ok_")) {
                String orderIdStr = callbackData.replace("courier_order_cancel_ok_", "");
                Integer listMessageId = callbackQuery.getMessage().getMessageId();
                handleCourierOrderCancelConfirm(telegramId, chatId, callbackQuery.getId(), orderIdStr, listMessageId);
            } else if (callbackData.equals("courier_order_cancel_no")) {
                answerCallbackQuery(callbackQuery.getId(), "Ок, не отменяем");
            // ===== КУРЬЕР: ВОЗВРАТ В МАГАЗИН =====
            } else if (callbackData.startsWith("courier_order_return_ask:")) {
                String orderIdStr = callbackData.replace("courier_order_return_ask:", "");
                answerCallbackQuery(callbackQuery.getId(), "❓ Вернуть заказ в магазин?");
                handleCourierOrderReturnAsk(chatId, orderIdStr);
            } else if (callbackData.startsWith("courier_order_return_ok_")) {
                String orderIdStr = callbackData.replace("courier_order_return_ok_", "");
                Integer listMessageId = callbackQuery.getMessage().getMessageId();
                handleCourierOrderReturnConfirm(telegramId, chatId, callbackQuery.getId(), orderIdStr, listMessageId);
            } else if (callbackData.equals("courier_order_return_no")) {
                answerCallbackQuery(callbackQuery.getId(), "Ок, не возвращаем");
            // ===== КУРЬЕР: ВЗЯТЬ СВЯЗКУ ЗАКАЗОВ =====
            } else if (callbackData.startsWith("courier_bundle_take:")) {
                String indicesStr = callbackData.replace("courier_bundle_take:", "");
                answerCallbackQuery(callbackQuery.getId(), "📦 Назначаю связку...");
                handleCourierBundleTake(telegramId, chatId, indicesStr);
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
                // Для курьера: если уже есть профиль курьера — показываем меню сразу.
                var courierOpt = courierService.findByTelegramId(telegramId);
                if (courierOpt.isPresent()) {
                    if (Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
                        sendMessage(chatId, "✅ Ты уже зарегистрирован как курьер.\n\n" +
                                "Вот твоё меню курьера ниже.");
                        bot.sendCourierMenu(chatId, "🚴 *Меню курьера*");
                    } else {
                        sendMessage(chatId, "⏳ Ты уже зарегистрирован как курьер.\n\n" +
                                "Профиль ждёт активации администратором. После активации ты сможешь брать заказы.");
                    }
                } else {
                    // Нет курьера — запускаем регистрацию курьера (запрос телефона)
                    sendMessage(chatId, "✅ Отлично! Ты выбрал роль: *Курьер*.\n\n" +
                            "Сейчас зарегистрируем тебя как курьера.\n" +
                            "Нажми кнопку ниже и поделись номером телефона.");

                    courierRegistrationHandler.startRegistrationFromCallback(telegramId, chatId, null);
                }
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
        sendMessage(chatId, text, null);
    }

    private void sendMessage(Long chatId, String text, InlineKeyboardMarkup replyMarkup) {
        try {
            SendMessage.SendMessageBuilder builder = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .parseMode("Markdown");
            if (replyMarkup != null) {
                builder.replyMarkup(replyMarkup);
            }
            bot.execute(builder.build());
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
     * Показать подтверждение отмены заказа курьером.
     */
    private void handleCourierOrderCancelAsk(Long chatId, String orderIdStr) {
        log.info("Курьер запросил подтверждение отмены заказа: orderIdStr={}", orderIdStr);
        String text = "❓ *Отменить этот заказ как курьер?*\n\n" +
                "После отмены заказ уйдёт из твоего списка и вернётся в работу магазину/админу.";
        InlineKeyboardButton btnYes = InlineKeyboardButton.builder()
                .text("Да, отменить заказ")
                .callbackData("courier_order_cancel_ok_" + orderIdStr)
                .build();
        InlineKeyboardButton btnNo = InlineKeyboardButton.builder()
                .text("Нет")
                .callbackData("courier_order_cancel_no")
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
            log.error("Ошибка отправки подтверждения отмены курьером: chatId={}", chatId, e);
        }
    }

    /**
     * Показать подтверждение возврата заказа в магазин курьером.
     */
    private void handleCourierOrderReturnAsk(Long chatId, String orderIdStr) {
        String text = "❓ *Вернуть заказ в магазин?*";
        InlineKeyboardButton btnYes = InlineKeyboardButton.builder()
                .text("Да, вернуть")
                .callbackData("courier_order_return_ok_" + orderIdStr)
                .build();
        InlineKeyboardButton btnNo = InlineKeyboardButton.builder()
                .text("Нет")
                .callbackData("courier_order_return_no")
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
            log.error("Ошибка отправки подтверждения возврата курьером: chatId={}", chatId, e);
        }
    }

    /**
     * Показать детали заказа курьеру (нажал на кнопку заказа в списке).
     */
    private void handleCourierOrderView(Long telegramId, Long chatId, String orderIdStr) {
        UUID orderId;
        try {
            orderId = UUID.fromString(orderIdStr);
        } catch (IllegalArgumentException e) {
            sendMessage(chatId, "❌ Неверный ID заказа.");
            return;
        }
        var orderOpt = orderService.getOrderWithShop(orderId);
        if (orderOpt.isEmpty()) {
            sendMessage(chatId, "❌ Заказ не найден.");
            return;
        }
        Order order = orderOpt.get();
        if (order.getStatus() != OrderStatus.NEW) {
            sendMessage(chatId, "❌ Этот заказ уже взят другим курьером.");
            return;
        }
        String shopName = order.getShop() != null && order.getShop().getShopName() != null
                ? order.getShop().getShopName() : "—";
        String timeRange = order.getDeliveryInterval() != null
                ? order.getDeliveryInterval().getTimeRange() : "—";
        StringBuilder sb = new StringBuilder();
        sb.append("*Заказ ").append(shopName).append("*\n\n");
        sb.append("👤 *Получатель:* ").append(order.getRecipientName()).append(" (").append(order.getRecipientPhone()).append(")\n");
        sb.append("📞 *Клиент:* ").append(order.getRecipientPhone()).append("\n");
        sb.append("📍 *Адрес:* ").append(order.getDeliveryAddress()).append("\n");
        sb.append("⏰ *Период доставки:* ").append(timeRange).append("\n");
        sb.append("💬 *Комментарий:* ").append(order.getComment() != null ? order.getComment() : "—").append("\n");
        sb.append("💰 *Стоимость доставки:* ").append(order.getDeliveryPrice()).append(" руб.");
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        row1.add(InlineKeyboardButton.builder().text("📞 Получатель").callbackData("courier_phone:" + orderId + ":recipient").build());
        row1.add(InlineKeyboardButton.builder().text("📞 Заказчик").callbackData("courier_phone:" + orderId + ":client").build());
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        row2.add(InlineKeyboardButton.builder().text("✅ Забрать заказ").callbackData("courier_order_take:" + orderId).build());
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(List.of(row1, row2));
        sendMessage(chatId, sb.toString(), markup);
    }

    /**
     * Курьер нажал «Получатель» или «Заказчик» — отправляем телефон для звонка.
     */
    private void handleCourierPhoneRequest(Long chatId, String callbackData) {
        String rest = callbackData.replace("courier_phone:", "");
        String[] parts = rest.split(":", 2);
        if (parts.length < 2) return;
        UUID orderId;
        try {
            orderId = UUID.fromString(parts[0]);
        } catch (IllegalArgumentException e) {
            return;
        }
        var orderOpt = orderService.findById(orderId);
        if (orderOpt.isEmpty()) return;
        Order order = orderOpt.get();
        String phone = order.getRecipientPhone();
        String label = "recipient".equals(parts[1]) ? "Получатель" : "Заказчик";
        sendMessage(chatId, "📞 *" + label + ":* " + phone + "\n\n_Нажми на номер, чтобы позвонить._");
    }

    /**
     * Курьер нажал «Забрать заказ» — назначаем заказ.
     */
    private void handleCourierOrderTake(Long telegramId, Long chatId, String orderIdStr) {
        UUID orderId;
        try {
            orderId = UUID.fromString(orderIdStr);
        } catch (IllegalArgumentException e) {
            sendMessage(chatId, "❌ Неверный ID заказа.");
            return;
        }
        courierAvailableOrdersHandler.takeOrderById(telegramId, chatId, orderId);
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
     * Курьер подтвердил отмену заказа — переходим к вводу причины.
     */
    private void handleCourierOrderCancelConfirm(Long telegramId, Long chatId, String callbackQueryId,
                                                 String orderIdStr, Integer listMessageId) {
        UUID orderId;
        try {
            orderId = UUID.fromString(orderIdStr);
        } catch (IllegalArgumentException e) {
            log.warn("🚨 Курьер прислал некорректный ID заказа при отмене: telegramId={}, raw={}", telegramId, orderIdStr);
            answerCallbackQuery(callbackQueryId, "❌ Неверный ID заказа");
            return;
        }
        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty() || !Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
            answerCallbackQuery(callbackQueryId, "❌ Нет активного профиля курьера");
            return;
        }
        answerCallbackQuery(callbackQueryId, "📝 Укажи причину отмены");
        bot.startAwaitingCancelReason(telegramId, chatId, orderId, false);
    }

    /**
     * Курьер подтвердил возврат заказа в магазин — переходим к вводу причины.
     */
    private void handleCourierOrderReturnConfirm(Long telegramId, Long chatId, String callbackQueryId,
                                                 String orderIdStr, Integer listMessageId) {
        UUID orderId;
        try {
            orderId = UUID.fromString(orderIdStr);
        } catch (IllegalArgumentException e) {
            log.warn("🚨 Курьер прислал некорректный ID заказа при возврате в магазин: telegramId={}, raw={}", telegramId, orderIdStr);
            answerCallbackQuery(callbackQueryId, "❌ Неверный ID заказа");
            return;
        }
        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty() || !Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
            answerCallbackQuery(callbackQueryId, "❌ Нет активного профиля курьера");
            return;
        }
        answerCallbackQuery(callbackQueryId, "📝 Укажи причину возврата");
        bot.startAwaitingCancelReason(telegramId, chatId, orderId, true);
    }

    /**
     * Курьер нажал «Взять связку» — назначаем все заказы связки.
     */
    private void handleCourierBundleTake(Long telegramId, Long chatId, String indicesStr) {
        List<Integer> indices = new java.util.ArrayList<>();
        for (String s : indicesStr.split(",")) {
            try {
                indices.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (indices.size() < 2 || indices.size() > 3) {
            sendMessage(chatId, "❌ Некорректная связка. Попробуй снова через «📋 Доступные заказы».");
            return;
        }
        List<UUID> orderIds = courierAvailableOrdersHandler.getOrderIdsForIndices(telegramId, indices);
        if (orderIds.size() != indices.size()) {
            sendMessage(chatId, "❌ Список заказов устарел.\nНажми «📋 Доступные заказы» и попробуй снова.");
            return;
        }
        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty() || !Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
            sendMessage(chatId, "❌ Нет активного профиля курьера.");
            return;
        }
        List<Order> assigned = orderService.assignBundleToCourier(orderIds, courierOpt.get().getUser());
        if (assigned.isEmpty()) {
            sendMessage(chatId, "❌ Не удалось взять связку.\n" +
                    "Возможно: заказы уже заняты, превышен лимит (3 активных) или не хватает средств на депозите.");
            return;
        }
        List<Order> withShops = new ArrayList<>();
        for (Order o : assigned) {
            withShops.add(orderService.getOrderWithShop(o.getId()).orElse(o));
        }
        // Порядок по оптимальному маршруту (забор1→доставка1→забор2→доставка2), чтобы текст и 2ГИС совпадали
        List<Order> forDisplay = orderBundleService.reorderByOptimalRoute(withShops);

        StringBuilder sb = new StringBuilder();
        sb.append("✅ *Связка взята!* (").append(forDisplay.size()).append(" заказов)\n\n");
        sb.append("_Порядок ниже = порядок по маршруту (сначала забор, потом доставка, затем следующий забор и т.д.)._\n\n");
        for (int i = 0; i < forDisplay.size(); i++) {
            Order o = forDisplay.get(i);
            sb.append(i + 1).append(". ").append(o.getRecipientName()).append(" — ").append(o.getDeliveryAddress()).append("\n");
            if (o.getEffectivePickupAddress() != null) {
                sb.append("   🏪 Забрать: ").append(o.getEffectivePickupAddress()).append("\n");
            }
        }
        sb.append("\nНажми «🚚 Мои заказы» для смены статусов.");

        // Кнопки маршрута (Яндекс/2ГИС) — только после взятия связки: курьер → забор1 → доставка1 → забор2 → доставка2
        InlineKeyboardMarkup markup = null;
        var courier = courierOpt.get();
        if (courier.getLastLatitude() != null && courier.getLastLongitude() != null) {
            double lat = courier.getLastLatitude().doubleValue();
            double lon = courier.getLastLongitude().doubleValue();
            Optional<org.example.flower_delivery.service.OrderBundleService.OrderBundle> routeOpt =
                    orderBundleService.buildRouteForOrders(forDisplay, lat, lon);
            if (routeOpt.isPresent()) {
                var bundle = routeOpt.get();
                List<InlineKeyboardButton> row = List.of(
                        InlineKeyboardButton.builder().text("🌍 Яндекс").url(bundle.yandexRouteUrl()).build(),
                        InlineKeyboardButton.builder().text("🗺 2ГИС").url(bundle.twoGisRouteUrl()).build()
                );
                markup = new InlineKeyboardMarkup(List.of(row));
            }
        }
        sendMessage(chatId, sb.toString(), markup);
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
            if (next == OrderStatus.ON_WAY && orderService.markShopPickupConfirmationRequested(orderId)) {
                orderService.getOrderForShopPickupMessage(orderId)
                        .ifPresent(bot::sendShopPickupConfirmationRequest);
            }
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

    /**
     * Магазин нажал «ДА» или «Нет» на запрос «Курьер забрал заказ?».
     */
    private void handleShopPickupConfirmation(Long telegramId, Long chatId, CallbackQuery callbackQuery) {
        String data = callbackQuery.getData();
        String rest = data.replace("shop_pickup_confirm:", "");
        String[] parts = rest.split(":", 2);
        if (parts.length != 2) {
            answerCallbackQuery(callbackQuery.getId(), "❌ Ошибка данных");
            return;
        }
        UUID orderId;
        try {
            orderId = UUID.fromString(parts[0]);
        } catch (IllegalArgumentException e) {
            answerCallbackQuery(callbackQuery.getId(), "❌ Неверный заказ");
            return;
        }
        boolean confirmed = "yes".equalsIgnoreCase(parts[1]);
        var orderOpt = orderService.getOrderForShopPickupMessage(orderId);
        if (orderOpt.isEmpty()) {
            answerCallbackQuery(callbackQuery.getId(), "❌ Заказ не найден");
            return;
        }
        Order order = orderOpt.get();
        if (order.getShop() == null || order.getShop().getUser() == null
                || !java.util.Objects.equals(order.getShop().getUser().getTelegramId(), telegramId)) {
            answerCallbackQuery(callbackQuery.getId(), "❌ Это не ваш заказ");
            return;
        }
        boolean updated = orderService.setShopPickupConfirmed(orderId, confirmed);
        if (!updated) {
            answerCallbackQuery(callbackQuery.getId(), "Вы уже ответили на этот запрос");
            return;
        }
        // Редактируем исходное сообщение — убираем кнопки и показываем ответ (одно сообщение, без дублирования)
        try {
            EditMessageText edit = new EditMessageText();
            edit.setChatId(chatId.toString());
            edit.setMessageId(callbackQuery.getMessage().getMessageId());
            edit.setText(confirmed ? "✅ Подтверждено: вы передали заказ курьеру." : "❌ Вы ответили: Нет — заказ курьеру не передан.");
            edit.setReplyMarkup(null);
            bot.execute(edit);
        } catch (TelegramApiException e) {
            log.debug("Редактирование сообщения магазину не выполнено (возможно, устаревшее): orderId={}", orderId, e);
        }
        answerCallbackQuery(callbackQuery.getId(), "");
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
