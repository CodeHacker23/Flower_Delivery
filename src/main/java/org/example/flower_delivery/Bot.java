package org.example.flower_delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.handler.CallbackQueryHandler;
import org.example.flower_delivery.handler.CourierRegistrationHandler;
import org.example.flower_delivery.handler.MyOrdersSelectionHandler;
import org.example.flower_delivery.handler.OrderCreationHandler;
import org.example.flower_delivery.handler.ShopRegistrationHandler;
import org.example.flower_delivery.handler.StartCommandHandler;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.service.OrderService;
import org.example.flower_delivery.service.ShopService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;
import java.time.format.DateTimeFormatter;

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
    
    // Инжектируем обработчик создания заказа
    private final OrderCreationHandler orderCreationHandler;

    // Обработчик выбора заказа из списка \"Мои заказы\"
    private final MyOrdersSelectionHandler myOrdersSelectionHandler;

    // Обработчик редактирования заказа (меню + ввод нового значения)
    private final org.example.flower_delivery.handler.OrderEditHandler orderEditHandler;
    
    // Инжектируем сервис магазинов (для временной команды /activate)
    private final ShopService shopService;
    
    // Инжектируем сервис заказов (для просмотра заказов)
    private final OrderService orderService;

    // Инжектируем сервис курьеров (для временной активации командой /k)
    private final org.example.flower_delivery.service.CourierService courierService;
    
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
        
        // Проверяем, есть ли сообщение с текстом
        if (update.hasMessage() && update.getMessage().hasText()) {
            String text = update.getMessage().getText();
            Long telegramId = update.getMessage().getFrom().getId();
            Long chatId = update.getMessage().getChatId();
            
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

            // Если юзер в процессе редактирования заказа (ждёт ввод нового адреса/телефона/комментария)
            if (orderEditHandler.isEditing(telegramId)) {
                if (orderEditHandler.handleText(telegramId, chatId, text)) {
                    return;
                }
            }
            
            // Обработка команд
            if (text.equals("/start")) {
                startCommandHandler.handle(update);
            }
            // ВРЕМЕННАЯ КОМАНДА: активировать свой магазин (для тестирования)
            else if (text.equals("/r")) {
                handleActivateCommand(update);
            }
            // ВРЕМЕННАЯ КОМАНДА: активировать своего курьера (для тестирования)
            else if (text.equals("/k")) {
                handleActivateCourierCommand(update);
            }
            // Кнопка меню: Создать заказ
            else if (text.equals("📦 Создать заказ")) {
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
            sb.append("   📊 Статус: ").append(order.getStatus().getDisplayName()).append("\n");

            // Дата создания заказа (для понимания, когда заявка появилась)
            if (order.getCreatedAt() != null) {
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
                sb.append("   📅 Создан: ").append(order.getCreatedAt().format(fmt)).append("\n");
            }
            
            // Если есть курьер — показываем его телефон
            if (order.getCourier() != null) {
                sb.append("   🚴 Курьер: ").append(order.getCourier().getPhone()).append("\n");
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
        // Один ряд с тремя кнопками
        KeyboardRow row1 = new KeyboardRow();
        row1.add("📋 Доступные заказы");
        row1.add("🚚 Мои заказы");
        row1.add("💰 Моя статистика");

        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(List.of(row1));
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
     * Кнопка "📋 Доступные заказы" в меню курьера.
     * Пока заглушка: позже сюда добавим выбор и сортировку по расстоянию.
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

        // Получаем все свободные заказы (NEW)
        List<Order> availableOrders = orderService.getAvailableOrders();
        if (availableOrders.isEmpty()) {
            sendSimpleMessage(chatId, "📋 *Доступные заказы*\n\n" +
                    "Сейчас нет свободных заказов.\n" +
                    "Загляни сюда чуть позже.");
            return;
        }

        // Ограничим список, чтобы не заваливать курьера (например, первыми 10)
        int limit = Math.min(10, availableOrders.size());
        List<Order> ordersToShow = availableOrders.subList(0, limit);

        StringBuilder sb = new StringBuilder();
        sb.append("📋 *Доступные заказы*\n\n");
        sb.append("Показаны первые ").append(limit).append(" из ").append(availableOrders.size()).append(":\n\n");

        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        for (int i = 0; i < ordersToShow.size(); i++) {
            Order order = ordersToShow.get(i);
            int number = i + 1;

            sb.append("*").append(number).append(". Заказ ").append(order.getId().toString().substring(0, 8)).append("*\n");
            sb.append("   📍 ").append(order.getDeliveryAddress()).append("\n");
            sb.append("   👤 ").append(order.getRecipientName()).append(" (").append(order.getRecipientPhone()).append(")\n");
            sb.append("   💰 ").append(order.getDeliveryPrice()).append("₽\n");
            if (order.getCreatedAt() != null) {
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm");
                sb.append("   📅 Создан: ").append(order.getCreatedAt().format(fmt)).append("\n");
            }
            sb.append("\n");

            InlineKeyboardButton takeBtn = InlineKeyboardButton.builder()
                    .text("✅ Взять заказ #" + number)
                    .callbackData("courier_take_" + order.getId())
                    .build();
            keyboard.add(List.of(takeBtn));
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup(keyboard);

        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(sb.toString());
        message.setParseMode("Markdown");
        message.setReplyMarkup(markup);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки списка доступных заказов курьеру: chatId={}", chatId, e);
        }
    }

    /**
     * Кнопка "🚚 Мои заказы" в меню курьера.
     * Пока заглушка: позже покажем активные заказы, которые курьер сейчас везёт.
     */
    private void handleCourierMyOrdersButton(Update update) {
        Long chatId = update.getMessage().getChatId();
        sendSimpleMessage(chatId, "🚚 *Мои заказы (курьер)*\n\n" +
                "Скоро здесь будет список заказов, которые ты уже взял.\n" +
                "Пока это заглушка.");
    }

    /**
     * Кнопка "💰 Моя статистика" в меню курьера.
     * Пока заглушка: позже посчитаем заработок за период.
     */
    private void handleCourierStatsButton(Update update) {
        Long chatId = update.getMessage().getChatId();
        sendSimpleMessage(chatId, "💰 *Моя статистика*\n\n" +
                "Здесь появится статистика по доставкам и заработку.\n" +
                "Пока это заглушка.");
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
