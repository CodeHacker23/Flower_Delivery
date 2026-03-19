package org.example.flower_delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.handler.CallbackQueryHandler;
import org.example.flower_delivery.handler.CourierRegistrationHandler;
import org.example.flower_delivery.handler.CourierDepositHandler;
import org.example.flower_delivery.handler.MyOrdersSelectionHandler;
import org.example.flower_delivery.handler.OrderCreationHandler;
import org.example.flower_delivery.handler.ShopRegistrationHandler;
import org.example.flower_delivery.handler.StartCommandHandler;
import org.example.flower_delivery.handler.CourierAvailableOrdersHandler;
import org.example.flower_delivery.model.Courier;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.OrderStatus;
import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.service.OrderBundleService;
import org.example.flower_delivery.service.OrderService;
import org.example.flower_delivery.service.ShopService;
import org.example.flower_delivery.service.UserService;
import org.example.flower_delivery.service.CourierTransactionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.format.DateTimeFormatter;

import static org.example.flower_delivery.model.OrderStatus.*;

/**
 * Главный класс бота - это как "мозг" который слушает сообщения от Telegram
 * 
 * TelegramLongPollingBot - это способ работы бота:
 * - Бот постоянно спрашивает у Telegram: "Есть новые сообщения?"
 * - Если есть - получает их и обрабатывает
 * - Это как постоянно проверять почтовый ящик
 * 
 * Есть еще WebhookBot (более продвинутый, но сложнее настраивать)
 * Для начала LongPolling - проще и надежнее
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Bot extends TelegramLongPollingBot {
    
    // @Value - говорит Spring: "Возьми значение из application.properties"
    // ${telegram.bot.token} - имя свойства из properties файла
    // Если свойства нет - упадет с ошибкой (и правильно, блять!)
    @Value("${telegram.bot.token}")
    private String botToken;
    
    @Value("${telegram.bot.username}")
    private String botUsername;
    
    // Инжектируем обработчик команды /start (Spring автоматически подставит!)
    private final StartCommandHandler startCommandHandler;
    
    // Инжектируем обработчик нажатий на кнопки (Spring автоматически подставит!)
    private final CallbackQueryHandler callbackQueryHandler;
    
    // Инжектируем обработчик регистрации магазина
    private final ShopRegistrationHandler shopRegistrationHandler;
    
    // Инжектируем обработчик регистрации курьера
    private final CourierRegistrationHandler courierRegistrationHandler;

    // Обработчик пополнения депозита курьера
    private final CourierDepositHandler courierDepositHandler;
    
    // Инжектируем обработчик создания заказа
    private final OrderCreationHandler orderCreationHandler;

    // Обработчик выбора заказа из списка \"Мои заказы\"
    private final MyOrdersSelectionHandler myOrdersSelectionHandler;

    // Обработчик выбора заказа курьером из списка доступных
    private final CourierAvailableOrdersHandler courierAvailableOrdersHandler;

    // Обработчик геолокации курьера (подтверждение «В магазине» / «Вручил» через локацию)
    private final org.example.flower_delivery.handler.CourierGeoHandler courierGeoHandler;

    // Обработчик редактирования заказа (меню + ввод нового значения)
    private final org.example.flower_delivery.handler.OrderEditHandler orderEditHandler;

    // Сервис связок заказов (подбор 2–3 заказов по пути)
    private final OrderBundleService orderBundleService;
    
    // Инжектируем сервис магазинов (для временной команды /activate)
    private final ShopService shopService;
    
    // Инжектируем сервис заказов (для просмотра заказов)
    private final OrderService orderService;

    // Сервис пользователей (для уведомлений админам и т.п.)
    private final UserService userService;

    // Сервис транзакций курьера (для истории депозита)
    private final CourierTransactionService courierTransactionService;

    // Инжектируем сервис курьеров (для временной активации командой /k)
    private final org.example.flower_delivery.service.CourierService courierService;

    /** Ожидание ввода номера заказа для отмены курьером: telegramId -> список UUID активных заказов. */
    private final Map<Long, List<UUID>> awaitingCancelSelection = new ConcurrentHashMap<>();

    /** Ожидание причины отмены/возврата: telegramId -> (orderId, isReturn). */
    private final Map<Long, PendingCancelReason> awaitingCancelReason = new ConcurrentHashMap<>();

    private record PendingCancelReason(UUID orderId, boolean isReturn) {}
    
    /**
     * Метод который вызывается КАЖДЫЙ РАЗ когда приходит новое сообщение/команда/кнопка
     * 
     * Update - это объект который содержит ВСЮ информацию о событии:
     * - Сообщение (текст, кто отправил, когда)
     * - Команда (/start, /help и т.д.)
     * - Нажатие на кнопку (callback)
     * - Геолокация, фото, документ - всё что угодно!
     * 
     * Сейчас метод:
     * 1. Проверяет команду /start и делегирует обработку StartCommandHandler
     * 2. Проверяет нажатие на кнопку (callback query) и делегирует CallbackQueryHandler
     */
    @Override
    public void onUpdateReceived(Update update) {
        Long telegramId = update.hasCallbackQuery()
                ? update.getCallbackQuery().getFrom().getId()
                : (update.hasMessage() && update.getMessage().getFrom() != null
                        ? update.getMessage().getFrom().getId()
                        : null);
        log.debug("Update received: telegramId={}, hasCallback={}, hasMessage={}",
                telegramId, update.hasCallbackQuery(), update.hasMessage());

        try {
            processUpdate(update);
        } catch (Exception e) {
            log.error("Ошибка при обработке update: telegramId={}", telegramId, e);
        }
    }

    /**
     * Точка входа для текстовых апдейтов из {@code TextUpdateHandler}.
     * В текущей версии {@code Bot} вся логика текстов живёт внутри {@link #processUpdate(Update)}.
     */
    public void processTextUpdate(Update update) {
        processUpdate(update);
    }

    private void processUpdate(Update update) {
        // Проверяем, есть ли нажатие на кнопку (callback query)
        if (update.hasCallbackQuery()) {
            callbackQueryHandler.handle(update);
            return;
        }
        
        // Проверяем, есть ли сообщение с контактом (кнопка "Поделиться номером")
        if (update.hasMessage() && update.getMessage().hasContact()) {
            // Сначала пробуем отдать контакт регистрации магазина
            if (shopRegistrationHandler.handleContact(update)) {
                return; // Контакт обработан регистрацией магазина
            }
            // Если не магазин — пробуем как регистрацию курьера
            if (courierRegistrationHandler.handleContact(update)) {
                return; // Контакт обработан регистрацией курьера
            }
        }

        // Проверяем, есть ли сообщение с фото (для селфи с паспортом курьера)
        if (update.hasMessage() && update.getMessage().hasPhoto()) {
            if (courierRegistrationHandler.handlePhoto(update)) {
                return; // Фото обработано регистрацией курьера
            }
        }

        // Геолокация курьера: подтверждение «В магазине»/«Вручил» или просто обновление последней точки
        if (update.hasMessage() && update.getMessage().hasLocation()) {
            Long telegramId = update.getMessage().getFrom().getId();
            Long chatId = update.getMessage().getChatId();
            var location = update.getMessage().getLocation();
            double lat = location.getLatitude();
            double lon = location.getLongitude();
            log.debug("Получена геолокация: telegramId={}, chatId={}", telegramId, chatId);
            // Сначала проверяем: курьер ждал гео для списка «Доступные заказы»?
            if (courierAvailableOrdersHandler.isAwaitingLocationForList(telegramId)) {
                courierAvailableOrdersHandler.handleLocationForAvailableList(telegramId, chatId, lat, lon);
                return;
            }
            if (courierGeoHandler.handleLocation(telegramId, chatId, lat, lon)) {
                return; // Обработано как подтверждение статуса (снимок + смена статуса)
            }
            // Иначе — просто обновить последнюю известную точку курьера
            courierService.updateLastLocation(telegramId, lat, lon);
            return;
        }
        
        // Проверяем, есть ли сообщение с текстом
        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText();
            Long telegramId = update.getMessage().getFrom().getId();
            Long chatId = update.getMessage().getChatId();

            // Команды обрабатываем ПЕРЕД пошаговыми сценариями, чтобы /start всегда мог "выйти" из регистрации.
            if (text.equals("/start")) {
                // Сбрасываем регистрацию магазина (если была) и передаём в StartCommandHandler.
                shopRegistrationHandler.cancelRegistration(telegramId);
                startCommandHandler.handle(update);
                return;
            }
            // ВРЕМЕННАЯ КОМАНДА: активировать свой магазин (для тестирования)
            if (text.equals("/r")) {
                handleActivateCommand(update);
                return;
            }
            // ВРЕМЕННАЯ КОМАНДА: активировать своего курьера (для тестирования)
            if (text.equals("/k")) {
                handleActivateCourierCommand(update);
                return;
            }

            // Пополнение депозита курьера — ввод суммы.
            if (courierDepositHandler.handleText(update)) {
                return;
            }

            // Если юзер в процессе регистрации курьера — обрабатываем его сообщение
            if (courierRegistrationHandler.handleText(update)) {
                return; // Сообщение обработано хендлером регистрации курьера
            }

            // Если юзер в процессе регистрации магазина — обрабатываем его сообщение
            if (shopRegistrationHandler.handleMessage(update)) {
                return; // Сообщение обработано хендлером регистрации
            }
            
            // Если юзер в процессе создания заказа — обрабатываем его сообщение
            if (orderCreationHandler.handleMessage(update)) {
                return; // Сообщение обработано хендлером создания заказа
            }

            // Если юзер выбирает заказ из списка \"Мои заказы\"
            if (myOrdersSelectionHandler.isAwaitingSelection(telegramId)) {
                if (myOrdersSelectionHandler.handleText(telegramId, chatId, text)) {
                    return;
                }
            }

            // Если курьер выбирает заказ из списка \"Доступные заказы\"
            if (courierAvailableOrdersHandler.isAwaitingSelection(telegramId)) {
                if (courierAvailableOrdersHandler.handleText(telegramId, chatId, text)) {
                    return;
                }
            }

            // Если курьер вводит причину отмены/возврата
            if (awaitingCancelReason.containsKey(telegramId)) {
                if (handleCourierCancelReasonText(telegramId, chatId, text)) {
                    return;
                }
            }

            // Если курьер выбирает номер заказа для отмены
            if (awaitingCancelSelection.containsKey(telegramId)) {
                if (handleCourierCancelSelectionText(telegramId, chatId, text)) {
                    return;
                }
            }

            // Если юзер в процессе редактирования заказа (ждёт ввод нового адреса/телефона/комментария)
            if (orderEditHandler.isEditing(telegramId)) {
                if (orderEditHandler.handleText(telegramId, chatId, text)) {
                    return;
                }
            }

            // После подтверждения «В магазине» курьеру показывается кнопка «В путь» — обрабатываем нажатие
            if (courierGeoHandler.isAwaitingOnWay(telegramId) && "🚗 В путь".equals(text)) {
                if (courierGeoHandler.handleOnWayButton(telegramId, chatId)) {
                    return;
                }
            }
            
            // Кнопка меню: Создать заказ
            if (text.equals("📦 Создать заказ")) {
                orderCreationHandler.startOrderCreation(telegramId, chatId);
            }
            // Кнопка меню: Мой магазин
            else if (text.equals("🏪 Мой магазин")) {
                handleShopInfoButton(update);
            }
            // Кнопка меню: Мои заказы (для магазина)
            else if (text.equals("📋 Мои заказы")) {
                handleMyOrdersButton(update);
            }
            // Кнопка меню курьера: Доступные заказы (курьер)
            else if (text.equals("📋 Доступные заказы")) {
                handleCourierAvailableOrdersButton(update);
            }
            // Кнопка меню курьера: Мои заказы (курьер)
            else if (text.equals("🚚 Мои заказы")) {
                handleCourierMyOrdersButton(update);
            }
            // Кнопка меню курьера: Моя статистика (курьер)
            else if (text.equals("💰 Моя статистика")) {
                handleCourierStatsButton(update);
            }
            // Кнопка меню: Информация (заглушка)
            else if (text.equals("ℹ️ Информация")) {
                Long chatIdInfo = update.getMessage().getChatId();
                sendSimpleMessage(chatIdInfo, "ℹ️ *Информация*\n\nМы это заполним позже, брат.");
            }
            // Здесь позже добавим обработку других команд (/help, /orders и т.д.)
        }
    }

    /**
     * Обработка кнопки "Мои заказы" — показать список заказов магазина.
     */
    private void handleMyOrdersButton(Update update) {
        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();
        
        // Находим магазин пользователя
        var shopOptional = shopService.findByUserTelegramId(telegramId);
        
        if (shopOptional.isEmpty()) {
            sendSimpleMessage(chatId, "❌ У тебя нет зарегистрированного магазина.");
            return;
        }
        
        Shop shop = shopOptional.get();
        
        // Получаем заказы магазина
        List<Order> allOrders = orderService.getOrdersByShop(shop);
        
        if (allOrders.isEmpty()) {
            sendSimpleMessage(chatId, "📋 *Мои заказы*\n\n" +
                    "У тебя пока нет заказов.\n" +
                    "Нажми \"📦 Создать заказ\" чтобы создать первый!");
            return;
        }

        // Ограничиваем список последними 20 заказами (если заказов больше)
        int max = 20;
        int fromIndex = Math.max(0, allOrders.size() - max);
        List<Order> orders = allOrders.subList(fromIndex, allOrders.size());
        
        // Формируем список заказов
        StringBuilder sb = new StringBuilder();
        sb.append("📋 *Мои заказы* (").append(allOrders.size()).append(" всего, показаны последние ")
                .append(orders.size()).append(")\n\n");
        
        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            
            // Проверяем, мультиадресный ли заказ
            if (order.isMultiStopOrder()) {
                // Мультиадресный заказ
                sb.append("*").append(i + 1).append(". 📦 Мультиадрес (").append(order.getTotalStops()).append(" точек)*\n");
                
                // Показываем маршрут (если есть точки)
                List<org.example.flower_delivery.model.OrderStop> stops = orderService.getOrderStops(order.getId());
                if (!stops.isEmpty()) {
                    for (org.example.flower_delivery.model.OrderStop stop : stops) {
                        String statusIcon = stop.isDelivered() ? "✅" : "📍";
                        sb.append("   ").append(statusIcon).append(" ").append(stop.getRecipientName());
                        sb.append(" — ").append(stop.getDeliveryAddress()).append("\n");
                    }
                } else {
                    // Fallback если точки не загрузились
                    sb.append("   📍 ").append(order.getDeliveryAddress()).append("\n");
                }
                
            } else {
                // Обычный заказ (1 точка)
                sb.append("*").append(i + 1).append(". ").append(order.getRecipientName()).append("*\n");
                sb.append("   📍 ").append(order.getDeliveryAddress()).append("\n");
            }
            
            sb.append("   💰 ").append(order.getDeliveryPrice()).append("₽\n");
            // Название статуса жирным; «принят»/«в магазине» — 🚴, «доставлен» — ✅
            String statusIcon = (order.getStatus() == ACCEPTED || order.getStatus() == IN_SHOP) ? " 🚴" : (order.getStatus() == DELIVERED ? " ✅" : "");
            sb.append("   📊 Статус: *").append(order.getStatus().getDisplayName()).append("*").append(statusIcon).append("\n");

            // Дата создания заказа (для понимания, когда заявка появилась)
            if (order.getCreatedAt() != null) {
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
                sb.append("   📅 Создан: ").append(order.getCreatedAt().format(fmt)).append("\n");
            }
            
            sb.append("\n");
        }

        // Сохраняем список последних заказов для выбора по номеру
        myOrdersSelectionHandler.saveLastOrders(telegramId, orders);

        // Кнопка для выбора заказа по номеру / ID
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        InlineKeyboardButton selectBtn = InlineKeyboardButton.builder()
                .text("🔎 Выбрать заказ")
                .callbackData("orders_select")
                .build();
        keyboard.add(List.of(selectBtn));
        
        // Отправляем сообщение с текстом списка и (если есть) с клавиатурой под ним
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(sb.toString());
        message.setParseMode("Markdown");
        message.setReplyMarkup(new InlineKeyboardMarkup(keyboard));
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки списка заказов: chatId={}", chatId, e);
        }
    }
    
    /**
     * Обработка кнопки "Мой магазин" (информация о магазине).
     */
    private void handleShopInfoButton(Update update) {
        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();
        
        var shopOptional = shopService.findByUserTelegramId(telegramId);
        
        if (shopOptional.isEmpty()) {
            sendSimpleMessage(chatId, "❌ У тебя нет зарегистрированного магазина.");
            return;
        }
        
        Shop shop = shopOptional.get();
        String status = shop.getIsActive() ? "✅ Активен" : "⏳ Ожидает активации";
        
        sendSimpleMessage(chatId, "🏪 *Мой магазин*\n\n" +
                "📋 *Информация:*\n" +
                "• Название: " + shop.getShopName() + "\n" +
                "• Адрес забора: " + shop.getPickupAddress() + "\n" +
                "• Телефон: " + shop.getPhone() + "\n" +
                "• Статус: " + status + "\n\n" +
                "📅 Зарегистрирован: " + shop.getCreatedAt().toLocalDate());
    }
    
    /**
     * ВРЕМЕННАЯ КОМАНДА для тестирования.
     * Активирует магазин текущего пользователя.
     * 
     * В продакшене это должен делать админ через админку!
     */
    private void handleActivateCommand(Update update) {
        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();
        
        var shopOptional = shopService.findByUserTelegramId(telegramId);
        
        if (shopOptional.isEmpty()) {
            sendSimpleMessage(chatId, "❌ У тебя нет магазина для активации.");
            return;
        }
        
        Shop shop = shopOptional.get();
        
        if (shop.getIsActive()) {
            // Магазин уже активен — показываем меню
            sendShopMenu(chatId, shop, "✅ Твой магазин уже активен!");
            return;
        }
        
        // Активируем магазин
        shop.setIsActive(true);
        shopService.save(shop);
        
        log.info("Магазин активирован (тестовая команда): shopId={}, telegramId={}", 
                shop.getId(), telegramId);
        
        // Показываем меню магазина
        sendShopMenu(chatId, shop, "✅ *Магазин активирован!*\n\n" +
                "Теперь ты можешь создавать заказы.");
    }

    /**
     * ВРЕМЕННАЯ КОМАНДА для тестирования.
     * Активирует курьера для текущего пользователя.
     *
     * В продакшене это будет делать админ через админку.
     */
    private void handleActivateCourierCommand(Update update) {
        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();

        var courierOptional = courierService.findByTelegramId(telegramId);

        if (courierOptional.isEmpty()) {
            sendSimpleMessage(chatId, "❌ У тебя ещё нет регистрации курьера.\n" +
                    "Сначала выбери роль *Курьер* через /start.");
            return;
        }

        var courier = courierOptional.get();

        if (Boolean.TRUE.equals(courier.getIsActive())) {
            // Профиль уже активен — просто показываем меню курьера
            sendCourierMenu(chatId, "✅ Твой профиль курьера уже активирован.\n\n" +
                    "Можешь смотреть доступные заказы и свою статистику.");
            return;
        }

        courierService.activateCourier(courier);
        // Баланс пополняется через ЮKassa или вручную (scripts/deposit_topup.sql)
        // После активации сразу показываем меню курьера
        sendCourierMenu(chatId, "✅ *Профиль курьера активирован!*\n\n" +
                "Теперь ты можешь выбирать заказы и работать курьером.");
    }
    
    /**
     * Показать меню магазина с кнопками (ReplyKeyboard — внизу экрана).
     * Оставлен public, чтобы можно было вызывать из других хендлеров (например, /start).
     */
    public void sendShopMenu(Long chatId, Shop shop, String headerText) {
        // Создаём ряды с кнопками
        KeyboardRow row1 = new KeyboardRow();
        row1.add("📦 Создать заказ");
        row1.add("📋 Мои заказы");
        
        KeyboardRow row2 = new KeyboardRow();
        row2.add("🏪 Мой магазин");
        row2.add("ℹ️ Информация");
        
        // Собираем клавиатуру (2 ряда)
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(List.of(row1, row2));
        keyboard.setResizeKeyboard(true);  // Подогнать размер под текст
        keyboard.setOneTimeKeyboard(false); // НЕ скрывать после нажатия — всегда видна!
        
        try {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(headerText)
                    .parseMode("Markdown")
                    .replyMarkup(keyboard)
                    .build();
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки меню магазина: chatId={}", chatId, e);
        }
    }

    /**
     * Показать меню курьера с основными кнопками.
     * Пока без сложной логики — просто точка входа для курьерского функционала.
     */
    public void sendCourierMenu(Long chatId, String headerText) {
        // Первый ряд с основными кнопками
        KeyboardRow row1 = new KeyboardRow();
        row1.add("📋 Доступные заказы");
        row1.add("🚚 Мои заказы");
        row1.add("💰 Моя статистика");

        // Второй ряд — кнопка информации
        KeyboardRow row2 = new KeyboardRow();
        row2.add("ℹ️ Информация");

        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(List.of(row1, row2));
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        try {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(headerText)
                    .parseMode("Markdown")
                    .replyMarkup(keyboard)
                    .build();
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки меню курьера: chatId={}", chatId, e);
        }
    }

    /**
     * Отправить текст курьеру и вернуть меню (📋 Доступные заказы, 🚚 Мои заказы, 💰 Моя статистика).
     * Без Markdown — для сообщений после геолокации, чтобы не ломаться на спецсимволах.
     */
    public void sendCourierMenuPlain(Long chatId, String text) {
        KeyboardRow row1 = new KeyboardRow();
        row1.add("📋 Доступные заказы");
        row1.add("🚚 Мои заказы");
        row1.add("💰 Моя статистика");
        KeyboardRow row2 = new KeyboardRow();
        row2.add("ℹ️ Информация");
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(List.of(row1, row2));
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);
        try {
            execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .replyMarkup(keyboard)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки меню курьера: chatId={}", chatId, e);
        }
    }

    /**
     * Кнопка "📋 Доступные заказы" в меню курьера.
     * РЕАЛИЗОВАНО: сортировка по расстоянию, честное распределение (10+10),
     * пагинация 10 на страницу, связки, выбор заказа. См. docs/COURIER_MODULE_STATUS.md
     */
    private void handleCourierAvailableOrdersButton(Update update) {
        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();

        // Проверяем, что у пользователя есть активный профиль курьера
        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty()) {
            sendSimpleMessage(chatId, "❌ У тебя ещё нет профиля курьера.\n\n" +
                    "Выбери роль *Курьер* через /start и пройди регистрацию.");
            return;
        }
        var courier = courierOpt.get();
        if (!Boolean.TRUE.equals(courier.getIsActive())) {
            sendSimpleMessage(chatId, "⏳ Твой профиль курьера ещё не активирован.\n\n" +
                    "Сначала активируй его командой /k (временно),\n" +
                    "позже это будет делать админ.");
            return;
        }

        // (опционально) можно ограничить количество активных заказов для курьера
        long activeCount = orderService.countActiveOrdersForCourier(courier.getUser());
        int maxActive = 3;
        if (activeCount >= maxActive) {
            sendSimpleMessage(chatId, "🚫 У тебя уже " + activeCount + " активных заказов.\n\n" +
                    "Сначала довези текущие (кнопка \"🚚 Мои заказы\"),\n" +
                    "потом можно брать новые.");
            return;
        }

        // Есть ли «свежая» гео курьера (до 30 мин) — тогда показываем ближайшие заказы и маршруты
        boolean hasFreshLocation = courier.getLastLocationAt() != null
                && courier.getLastLocationAt().isAfter(LocalDateTime.now().minusMinutes(30))
                && courier.getLastLatitude() != null && courier.getLastLongitude() != null;

        List<Order> availableOrders;
        if (hasFreshLocation) {
            // Честное распределение: 10 ближайших + 10 от «других» магазинов (при 400 заказах — до 80 из БД)
            availableOrders = orderService.getAvailableOrdersWithFairness(
                    courier.getLastLatitude().doubleValue(), courier.getLastLongitude().doubleValue(),
                    courier.getUser(), 10, 10);
        } else {
            availableOrders = orderService.getAvailableOrdersWithShop();
        }

        if (availableOrders.isEmpty()) {
            sendSimpleMessage(chatId, "📋 *Доступные заказы*\n\n" +
                    "Сейчас нет свободных заказов.\n" +
                    "Загляни сюда чуть позже.");
            return;
        }

        if (hasFreshLocation) {
            courierAvailableOrdersHandler.saveLastAvailableOrders(telegramId, availableOrders);
            courierAvailableOrdersHandler.saveLastAvailableCourierLocation(telegramId,
                    courier.getLastLatitude().doubleValue(), courier.getLastLongitude().doubleValue());
            courierAvailableOrdersHandler.showAvailableOrdersPage(telegramId, chatId, 0, null);
            return;
        }

        // Гео нет или устарела — просим отправить локацию, чтобы показать ближайшие
        courierAvailableOrdersHandler.startAwaitingLocationForList(telegramId);
        sendLocationRequestForAvailableOrders(chatId);
    }

    /** Запрос геолокации для списка «Доступные заказы» (сортировка по расстоянию + маршруты). */
    public void sendLocationRequestForAvailableOrders(Long chatId) {
        KeyboardButton locationButton = new KeyboardButton("📍 Отправить геолокацию");
        locationButton.setRequestLocation(true);
        KeyboardRow row = new KeyboardRow();
        row.add(locationButton);
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(List.of(row));
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(true);
        try {
            execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text("📍 Отправьте геолокацию, чтобы показать *ближайшие* заказы и построить маршрут до магазина.")
                    .parseMode("Markdown")
                    .replyMarkup(keyboard)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки запроса геолокации: chatId={}", chatId, e);
        }
    }

    /** Текст и клавиатура списка «Доступные заказы». Каждый заказ — inline-кнопка. */
    public record AvailableOrdersContent(String text, InlineKeyboardMarkup markup) {}

    /** Сократить строку для кнопки. */
    private static String truncateForButton(String s, int maxLen) {
        if (s == null || s.isEmpty()) return "—";
        s = s.trim();
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 1) + ".";
    }

    /** Адрес до «улица + дом» — без города, ул., подъезда, квартиры. Для кнопок: "Труда 72", "Тухачевского 10а". */
    private static String streetAndHouseOnly(String address) {
        if (address == null || address.isEmpty()) return "—";
        String s = address.trim();
        // Обрезаем всё после подъезда, квартиры, домофона
        int cut = -1;
        String[] markers = {"подъезд", "подик", "под.", " кв.", " кв ", "квартира", "кв.", "домофон", " д."};
        String lower = s.toLowerCase();
        for (String m : markers) {
            int idx = lower.indexOf(m);
            if (idx > 0 && (cut < 0 || idx < cut)) cut = idx;
        }
        if (cut > 0) s = s.substring(0, cut).trim();
        if (s.endsWith(",")) s = s.substring(0, s.length() - 1).trim();
        // Убираем префикс города и "ул." — чтобы было "Труда 72", а не "Челябинск, ул. Труда, 72"
        s = s.replaceFirst("(?i)^челябинск,?\\s*", "");
        s = s.replaceFirst("(?i)^ул\\.?\\s*", "");
        s = s.replaceFirst("(?i)^пр\\.?\\s*", "");
        s = s.replaceFirst("(?i)^проспект\\s+", "");
        s = s.replace(", ", " ").replace(",", " ").replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? "—" : s;
    }

    /** Короткий формат времени для кнопки (ASAP → «Срочно», остальные → 09-12). */
    private static String shortTimeForButton(org.example.flower_delivery.model.DeliveryInterval interval) {
        if (interval == null) return "—";
        if (interval == org.example.flower_delivery.model.DeliveryInterval.ASAP) return "Срочно";
        // 09:00 — 12:00 → 09-12 (компактно для мобильного)
        String range = interval.getTimeRange().replace(" — ", "-");
        return range.replace(":00", "").replace(":30", "");
    }

    public AvailableOrdersContent buildAvailableOrdersContentWithLocation(List<Order> ordersToShow,
                                                                         List<Order> fullListForBundles,
                                                                         double courierLat, double courierLon,
                                                                         int page, int totalPages, int totalCount) {
        java.time.LocalDate today = java.time.LocalDate.now();
        String dateStr = today.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        StringBuilder sb = new StringBuilder();
        sb.append("📋 *Список заказов* (").append(page + 1).append("/").append(totalPages)
                .append(") на ").append(dateStr).append("\n\n")
                .append("Нажми на заказ, чтобы увидеть детали и взять его.");

        List<OrderBundleService.OrderBundle> bundles = orderBundleService.findRecommendedBundles(
                fullListForBundles != null && !fullListForBundles.isEmpty() ? fullListForBundles : ordersToShow,
                courierLat, courierLon);
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        // Одна строка — Telegram НЕ поддерживает \n в кнопках. Время коротко (09-12), адреса по возможности целиком
        for (int i = 0; i < ordersToShow.size(); i++) {
            Order order = ordersToShow.get(i);
            int number = page * CourierAvailableOrdersHandler.ORDERS_PER_PAGE + i + 1;
            String timeStr = shortTimeForButton(order.getDeliveryInterval());
            String shopAddr = order.getEffectivePickupAddress() != null
                    ? streetAndHouseOnly(order.getEffectivePickupAddress())
                    : "—";
            String deliveryAddr;
            if (order.isMultiStopOrder()) {
                String route = order.getRouteDescription();
                deliveryAddr = java.util.Arrays.stream(route.split(" → "))
                        .map(String::trim)
                        .map(addr -> streetAndHouseOnly(addr))
                        .reduce((a, b) -> a + "→" + b)
                        .orElse("—");
            } else {
                deliveryAddr = streetAndHouseOnly(order.getDeliveryAddress());
            }
            if (shopAddr.length() > 20) shopAddr = truncateForButton(shopAddr, 20);
            if (deliveryAddr.length() > 20) deliveryAddr = truncateForButton(deliveryAddr, 20);
            String btnText = "Заказ " + number + " (" + timeStr + ") " + shopAddr + "→" + deliveryAddr;
            if (btnText.length() > 64) {
                btnText = btnText.substring(0, 61) + ".";
            }
            keyboard.add(List.of(
                    InlineKeyboardButton.builder().text(btnText).callbackData("courier_order_view:" + order.getId()).build()
            ));
        }

        // Кнопки связок: индексы 1-based в полном списке (не на странице)
        for (int b = 0; b < bundles.size(); b++) {
            OrderBundleService.OrderBundle bundle = bundles.get(b);
            String indicesStr = String.join(",", bundle.indicesInList().stream().map(String::valueOf).toList());
            List<String> displayNumbers = bundle.indicesInList().stream().map(String::valueOf).toList();
            String indicesDisplay = String.join(", ", displayNumbers);
            String btnText = (b == 0) ? "📦 Взять связку (" + indicesDisplay + ")" : "📦 Альтернативная связка (" + indicesDisplay + ")";
            keyboard.add(List.of(
                    InlineKeyboardButton.builder().text(btnText).callbackData("courier_bundle_take:" + indicesStr).build()
            ));
        }

        // Пагинация
        if (totalPages > 1) {
            List<InlineKeyboardButton> navRow = new ArrayList<>();
            if (page > 0) {
                navRow.add(InlineKeyboardButton.builder().text("← Назад").callbackData("courier_orders_page:" + (page - 1)).build());
            }
            if (page < totalPages - 1) {
                navRow.add(InlineKeyboardButton.builder().text("Дальше →").callbackData("courier_orders_page:" + (page + 1)).build());
            }
            if (!navRow.isEmpty()) {
                keyboard.add(navRow);
            }
        }
        return new AvailableOrdersContent(sb.toString(), new InlineKeyboardMarkup(keyboard));
    }

    /** Ссылка на маршрут в Яндекс.Картах: от (lat1,lon1) до (lat2,lon2). */
    private static String buildYandexRouteUrl(double fromLat, double fromLon, double toLat, double toLon) {
        return "https://yandex.ru/maps/?rtext=" + fromLat + "," + fromLon + "~" + toLat + "," + toLon + "&rtt=auto";
    }

    /** Ссылка на маршрут в 2ГИС: от (lat1,lon1) до (lat2,lon2). */
    private static String build2GisRouteUrl(double fromLat, double fromLon, double toLat, double toLon) {
        // 2ГИС routeSearch: только точка Б (магазин), точка А = GPS пользователя. Формат to/lon,lat (документация)
        String to = toLon + "," + toLat;
        return "https://2gis.ru/routeSearch/rsType/car/to/" + to;
    }

    /**
     * После успешного возврата заказа в магазин подсказать курьеру маршрут обратно в магазин,
     * если у него больше нет активных заказов.
     */
    public void sendReturnToShopRoute(org.example.flower_delivery.model.Courier courier, Order order) {
        if (courier == null || order == null) {
            return;
        }
        Long chatId = courier.getUser() != null ? courier.getUser().getTelegramId() : null;
        if (chatId == null) {
            return;
        }
        if (courier.getLastLatitude() == null || courier.getLastLongitude() == null) {
            return;
        }
        if (order.getEffectivePickupLatitude() == null || order.getEffectivePickupLongitude() == null) {
            return;
        }

        double fromLat = courier.getLastLatitude().doubleValue();
        double fromLon = courier.getLastLongitude().doubleValue();
        double toLat = order.getEffectivePickupLatitude().doubleValue();
        double toLon = order.getEffectivePickupLongitude().doubleValue();

        String yandexUrl = buildYandexRouteUrl(fromLat, fromLon, toLat, toLon);
        String twoGisUrl = build2GisRouteUrl(fromLat, fromLon, toLat, toLon);

        InlineKeyboardButton yandexBtn = InlineKeyboardButton.builder()
                .text("🗺 Яндекс.Карты")
                .url(yandexUrl)
                .build();
        InlineKeyboardButton twoGisBtn = InlineKeyboardButton.builder()
                .text("🗺 2ГИС")
                .url(twoGisUrl)
                .build();
        InlineKeyboardMarkup kb = new InlineKeyboardMarkup(
                java.util.List.of(java.util.List.of(yandexBtn, twoGisBtn))
        );

        String text = "↩️ *Заказ помечен как возвращён в магазин.*\n\n" +
                "Если ты ещё с букетом — вот маршрут обратно в магазин.";

        try {
            execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .parseMode("Markdown")
                    .replyMarkup(kb)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки маршрута возврата в магазин: chatId={}", chatId, e);
        }
    }

    /**
     * Кнопка "🚚 Мои заказы" в меню курьера.
     * Пока заглушка: позже покажем активные заказы, которые курьер сейчас везёт.
     */
    private void handleCourierMyOrdersButton(Update update) {
        Long chatId = update.getMessage().getChatId();
        Long telegramId = update.getMessage().getFrom().getId();

        var courierOpt = courierService.findByTelegramId(telegramId);

        if(courierOpt.isEmpty()) {
            sendSimpleMessage(chatId, "❌ У тебя ещё нет профиля курьера.\n\n" +
                    "Выбери роль *Курьер* через /start и пройди регистрацию.");
            return;
        }

        var courier = courierOpt.get();
        if(!Boolean.TRUE.equals(courier.getIsActive())) {
            sendSimpleMessage(chatId, "⏳ Твой профиль курьера ещё не активирован.\n\n" +
                    "Сначала активируй его командой /k (временно),\n" +
                    "позже это будет делать админ.");
            return;
        }

        List<Order> allOrders = orderService.getOrdersByCourierWithShop(courier.getUser());
        if (allOrders.isEmpty()) {
            sendSimpleMessage(chatId, "🚚 *Мои заказы (курьер)*\n\n" +
                    "У тебя пока нет заказов.\n" +
                    "Зайди в «📋 Доступные заказы» и возьми первый заказ.");
            return;
        }

        CourierMyOrdersContent content = buildCourierMyOrdersContent(courier, allOrders);
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(content.text);
        message.setParseMode("Markdown");
        message.setReplyMarkup(content.replyMarkup);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки списка заказов курьера: chatId={}", chatId, e);
        }
    }

    /**
     * Собрать текст и клавиатуру списка «Мои заказы» курьера (для отправки и для редактирования).
     * Сначала всегда показываем все активные заказы (ACCEPTED/IN_SHOP/ON_WAY), затем добиваем
     * последними завершёнными (до 6 всего), чтобы активные не «прятались» за последними 6.
     */
    public CourierMyOrdersContent buildCourierMyOrdersContent(Courier courier, List<Order> allOrders) {
        List<Order> active = allOrders.stream()
                .filter(o -> o.getStatus() == OrderStatus.ACCEPTED || o.getStatus() == OrderStatus.IN_SHOP
                        || o.getStatus() == OrderStatus.PICKED_UP || o.getStatus() == OrderStatus.ON_WAY)
                .toList();
        List<Order> completed = allOrders.stream()
                .filter(o -> !active.contains(o))
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
                    if (a.getCreatedAt() == null) return 1;
                    if (b.getCreatedAt() == null) return -1;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .toList();
        int rest = Math.max(0, 6 - active.size());
        List<Order> orders = new ArrayList<>(active);
        orders.addAll(completed.stream().limit(rest).toList());

        StringBuilder sb = new StringBuilder();
        sb.append("🚚 *Мои заказы (курьер)*\n\n");
        sb.append("Всего: ").append(allOrders.size());
        if (!active.isEmpty()) {
            sb.append(", активных: ").append(active.size());
        }
        sb.append(", показано: ").append(orders.size()).append("\n\n");

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            if (order.getEffectivePickupAddress() != null) {
                sb.append("*").append(i + 1).append(". ").append(order.getRecipientName()).append("*\n");
                sb.append("   🏪 Забрать: ").append(order.getEffectivePickupAddress()).append("\n");
            } else {
                sb.append("*").append(i + 1).append(". ").append(order.getRecipientName()).append("*\n");
            }
            if (order.isMultiStopOrder()) {
                List<org.example.flower_delivery.model.OrderStop> stops = orderService.getOrderStops(order.getId());
                if (!stops.isEmpty()) {
                    for (org.example.flower_delivery.model.OrderStop stop : stops) {
                        String pointIcon = stop.isDelivered() ? "✅" : "📍";
                        sb.append("   ").append(pointIcon).append(" Точка ").append(stop.getStopNumber())
                                .append(": ").append(stop.getRecipientName()).append(" — ").append(stop.getDeliveryAddress()).append("\n");
                    }
                } else {
                    sb.append("   📍 ").append(order.getDeliveryAddress()).append("\n");
                }
            } else {
                sb.append("   📍 ").append(order.getDeliveryAddress()).append("\n");
            }
            sb.append("   💰 ").append(order.getDeliveryPrice()).append("₽\n");
            String statusIcon;
            switch (order.getStatus()) {
                case ACCEPTED, IN_SHOP, PICKED_UP, ON_WAY -> statusIcon = "🔥";
                case DELIVERED -> statusIcon = "✅";
                case RETURNED -> statusIcon = "↩️";
                case CANCELLED -> statusIcon = "⛔";
                case NEW -> statusIcon = "🆕";
                default -> statusIcon = "ℹ️";
            }
            sb.append("   📊 Статус: *").append(order.getStatus().getDisplayName()).append("* ").append(statusIcon).append("\n");
            if (order.getCreatedAt() != null) {
                sb.append("   📅 Создан: ").append(order.getCreatedAt().format(fmt)).append("\n");
            }
            sb.append("\n");
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Кнопки смены статуса: «N. Адрес → В магазине / В путь / Вручил».
        List<InlineKeyboardButton> statusRow = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            OrderStatus next = nextStatusForCourier(order.getStatus());
            if (next == null) continue;
            if (next == DELIVERED && order.isMultiStopOrder()) {
                List<org.example.flower_delivery.model.OrderStop> stops = orderService.getOrderStops(order.getId());
                for (org.example.flower_delivery.model.OrderStop stop : stops) {
                    if (!stop.isDelivered()) {
                        InlineKeyboardButton btn = new InlineKeyboardButton();
                        String shortAddr = shortAddressForButton(stop.getDeliveryAddress());
                        btn.setText((i + 1) + ". " + (shortAddr.isEmpty() ? "Точка " + stop.getStopNumber() : shortAddr) + " → Вручил");
                        btn.setCallbackData("courier_stop_delivered:" + order.getId() + ":" + stop.getStopNumber());
                        statusRow.add(btn);
                    }
                }
            } else {
                InlineKeyboardButton btn = new InlineKeyboardButton();
                String shortAddr;
                if (next == OrderStatus.IN_SHOP && order.getShop() != null) {
                    shortAddr = shortAddressForButton(order.getEffectivePickupAddress());
                } else {
                    shortAddr = shortAddressForButton(order.getDeliveryAddress());
                    if (order.isMultiStopOrder()) {
                        var stops = orderService.getOrderStops(order.getId());
                        if (!stops.isEmpty()) shortAddr = shortAddressForButton(stops.get(0).getDeliveryAddress());
                    }
                }
                btn.setText((i + 1) + ". " + (shortAddr.isEmpty() ? "" : shortAddr + " ") + "→ " + next.getDisplayName());
                btn.setCallbackData("courier_order_next:" + order.getId());
                statusRow.add(btn);
            }
        }
        for (int r = 0; r < statusRow.size(); r += 4) {
            rows.add(statusRow.subList(r, Math.min(r + 4, statusRow.size())));
        }

        // Одна кнопка «Отменить заказ» — по нажатию курьер вводит номер заказа.
        boolean hasActiveOrders = orders.stream().anyMatch(o ->
                o.getStatus() == OrderStatus.ACCEPTED
                        || o.getStatus() == OrderStatus.IN_SHOP
                        || o.getStatus() == OrderStatus.PICKED_UP
                        || o.getStatus() == OrderStatus.ON_WAY);
        if (hasActiveOrders) {
            InlineKeyboardButton cancelBtn = new InlineKeyboardButton();
            cancelBtn.setText("⛔ Отменить заказ");
            cancelBtn.setCallbackData("courier_cancel_select");
            rows.add(List.of(cancelBtn));
        }
        markup.setKeyboard(rows);
        return new CourierMyOrdersContent(sb.toString(), markup);
    }

    /**
     * Короткий адрес для подписи кнопки (лимит Telegram ~64 байта на текст кнопки).
     * Берём часть до первой запятой, не более 16 символов, по границе слова.
     */
    private static String shortAddressForButton(String fullAddress) {
        if (fullAddress == null || fullAddress.isEmpty()) return "";
        String s = fullAddress.trim();
        int comma = s.indexOf(',');
        if (comma > 0) s = s.substring(0, comma).trim();
        final int max = 16;
        if (s.length() <= max) return s;
        s = s.substring(0, max);
        int lastSpace = s.lastIndexOf(' ');
        return lastSpace > 0 ? s.substring(0, lastSpace).trim() : s;
    }

    /**
     * Отправить магазину запрос: «Курьер [имя] уехал с заказом … Вы передали ему заказ? ДА ✅ / Нет ❌».
     * Вызывать после перевода заказа в «В путь». Order должен быть загружен с shop, shop.user, courier.
     */
    public void sendShopPickupConfirmationRequest(Order order) {
        if (order == null || order.getShop() == null || order.getShop().getUser() == null) {
            log.warn("Не удалось отправить запрос магазину: заказ или магазин/пользователь отсутствуют");
            return;
        }
        Long shopChatId = order.getShop().getUser().getTelegramId();
        if (shopChatId == null) {
            log.warn("У магазина заказа {} нет telegram_id", order.getId());
            return;
        }
        String courierName = order.getCourier() != null && order.getCourier().getFullName() != null
                ? order.getCourier().getFullName() : "Курьер";
        String recipient = order.getRecipientName() != null ? order.getRecipientName() : "";
        String address = order.getDeliveryAddress() != null ? order.getDeliveryAddress() : "";
        String text = "📦 *Курьер забрал заказ?*\n\n"
                + "Курьер *" + escapeMarkdown(courierName) + "* уехал с заказом:\n"
                + "• " + escapeMarkdown(recipient) + "\n"
                + "• " + escapeMarkdown(address) + "\n\n"
                + "Вы передали ему заказ?";
        InlineKeyboardButton btnYes = InlineKeyboardButton.builder()
                .text("ДА ✅")
                .callbackData("shop_pickup_confirm:" + order.getId() + ":yes")
                .build();
        InlineKeyboardButton btnNo = InlineKeyboardButton.builder()
                .text("Нет ❌")
                .callbackData("shop_pickup_confirm:" + order.getId() + ":no")
                .build();
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(List.of(btnYes, btnNo)));
        SendMessage message = SendMessage.builder()
                .chatId(shopChatId.toString())
                .text(text)
                .parseMode("Markdown")
                .replyMarkup(markup)
                .build();
        try {
            execute(message);
            log.info("Запрос «Курьер забрал?» отправлен магазину: orderId={}, shopChatId={}", order.getId(), shopChatId);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки запроса магазину: orderId={}, shopChatId={}", order.getId(), shopChatId, e);
        }
    }

    private static String escapeMarkdown(String s) {
        if (s == null) return "";
        return s.replace("_", "\\_").replace("*", "\\*").replace("[", "\\[");
    }

    /**
     * Начать выбор номера заказа для отмены курьером.
     * Сохраняем список активных UUID и просим ввести номер.
     */
    public void startCourierCancelSelection(Long telegramId, Long chatId) {
        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty()) return;
        List<Order> allOrders = orderService.getOrdersByCourierWithShop(courierOpt.get().getUser());
        List<UUID> activeIds = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        sb.append("⛔ *Отмена заказа*\n\n");
        sb.append("Выбери номер заказа для отмены:\n\n");
        int idx = 0;
        for (Order o : allOrders) {
            OrderStatus st = o.getStatus();
            if (st == OrderStatus.ACCEPTED || st == OrderStatus.IN_SHOP
                    || st == OrderStatus.PICKED_UP || st == OrderStatus.ON_WAY) {
                idx++;
                activeIds.add(o.getId());
                sb.append("*").append(idx).append(".* ")
                        .append(o.getRecipientName()).append(" — ")
                        .append(o.getDeliveryAddress()).append("\n");
            }
        }
        if (activeIds.isEmpty()) {
            sendSimpleMessage(chatId, "У тебя нет активных заказов для отмены.");
            return;
        }
        sb.append("\nВведи номер (1–").append(activeIds.size()).append(") или /cancel для выхода.");
        awaitingCancelSelection.put(telegramId, activeIds);
        sendSimpleMessage(chatId, sb.toString());
    }

    /**
     * Обработка текстового ввода номера заказа для отмены курьером.
     */
    private boolean handleCourierCancelSelectionText(Long telegramId, Long chatId, String text) {
        // Выход из режима по кнопкам меню или /cancel
        if ("📋 Доступные заказы".equals(text) || "🚚 Мои заказы".equals(text)
                || "💰 Моя статистика".equals(text) || "ℹ️ Информация".equals(text)
                || "/start".equals(text) || "/cancel".equalsIgnoreCase(text.trim())) {
            awaitingCancelSelection.remove(telegramId);
            return false;
        }

        List<UUID> ids = awaitingCancelSelection.get(telegramId);
        if (ids == null || ids.isEmpty()) {
            awaitingCancelSelection.remove(telegramId);
            return false;
        }

        int index;
        try {
            index = Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            sendSimpleMessage(chatId, "❌ Введи число от 1 до " + ids.size());
            return true;
        }
        if (index < 1 || index > ids.size()) {
            sendSimpleMessage(chatId, "❌ Номер должен быть от 1 до " + ids.size());
            return true;
        }

        UUID orderId = ids.get(index - 1);
        awaitingCancelSelection.remove(telegramId);

        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty() || !Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
            sendSimpleMessage(chatId, "❌ Нет активного профиля курьера.");
            return true;
        }
        startAwaitingCancelReason(telegramId, chatId, orderId, false);
        return true;
    }

    /**
     * Начать ожидание причины отмены/возврата.
     */
    public void startAwaitingCancelReason(Long telegramId, Long chatId, UUID orderId, boolean isReturn) {
        awaitingCancelReason.put(telegramId, new PendingCancelReason(orderId, isReturn));
        String prompt = isReturn
                ? "📝 *Укажи причину возврата* заказа в магазин (или /skip чтобы пропустить):\n\n_Причина передаётся в поддержку._"
                : "📝 *Укажи причину отмены* заказа (или /skip чтобы пропустить):\n\n_Причина передаётся в поддержку._";
        sendSimpleMessage(chatId, prompt);
    }

    /**
     * Обработка ввода причины отмены/возврата курьером.
     */
    private boolean handleCourierCancelReasonText(Long telegramId, Long chatId, String text) {
        if ("/start".equals(text) || "/cancel".equalsIgnoreCase(text.trim())) {
            awaitingCancelReason.remove(telegramId);
            sendSimpleMessage(chatId, "Отменено.");
            return true;
        }
        if ("📋 Доступные заказы".equals(text) || "🚚 Мои заказы".equals(text)
                || "💰 Моя статистика".equals(text) || "ℹ️ Информация".equals(text)) {
            awaitingCancelReason.remove(telegramId);
            return false;
        }

        PendingCancelReason pending = awaitingCancelReason.get(telegramId);
        if (pending == null) return false;

        awaitingCancelReason.remove(telegramId);

        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty() || !Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
            sendSimpleMessage(chatId, "❌ Нет активного профиля курьера.");
            return true;
        }
        var courier = courierOpt.get();

        String reason = "/skip".equalsIgnoreCase(text.trim()) ? null : text.trim();
        if (reason != null && reason.isEmpty()) reason = null;

        var result = pending.isReturn()
                ? orderService.returnOrderToShopByCourier(pending.orderId(), courier.getUser(), reason)
                : orderService.cancelOrderByCourier(pending.orderId(), courier.getUser(), reason);

        UUID orderId = pending.orderId();
        if (result.success()) {
            String msg;
            if (pending.isReturn()) {
                msg = result.penaltyApplied()
                        ? "⚠️ *Заказ возвращён в магазин.*\n\n" + (result.penaltyReason() != null ? result.penaltyReason() + "\n\n" : "") + "Подробности в «💰 Моя статистика»."
                        : "✅ Заказ возвращён в магазин.\n\nНажми «🚚 Мои заказы» чтобы обновить список.";
            } else {
                msg = result.penaltyApplied()
                        ? "⚠️ *Заказ отменён.*\n\n" + (result.penaltyReason() != null ? result.penaltyReason() + "\n\n" : "") + "Подробности в «💰 Моя статистика»."
                        : "✅ Заказ отменён.\n\nНажми «🚚 Мои заказы» чтобы обновить список.";
            }
            sendCourierMenu(chatId, msg);
            // Уведомляем магазин
            orderService.getOrderForShopPickupMessage(orderId).ifPresent(order -> {
                var shop = order.getShop();
                if (shop != null && shop.getUser() != null && shop.getUser().getTelegramId() != null) {
                    Long shopChatId = shop.getUser().getTelegramId();
                    String action = pending.isReturn() ? "возвращён" : "отменён";
                    String shopMsg = "⚠️ *Заказ " + action + " курьером*\n\n"
                            + "Получатель: " + order.getRecipientName() + "\n"
                            + "Адрес: " + order.getDeliveryAddress() + "\n\n"
                            + "Курьер: " + courier.getFullName();
                    if (order.getCourierCancelReason() != null && !order.getCourierCancelReason().isBlank()) {
                        shopMsg += "\n\n📝 *Причина:* " + order.getCourierCancelReason();
                    }
                    sendSimpleMessage(shopChatId, shopMsg);
                }
            });
            if (result.notifyAdmin()) {
                String reasonForAdmin = orderService.getOrderForShopPickupMessage(orderId)
                        .map(Order::getCourierCancelReason)
                        .orElse(null);
                notifyAdminsAboutSuspiciousCancel(telegramId, orderId, pending.isReturn(), reasonForAdmin);
            }
            if (pending.isReturn()) {
                orderService.getOrderForShopPickupMessage(orderId).ifPresent(order -> {
                    var allForCourier = orderService.getOrdersByCourierWithShop(courier.getUser());
                    boolean hasOtherActive = allForCourier.stream().anyMatch(o ->
                            !o.getId().equals(order.getId()) &&
                                    (o.getStatus() == OrderStatus.ACCEPTED
                                            || o.getStatus() == OrderStatus.IN_SHOP
                                            || o.getStatus() == OrderStatus.PICKED_UP
                                            || o.getStatus() == OrderStatus.ON_WAY));
                    if (!hasOtherActive) {
                        sendReturnToShopRoute(courier, order);
                    }
                });
            }
        } else {
            sendSimpleMessage(chatId, pending.isReturn()
                    ? "❌ Не удалось вернуть заказ. Возможно, он уже завершён."
                    : "❌ Не удалось отменить заказ. Возможно, он уже завершён.");
        }
        return true;
    }

    /** Редактировать сообщение «Мои заказы» курьера (обновить список и кнопки). */
    public void editCourierMyOrdersMessage(Long chatId, Integer messageId, Long telegramId) {
        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty()) return;
        List<Order> allOrders = orderService.getOrdersByCourierWithShop(courierOpt.get().getUser());
        if (allOrders.isEmpty()) return;
        CourierMyOrdersContent content = buildCourierMyOrdersContent(courierOpt.get(), allOrders);
        EditMessageText edit = new EditMessageText();
        edit.setChatId(chatId.toString());
        edit.setMessageId(messageId);
        edit.setText(content.text);
        edit.setParseMode("Markdown");
        edit.setReplyMarkup(content.replyMarkup);
        try {
            execute(edit);
        } catch (TelegramApiException e) {
            log.error("Ошибка редактирования списка заказов курьера: chatId={}, messageId={}", chatId, messageId, e);
        }
    }

    /**
     * Уведомить всех активных администраторов о проблеме с геолокацией курьера
     * (3 неудачные попытки подтвердить «В магазине» / «Вручил»).
     */
    public void notifyAdminsAboutCourierGeoIssue(Long courierTelegramId,
                                                 UUID orderId,
                                                 OrderStatus nextStatus,
                                                 double latitude,
                                                 double longitude,
                                                 int attempts) {
        List<org.example.flower_delivery.model.User> admins = userService.findActiveAdmins();
        if (admins.isEmpty()) {
            log.warn("Нет активных админов для уведомления о проблеме гео: orderId={}, courierTelegramId={}",
                    orderId, courierTelegramId);
            return;
        }
        String text = "⚠️ *Проблема с подтверждением геолокации курьера*\n\n"
                + "Курьер telegramId: `" + courierTelegramId + "`\n"
                + "Заказ: `" + orderId + "`\n"
                + "Статус для подтверждения: *" + nextStatus.getDisplayName() + "*\n"
                + "Попыток: " + attempts + "\n"
                + "Последняя точка: `" + latitude + ", " + longitude + "`\n\n"
                + "Нужно вручную проверить ситуацию и при необходимости скорректировать статус/штрафы.";
        for (org.example.flower_delivery.model.User admin : admins) {
            Long chatId = admin.getTelegramId();
            if (chatId == null) continue;
            try {
                execute(SendMessage.builder()
                        .chatId(chatId.toString())
                        .text(text)
                        .parseMode("Markdown")
                        .build());
            } catch (TelegramApiException e) {
                log.error("Не удалось отправить администратору уведомление о проблеме гео: adminTelegramId={}, orderId={}",
                        chatId, orderId, e);
            }
        }
    }

    /**
     * Уведомить админов о «звоночке»: курьер был в магазине по гео, но не у получателя.
     * Штрафы назначает админ вручную.
     *
     * @param cancelReason причина отмены/возврата от курьера (может быть null)
     */
    public void notifyAdminsAboutSuspiciousCancel(Long courierTelegramId, UUID orderId, boolean isReturn, String cancelReason) {
        List<org.example.flower_delivery.model.User> admins = userService.findActiveAdmins();
        if (admins.isEmpty()) {
            log.warn("Нет активных админов для уведомления о подозрительной отмене: orderId={}, courierTelegramId={}",
                    orderId, courierTelegramId);
            return;
        }
        String action = isReturn ? "возвратил в магазин" : "отменил";
        String text = "⚠️ *Звоночек: подозрительная отмена*\n\n"
                + "Курьер telegramId: `" + courierTelegramId + "`\n"
                + "Заказ: `" + orderId + "`\n"
                + "Действие: " + action + "\n\n"
                + "Гео: курьер был в магазине, но не у получателя.\n";
        if (cancelReason != null && !cancelReason.isBlank()) {
            text += "\n📝 *Причина от курьера:* " + cancelReason + "\n";
        }
        text += "\nРазобрать и при необходимости назначить штраф.";
        for (org.example.flower_delivery.model.User admin : admins) {
            Long chatId = admin.getTelegramId();
            if (chatId == null) continue;
            try {
                execute(SendMessage.builder()
                        .chatId(chatId.toString())
                        .text(text)
                        .parseMode("Markdown")
                        .build());
            } catch (TelegramApiException e) {
                log.error("Не удалось отправить админу уведомление о подозрительной отмене: adminTelegramId={}, orderId={}",
                        chatId, orderId, e);
            }
        }
    }

    private static class CourierMyOrdersContent {
        final String text;
        final InlineKeyboardMarkup replyMarkup;
        CourierMyOrdersContent(String text, InlineKeyboardMarkup replyMarkup) {
            this.text = text;
            this.replyMarkup = replyMarkup;
        }
    }

    /** Следующий статус в цепочке курьера: В магазине → В пути → Доставлен (без «Забран»). */
    private static OrderStatus nextStatusForCourier(OrderStatus current) {
        return switch (current) {
            case ACCEPTED -> IN_SHOP;
            case IN_SHOP -> ON_WAY;
            case PICKED_UP -> ON_WAY;
            case ON_WAY -> DELIVERED;
            default -> null;
        };
    }

    

    /**
     * Кнопка "💰 Моя статистика" в меню курьера.
     * Показывает количество доставленных заказов и сумму заработка (всего и за текущий месяц).
     */
    private void handleCourierStatsButton(Update update) {
        Long chatId = update.getMessage().getChatId();
        Long telegramId = update.getMessage().getFrom().getId();

        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty()) {
            sendSimpleMessage(chatId, "❌ У тебя ещё нет профиля курьера.\n\nВыбери роль *Курьер* через /start и пройди регистрацию.");
            return;
        }
        if (!Boolean.TRUE.equals(courierOpt.get().getIsActive())) {
            sendSimpleMessage(chatId, "⏳ Твой профиль курьера ещё не активирован.\n\nСначала активируй его командой /k.");
            return;
        }

        List<Order> allOrders = orderService.getOrdersByCourierWithStops(courierOpt.get().getUser());
        var courier = courierOpt.get();
        CourierStatsContent content = buildCourierStatsContent(courier, allOrders, 0);

        try {
            execute(SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(content.text)
                    .parseMode("Markdown")
                    .replyMarkup(content.replyMarkup)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки статистики курьера: chatId={}", chatId, e);
        }
    }

    private static class CourierStatsContent {
        final String text;
        final InlineKeyboardMarkup replyMarkup;

        CourierStatsContent(String text, InlineKeyboardMarkup replyMarkup) {
            this.text = text;
            this.replyMarkup = replyMarkup;
        }
    }

    private CourierStatsContent buildCourierStatsContent(Courier courier, List<Order> allOrders, int txOffset) {
        final int PAGE_SIZE = 6;
        java.time.LocalDate now = java.time.LocalDate.now();
        java.time.LocalDateTime startOfToday = now.atStartOfDay();
        java.time.LocalDateTime startOfWeek = now.minusDays(6).atStartOfDay();
        java.time.YearMonth thisMonth = java.time.YearMonth.from(now);

        var deliveredOrders = allOrders.stream().filter(o -> o.getStatus() == DELIVERED).toList();

        long totalDelivered = deliveredOrders.size();
        java.math.BigDecimal totalSum = deliveredOrders.stream()
                .map(Order::getTotalDeliveryPrice)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        long todayDelivered = deliveredOrders.stream()
                .filter(o -> o.getDeliveredAt() != null && !o.getDeliveredAt().isBefore(startOfToday))
                .count();
        java.math.BigDecimal todaySum = deliveredOrders.stream()
                .filter(o -> o.getDeliveredAt() != null && !o.getDeliveredAt().isBefore(startOfToday))
                .map(Order::getTotalDeliveryPrice)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        long weekDelivered = deliveredOrders.stream()
                .filter(o -> o.getDeliveredAt() != null && !o.getDeliveredAt().isBefore(startOfWeek))
                .count();
        java.math.BigDecimal weekSum = deliveredOrders.stream()
                .filter(o -> o.getDeliveredAt() != null && !o.getDeliveredAt().isBefore(startOfWeek))
                .map(Order::getTotalDeliveryPrice)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        long monthDelivered = deliveredOrders.stream()
                .filter(o -> o.getDeliveredAt() != null
                        && java.time.YearMonth.from(o.getDeliveredAt()).equals(thisMonth))
                .count();
        java.math.BigDecimal monthSum = deliveredOrders.stream()
                .filter(o -> o.getDeliveredAt() != null
                        && java.time.YearMonth.from(o.getDeliveredAt()).equals(thisMonth))
                .map(Order::getTotalDeliveryPrice)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        String monthName = thisMonth.getMonth().getDisplayName(java.time.format.TextStyle.FULL, new java.util.Locale("ru"));
        java.math.BigDecimal balance = courier.getBalance() != null ? courier.getBalance() : java.math.BigDecimal.ZERO;
        java.math.BigDecimal percent = courier.getCommissionPercent() != null
                ? courier.getCommissionPercent()
                : new java.math.BigDecimal("20.00");

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
            java.util.List<org.example.flower_delivery.model.CourierTransaction> pageTx = allTx.subList(safeOffset, end);

            sb.append("\n\n📜 *Последние операции депозита:*\n");
            java.time.format.DateTimeFormatter txFmt = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm");
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

        org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton topUpBtn =
                org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                        .text("💳 Пополнить депозит")
                        .callbackData("courier_deposit_topup")
                        .build();

        java.util.List<java.util.List<InlineKeyboardButton>> rows = new java.util.ArrayList<>();

        if (allTx.size() > 6) {
            int safeOffset = Math.max(0, Math.min(txOffset, Math.max(0, allTx.size() - 1)));
            java.util.List<InlineKeyboardButton> navRow = new java.util.ArrayList<>();
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

        rows.add(java.util.List.of(topUpBtn));
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(rows);
        return new CourierStatsContent(text, markup);
    }

    /** Редактировать сообщение «Доступные заказы» (для пагинации). */
    public void editAvailableOrdersMessage(Long chatId, Integer messageId, String text, InlineKeyboardMarkup markup) {
        EditMessageText edit = new EditMessageText();
        edit.setChatId(chatId.toString());
        edit.setMessageId(messageId);
        edit.setText(text);
        edit.setParseMode("Markdown");
        edit.setReplyMarkup(markup);
        try {
            execute(edit);
        } catch (TelegramApiException e) {
            log.error("Ошибка редактирования списка доступных заказов: chatId={}, messageId={}", chatId, messageId, e);
        }
    }

    /** Редактировать сообщение «Моя статистика» курьера (для пагинации операций депозита). */
    public void editCourierStatsMessage(Long chatId, Integer messageId, Long telegramId, int txOffset) {
        var courierOpt = courierService.findByTelegramId(telegramId);
        if (courierOpt.isEmpty()) return;
        var courier = courierOpt.get();
        List<Order> allOrders = orderService.getOrdersByCourierWithStops(courier.getUser());
        CourierStatsContent content = buildCourierStatsContent(courier, allOrders, txOffset);

        EditMessageText edit = new EditMessageText();
        edit.setChatId(chatId.toString());
        edit.setMessageId(messageId);
        edit.setText(content.text);
        edit.setParseMode("Markdown");
        edit.setReplyMarkup(content.replyMarkup);
        try {
            execute(edit);
        } catch (TelegramApiException e) {
            log.error("Ошибка редактирования статистики курьера: chatId={}, messageId={}", chatId, messageId, e);
        }
    }
    
    /**
     * Простая отправка сообщения (для временных команд).
     */
    private void sendSimpleMessage(Long chatId, String text) {
        try {
            SendMessage message = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(text)
                    .parseMode("Markdown")
                    .build();
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения: chatId={}", chatId, e);
        }
    }

    /**
     * Возвращает имя бота (username без @)
     * 
     * Telegram использует это для идентификации бота
     * Должно совпадать с тем, что в application.properties
     * 
     * @return username бота (например: "FlowerDelivery74bot")
     */
    @Override
    public String getBotUsername() {
        return botUsername;
    }

    /**
     * Возвращает токен бота для авторизации в Telegram API
     * 
     * Токен - это как пароль от бота. Получаешь у @BotFather в Telegram
     * БЕЗ ТОКЕНА бот не сможет подключиться к Telegram!
     * 
     * ВАЖНО: Токен теперь берется из application.properties
     * Это безопаснее чем хардкодить в коде (можно вынести в переменные окружения на проде)
     * 
     * @return токен бота (длинная строка типа: "123456:ABC-DEF...")
     */
    @Override
    public String getBotToken() {
        return botToken;
    }
}
