package org.example.flower_delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.handler.CallbackQueryHandler;
import org.example.flower_delivery.handler.CourierRegistrationHandler;
import org.example.flower_delivery.handler.MyOrdersSelectionHandler;
import org.example.flower_delivery.handler.OrderCreationHandler;
import org.example.flower_delivery.handler.ShopRegistrationHandler;
import org.example.flower_delivery.handler.StartCommandHandler;
import org.example.flower_delivery.handler.CourierAvailableOrdersHandler;
import org.example.flower_delivery.model.Courier;
import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.OrderStatus;
import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.service.OrderService;
import org.example.flower_delivery.service.ShopService;
import org.example.flower_delivery.util.GeoUtil;
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
     * Отправить текст курьеру и вернуть меню (📋 Доступные заказы, 🚚 Мои заказы, 💰 Моя статистика).
     * Без Markdown — для сообщений после геолокации, чтобы не ломаться на спецсимволах.
     */
    public void sendCourierMenuPlain(Long chatId, String text) {
        KeyboardRow row1 = new KeyboardRow();
        row1.add("📋 Доступные заказы");
        row1.add("🚚 Мои заказы");
        row1.add("💰 Моя статистика");
        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setKeyboard(List.of(row1));
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

        // Есть ли «свежая» гео курьера (до 30 мин) — тогда показываем ближайшие заказы и маршруты
        boolean hasFreshLocation = courier.getLastLocationAt() != null
                && courier.getLastLocationAt().isAfter(LocalDateTime.now().minusMinutes(30))
                && courier.getLastLatitude() != null && courier.getLastLongitude() != null;

        List<Order> availableOrders;
        if (hasFreshLocation) {
            // Честное распределение: первые 5 — ближайшие, следующие 5 — от «других» магазинов (кому курьер мало отдавал за 24 ч)
            availableOrders = orderService.getAvailableOrdersWithFairness(
                    courier.getLastLatitude().doubleValue(), courier.getLastLongitude().doubleValue(),
                    courier.getUser(), 5, 5);
        } else {
            availableOrders = orderService.getAvailableOrdersWithShop();
        }

        if (availableOrders.isEmpty()) {
            sendSimpleMessage(chatId, "📋 *Доступные заказы*\n\n" +
                    "Сейчас нет свободных заказов.\n" +
                    "Загляни сюда чуть позже.");
            return;
        }

        int limit = Math.min(10, availableOrders.size());
        List<Order> ordersToShow = availableOrders.subList(0, limit);

        if (hasFreshLocation) {
            // Список с расстоянием и кнопками «Маршрут до магазина»
            var content = buildAvailableOrdersContentWithLocation(ordersToShow,
                    courier.getLastLatitude().doubleValue(), courier.getLastLongitude().doubleValue());
            courierAvailableOrdersHandler.saveLastAvailableOrders(telegramId, ordersToShow);
            SendMessage message = new SendMessage();
            message.setChatId(chatId.toString());
            message.setText(content.text());
            message.setParseMode("Markdown");
            message.setReplyMarkup(content.markup());
            try {
                execute(message);
            } catch (TelegramApiException e) {
                log.error("Ошибка отправки списка доступных заказов курьеру: chatId={}", chatId, e);
            }
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

    /** Текст и клавиатура списка «Доступные заказы» с расстоянием и кнопками «Маршрут до магазина» (Яндекс). */
    public record AvailableOrdersContent(String text, InlineKeyboardMarkup markup) {}

    public AvailableOrdersContent buildAvailableOrdersContentWithLocation(List<Order> ordersToShow, double courierLat, double courierLon) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 *Доступные заказы* (от ближайших)\n\n");
        sb.append("Показаны ").append(ordersToShow.size()).append(" заказов:\n\n");

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm");
        for (int i = 0; i < ordersToShow.size(); i++) {
            Order order = ordersToShow.get(i);
            int number = i + 1;
            sb.append("*").append(number).append(". Заказ*");
            if (order.getShop() != null && order.getShop().getLatitude() != null && order.getShop().getLongitude() != null) {
                double km = GeoUtil.distanceKm(courierLat, courierLon,
                        order.getShop().getLatitude().doubleValue(), order.getShop().getLongitude().doubleValue());
                sb.append(" — _").append(String.format("%.1f", km)).append(" км_");
            }
            sb.append("\n");
            if (order.getShop() != null) {
                String pickup = order.getShop().getPickupAddress() != null ? order.getShop().getPickupAddress() : "—";
                sb.append("   🏪 Забрать: ").append(pickup).append("\n");
            }
            if (order.isMultiStopOrder()) {
                List<org.example.flower_delivery.model.OrderStop> stops = orderService.getOrderStops(order.getId());
                if (!stops.isEmpty()) {
                    for (org.example.flower_delivery.model.OrderStop stop : stops) {
                        sb.append("   📍 ").append(stop.getRecipientName()).append(" — ").append(stop.getDeliveryAddress()).append("\n");
                    }
                } else {
                    sb.append("   📍 ").append(order.getDeliveryAddress()).append("\n");
                    sb.append("   👤 ").append(order.getRecipientName()).append(" (").append(order.getRecipientPhone()).append(")\n");
                }
            } else {
                sb.append("   📍 ").append(order.getDeliveryAddress()).append("\n");
                sb.append("   👤 ").append(order.getRecipientName()).append(" (").append(order.getRecipientPhone()).append(")\n");
            }
            sb.append("   💰 ").append(order.getDeliveryPrice()).append("₽\n");
            if (order.getCreatedAt() != null) {
                sb.append("   📅 Создан: ").append(order.getCreatedAt().format(fmt)).append("\n");
            }
            sb.append("\n");
        }

        // Только кнопка «Выбрать заказ»; маршрут показываем после выбора заказа (в сообщении «Заказ взят!»)
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();
        keyboard.add(List.of(InlineKeyboardButton.builder().text("🔎 Выбрать заказ").callbackData("courier_orders_select").build()));
        return new AvailableOrdersContent(sb.toString(), new InlineKeyboardMarkup(keyboard));
    }

    /** Ссылка на маршрут в Яндекс.Картах: от (lat1,lon1) до (lat2,lon2). */
    private static String buildYandexRouteUrl(double fromLat, double fromLon, double toLat, double toLon) {
        return "https://yandex.ru/maps/?rtext=" + fromLat + "," + fromLon + "~" + toLat + "," + toLon + "&rtt=auto";
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
     */
    public CourierMyOrdersContent buildCourierMyOrdersContent(Courier courier, List<Order> allOrders) {
        int max = 15;
        int fromIndex = Math.max(0, allOrders.size() - max);
        List<Order> orders = allOrders.subList(fromIndex, allOrders.size());

        StringBuilder sb = new StringBuilder();
        sb.append("🚚 *Мои заказы (курьер)*\n\n");
        sb.append("Всего: ").append(allOrders.size())
                .append(", показаны последние ").append(orders.size()).append("\n\n");

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            if (order.getShop() != null && order.getShop().getPickupAddress() != null) {
                sb.append("*").append(i + 1).append(". ").append(order.getRecipientName()).append("*\n");
                sb.append("   🏪 Забрать: ").append(order.getShop().getPickupAddress()).append("\n");
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

        List<InlineKeyboardButton> statusRow = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            Order order = orders.get(i);
            OrderStatus next = nextStatusForCourier(order.getStatus());
            if (next == null) continue;
            // Вариант B: мультиадрес в пути — кнопки «Доставлено в точку 1», «Доставлено в точку 2» …
            if (next == DELIVERED && order.isMultiStopOrder()) {
                List<org.example.flower_delivery.model.OrderStop> stops = orderService.getOrderStops(order.getId());
                for (org.example.flower_delivery.model.OrderStop stop : stops) {
                    if (!stop.isDelivered()) {
                        InlineKeyboardButton btn = new InlineKeyboardButton();
                        btn.setText((i + 1) + ". Точка " + stop.getStopNumber() + " → Вручил");
                        btn.setCallbackData("courier_stop_delivered:" + order.getId() + ":" + stop.getStopNumber());
                        statusRow.add(btn);
                    }
                }
            } else {
                InlineKeyboardButton btn = new InlineKeyboardButton();
                btn.setText((i + 1) + " → " + next.getDisplayName());
                btn.setCallbackData("courier_order_next:" + order.getId());
                statusRow.add(btn);
            }
        }
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (int r = 0; r < statusRow.size(); r += 4) {
            rows.add(statusRow.subList(r, Math.min(r + 4, statusRow.size())));
        }
        markup.setKeyboard(rows);
        return new CourierMyOrdersContent(sb.toString(), markup);
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

        // С подгруженными stops, чтобы getTotalDeliveryPrice() не падал с LazyInit у мультиадреса
        List<Order> allOrders = orderService.getOrdersByCourierWithStops(courierOpt.get().getUser());
        java.time.LocalDate now = java.time.LocalDate.now();
        java.time.YearMonth thisMonth = java.time.YearMonth.from(now);

        long totalDelivered = allOrders.stream().filter(o -> o.getStatus() == DELIVERED).count();
        java.math.BigDecimal totalSum = allOrders.stream()
                .filter(o -> o.getStatus() == DELIVERED)
                .map(Order::getTotalDeliveryPrice)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        long monthDelivered = allOrders.stream()
                .filter(o -> o.getStatus() == DELIVERED && o.getDeliveredAt() != null
                        && java.time.YearMonth.from(o.getDeliveredAt()).equals(thisMonth))
                .count();
        java.math.BigDecimal monthSum = allOrders.stream()
                .filter(o -> o.getStatus() == DELIVERED && o.getDeliveredAt() != null
                        && java.time.YearMonth.from(o.getDeliveredAt()).equals(thisMonth))
                .map(Order::getTotalDeliveryPrice)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);

        String monthName = thisMonth.getMonth().getDisplayName(java.time.format.TextStyle.FULL, new java.util.Locale("ru"));
        String text = "💰 *Моя статистика*\n\n" +
                "📦 *Всего доставлено:* " + totalDelivered + " заказов\n" +
                "💵 *Сумма:* " + totalSum + " ₽\n\n" +
                "📅 *За " + monthName + ":* " + monthDelivered + " заказов, " + monthSum + " ₽";
        sendSimpleMessage(chatId, text);
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
