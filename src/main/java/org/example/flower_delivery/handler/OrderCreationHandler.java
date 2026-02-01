package org.example.flower_delivery.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.Bot;
import org.example.flower_delivery.model.OrderCreationData;
import org.example.flower_delivery.model.OrderCreationState;
import org.example.flower_delivery.service.DeliveryPriceService;
import org.example.flower_delivery.service.GeocodingService;
import org.example.flower_delivery.service.ShopService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.example.flower_delivery.service.OrderService;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import org.example.flower_delivery.model.Order;
import org.example.flower_delivery.model.Shop;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCreationHandler {

    private final ShopService shopService;
    private final OrderService orderService;
    private final GeocodingService geocodingService;
    private final DeliveryPriceService deliveryPriceService;

    @Autowired
    @Lazy
    private Bot bot;

    // Хранилище данных создания заказа для каждого юзера
    private final Map<Long, OrderCreationData> dataMap = new ConcurrentHashMap<>();

    /**
     * Начать создание заказа.
     * Вызывается когда магазин нажимает "Создать заказ".
     */
    public void startOrderCreation(Long telegramId, Long chatId) {
        log.info("Попытка создания заказа: telegramId={}", telegramId);

        // Проверяем, есть ли у юзера магазин
        var shopOptional = shopService.findByUserTelegramId(telegramId);
        if (shopOptional.isEmpty()) {
            log.warn("Магазин не найден: telegramId={}", telegramId);
            sendMessage(chatId, "❌ У тебя нет зарегистрированного магазина.\n" +
                    "Сначала зарегистрируй магазин через /start");
            return;
        }

        Shop shop = shopOptional.get();

        // Проверяем, активирован ли магазин админом
        if (!shop.getIsActive()) {
            log.warn("Магазин не активирован: shopId={}, telegramId={}", shop.getId(), telegramId);
            sendMessage(chatId, "⏳ *Магазин ещё не активирован*\n\n" +
                    "Администратор должен подтвердить твой магазин.\n" +
                    "После активации ты сможешь создавать заказы.\n\n" +
                    "Ожидай! 🙏");
            return;
        }

        // Всё ок — начинаем создание заказа
        log.info("Начало создания заказа: shopId={}, telegramId={}", shop.getId(), telegramId);

        OrderCreationData data = new OrderCreationData();
        data.setState(OrderCreationState.WAITING_DELIVERY_DATE);
        dataMap.put(telegramId, data);

        // Проверяем время — после 21:00 только на завтра
        LocalTime now = LocalTime.now();
        LocalTime endOfDay = LocalTime.of(21, 0);
        
        if (now.isAfter(endOfDay)) {
            // После 21:00 — только на завтра
            sendMessageWithDateButtons(chatId, "📦 *Создание заказа*\n\n" +
                    "⏰ Рабочее время закончилось (до 21:00)\n" +
                    "Заказ будет на *завтра*\n\n" +
                    "Шаг 1 из 6\n" +
                    "Выберите дату доставки:", true);
        } else {
            // В рабочее время — можно сегодня или завтра
            sendMessageWithDateButtons(chatId, "📦 *Создание заказа*\n\n" +
                    "Шаг 1 из 6\n" +
                    "Выберите дату доставки:", false);
        }
    }
    
    /**
     * Отправить сообщение с кнопками выбора даты.
     */
    private void sendMessageWithDateButtons(Long chatId, String text, boolean onlyTomorrow) {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd.MM");
        
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setParseMode("Markdown");
        
        InlineKeyboardMarkup keyboard;
        
        if (onlyTomorrow) {
            // Только завтра
            InlineKeyboardButton tomorrowBtn = InlineKeyboardButton.builder()
                    .text("📅 Завтра (" + tomorrow.format(formatter) + ")")
                    .callbackData("delivery_date_tomorrow")
                    .build();
            keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(List.of(tomorrowBtn))
                    .build();
        } else {
            // Сегодня и завтра
            InlineKeyboardButton todayBtn = InlineKeyboardButton.builder()
                    .text("📅 Сегодня (" + today.format(formatter) + ")")
                    .callbackData("delivery_date_today")
                    .build();
            InlineKeyboardButton tomorrowBtn = InlineKeyboardButton.builder()
                    .text("📅 Завтра (" + tomorrow.format(formatter) + ")")
                    .callbackData("delivery_date_tomorrow")
                    .build();
            keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(List.of(todayBtn, tomorrowBtn))
                    .build();
        }
        
        message.setReplyMarkup(keyboard);
        
        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения с кнопками даты: chatId={}", chatId, e);
        }
    }
    
    /**
     * Обработка выбора даты (из callback).
     */
    public void handleDateSelection(Long telegramId, Long chatId, String callbackData) {
        OrderCreationData data = dataMap.get(telegramId);
        if (data == null || data.getState() != OrderCreationState.WAITING_DELIVERY_DATE) {
            return;
        }
        
        LocalDate selectedDate;
        String dateText;
        
        if (callbackData.equals("delivery_date_today")) {
            selectedDate = LocalDate.now();
            dateText = "сегодня";
        } else {
            selectedDate = LocalDate.now().plusDays(1);
            dateText = "завтра";
        }
        
        data.setDeliveryDate(selectedDate);
        data.setState(OrderCreationState.WAITING_RECIPIENT_NAME);
        
        sendMessage(chatId, "✅ Дата: *" + dateText + "* (" + selectedDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + ")\n\n" +
                "Шаг 2 из 6\n" +
                "Введите *имя получателя*:");
    }

    private void handleRecipientName(Long chatId, Long telegramId, String text, OrderCreationData data) {
        if (text.length() < 2) {
            sendMessage(chatId, "❌ Имя слишком короткое. Введи минимум 2 символа:");
            return;
        }
        data.setRecipientName(text);
        data.setState(OrderCreationState.WAITING_RECIPIENT_PHONE);

        sendMessage(chatId, "✅ Получатель: *" + text + "*\n\n" +
                "Шаг 3 из 6\n" +
                "Введите *телефон получателя*:");
    }

    /**
     * Шаг 2: Обработка телефона получателя.
     */
    private void handleRecipientPhone(Long chatId, Long telegramId, String text, OrderCreationData data) {
        if (text.length() < 5) {
            sendMessage(chatId, "❌ Телефон слишком короткий. Попробуй ещё раз:");
            return;
        }
        data.setRecipientPhone(text);
        data.setState(OrderCreationState.WAITING_DELIVERY_ADDRESS);

        sendMessage(chatId, "✅ Телефон: *" + text + "*\n\n" +
                "Шаг 4 из 6\n" +
                "Введи *полный адрес доставки*:\n\n" +
                "_Пример: ул. Ленина 44, подъезд 2, кв. 15_");
    }


    /**
     * Шаг 4: Обработка полного адреса доставки + автоматический расчёт цены.
     */
    private void handleDeliveryAddress(Long chatId, Long telegramId, String text, OrderCreationData data) {
        if (text.length() < 10) {
            sendMessage(chatId, "❌ Адрес слишком короткий.\n\n" +
                    "Укажи полный адрес: улица, дом, подъезд, квартира\n" +
                    "_Пример: ул. Ленина 44, подъезд 2, кв. 15_");
            return;
        }

        data.setDeliveryAddress(text);
        
        // Пробуем геокодировать адрес
        sendMessage(chatId, "🔍 Определяю расстояние...");
        
        Optional<GeocodingService.GeocodingResult> geocodeResult = geocodingService.geocode(text);
        
        if (geocodeResult.isEmpty()) {
            // Геокодирование не удалось — ручной ввод цены
            log.warn("Не удалось геокодировать адрес: {}", text);
            data.setState(OrderCreationState.WAITING_DELIVERY_PRICE);
            sendMessage(chatId, "⚠️ Не удалось определить адрес автоматически.\n\n" +
                    "Шаг 5 из 6\n" +
                    "Введите *стоимость доставки* вручную:\n\n" +
                    "💡 *Тарифы* (мин. 300₽):\n" +
                    deliveryPriceService.getTariffDescription());
            return;
        }
        
        GeocodingService.GeocodingResult geo = geocodeResult.get();
        
        // Проверяем регион
        if (!geocodingService.isInAllowedRegion(geo)) {
            data.setState(OrderCreationState.WAITING_DELIVERY_PRICE);
            sendMessage(chatId, "⚠️ Адрес находится за пределами зоны доставки.\n" +
                    "Регион: " + geo.region() + "\n\n" +
                    "Шаг 5 из 6\n" +
                    "Введите *стоимость доставки* вручную:");
            return;
        }
        
        // Сохраняем координаты
        data.setDeliveryLatitude(geo.latitude());
        data.setDeliveryLongitude(geo.longitude());
        
        // Получаем координаты магазина
        Shop shop = shopService.findByUserTelegramId(telegramId).orElse(null);
        if (shop == null || shop.getLatitude() == null || shop.getLongitude() == null) {
            // У магазина нет координат — геокодируем его адрес
            log.info("У магазина нет координат, геокодируем pickup_address");
            geocodeShopIfNeeded(shop);
        }
        
        // Если теперь есть координаты магазина — считаем расстояние
        if (shop != null && shop.getLatitude() != null && shop.getLongitude() != null) {
            double shopLat = shop.getLatitude().doubleValue();
            double shopLon = shop.getLongitude().doubleValue();
            
            DeliveryPriceService.DeliveryCalculation calc = deliveryPriceService.calculate(
                    shopLat, shopLon, geo.latitude(), geo.longitude()
            );
            
            data.setDistanceKm(calc.distanceKm());
            data.setSuggestedPrice(calc.price());
            data.setState(OrderCreationState.WAITING_PRICE_CONFIRMATION);
            
            // Показываем кнопку подтверждения цены
            sendPriceConfirmation(chatId, geo.fullAddress(), calc);
        } else {
            // Не удалось получить координаты магазина — ручной ввод
            data.setState(OrderCreationState.WAITING_DELIVERY_PRICE);
            sendMessage(chatId, "✅ Адрес найден: *" + geo.fullAddress() + "*\n\n" +
                    "⚠️ Не удалось рассчитать расстояние.\n\n" +
                    "Шаг 5 из 6\n" +
                    "Введите *стоимость доставки* вручную:\n\n" +
                    "💡 *Тарифы* (мин. 300₽):\n" +
                    deliveryPriceService.getTariffDescription());
        }
    }
    
    /**
     * Геокодировать адрес магазина (если координаты не заполнены).
     */
    private void geocodeShopIfNeeded(Shop shop) {
        if (shop == null || shop.getPickupAddress() == null) return;
        if (shop.getLatitude() != null && shop.getLongitude() != null) return;
        
        Optional<GeocodingService.GeocodingResult> result = geocodingService.geocode(shop.getPickupAddress());
        if (result.isPresent()) {
            GeocodingService.GeocodingResult geo = result.get();
            shop.setLatitude(BigDecimal.valueOf(geo.latitude()));
            shop.setLongitude(BigDecimal.valueOf(geo.longitude()));
            shopService.save(shop);
            log.info("Координаты магазина обновлены: lat={}, lon={}", geo.latitude(), geo.longitude());
        }
    }
    
    /**
     * Отправить сообщение с подтверждением цены.
     */
    private void sendPriceConfirmation(Long chatId, String address, DeliveryPriceService.DeliveryCalculation calc) {
        String text = "✅ *Адрес найден:*\n" + address + "\n\n" +
                "📏 *Расстояние:* " + calc.distanceKm() + " км\n" +
                "💰 *Рекомендуемая цена:* " + calc.price() + "₽\n\n" +
                "Шаг 5 из 6\n" +
                "Подтвердите цену или введите свою:";
        
        // Кнопка подтверждения
        InlineKeyboardButton confirmBtn = InlineKeyboardButton.builder()
                .text("✅ Подтвердить " + calc.price() + "₽")
                .callbackData("confirm_price_" + calc.price())
                .build();
        
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(confirmBtn))
                .build();
        
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setParseMode("Markdown");
        message.setReplyMarkup(keyboard);
        
        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения: chatId={}", chatId, e);
        }
    }
    
    /**
     * Обработка подтверждения цены (из callback).
     */
    public void handlePriceConfirmation(Long telegramId, Long chatId, BigDecimal price) {
        OrderCreationData data = dataMap.get(telegramId);
        if (data == null) return;
        
        data.setDeliveryPrice(price);
        data.setState(OrderCreationState.WAITING_COMMENT);
        
        sendMessage(chatId, "✅ Цена: *" + price + "₽*\n\n" +
                "Шаг 6 из 6\n" +
                "Введи *комментарий* (особые пожелания)\n" +
                "или отправь /skip чтобы пропустить:");
    }



    /**
     * Обработка ручного ввода цены (когда юзер не нажал кнопку подтверждения).
     */
    private void handleManualPrice(Long chatId, Long telegramId, String text, OrderCreationData data) {
        try {
            BigDecimal price = new BigDecimal(text.replace(",", "."));
            BigDecimal minPrice = deliveryPriceService.getMinPrice();
            
            if (price.compareTo(minPrice) < 0) {
                sendMessage(chatId, "❌ Минимальная цена доставки — *" + minPrice + "₽*\n\n" +
                        "Введи цену от " + minPrice + "₽ или нажми кнопку выше:");
                return;
            }

            data.setDeliveryPrice(price);
            data.setState(OrderCreationState.WAITING_COMMENT);

            sendMessage(chatId, "✅ Цена: *" + price + "₽*\n\n" +
                    "Шаг 6 из 6\n" +
                    "Введи *комментарий* (особые пожелания)\n" +
                    "или отправь /skip чтобы пропустить:");

        } catch (NumberFormatException e) {
            sendMessage(chatId, "❌ Некорректное число. Введи цену цифрами или нажми кнопку выше:");
        }
    }

    /**
     * Шаг 5: Обработка цены доставки (ручной ввод, когда геокодирование не удалось).
     */
    private static final BigDecimal MIN_DELIVERY_PRICE = new BigDecimal("300");
    
    private void handleDeliveryPrice(Long chatId, Long telegramId, String text, OrderCreationData data) {
        try {
            BigDecimal price = new BigDecimal(text.replace(",", "."));
            if (price.compareTo(MIN_DELIVERY_PRICE) < 0) {
                sendMessage(chatId, "❌ Минимальная цена доставки — *300₽*\n\n" +
                        "Введи цену от 300₽:");
                return;
            }

            data.setDeliveryPrice(price);
            data.setState(OrderCreationState.WAITING_COMMENT);

            sendMessage(chatId, "✅ Цена: *" + price + "₽*\n\n" +
                    "Шаг 6 из 6\n" +
                    "Введи *комментарий* (особые пожелания)\n" +
                    "или отправь /skip чтобы пропустить:");

        } catch (NumberFormatException e) {
            sendMessage(chatId, "❌ Некорректное число. Введи цену цифрами (например: 350):");
        }
    }

    /**
     * Шаг 5: Обработка комментария и создание заказа.
     */
    private void handleComment(Long chatId, Long telegramId, String text, OrderCreationData data) {
        // Если не /skip — сохраняем комментарий
        if (!text.equals("/skip")) {
            data.setComment(text);
        }

        // Получаем магазин юзера
        Shop shop = shopService.findByUserTelegramId(telegramId)
                .orElse(null);

        if (shop == null) {
            sendMessage(chatId, "❌ Ошибка: магазин не найден!");
            dataMap.remove(telegramId);
            return;
        }

        // Создаём заказ (с координатами если есть)
        try {
            Order order = orderService.createOrder(
                    shop,
                    data.getRecipientName(),
                    data.getRecipientPhone(),
                    data.getDeliveryAddress(),
                    data.getDeliveryPrice(),
                    data.getComment(),
                    data.getDeliveryDate(),
                    data.getDeliveryLatitude(),
                    data.getDeliveryLongitude()
            );

            // Очищаем данные
            dataMap.remove(telegramId);

            String dateStr = data.getDeliveryDate().equals(LocalDate.now()) ? "сегодня" : "завтра";
            
            // Формируем сообщение
            StringBuilder msg = new StringBuilder();
            msg.append("🎉 *Заказ создан!*\n\n");
            msg.append("📋 *Данные заказа:*\n");
            msg.append("• Дата: ").append(dateStr).append(" (").append(data.getDeliveryDate().format(DateTimeFormatter.ofPattern("dd.MM"))).append(")\n");
            msg.append("• Получатель: ").append(data.getRecipientName()).append("\n");
            msg.append("• Телефон: ").append(data.getRecipientPhone()).append("\n");
            msg.append("• Адрес: ").append(data.getDeliveryAddress()).append("\n");
            
            // Показываем расстояние если есть
            if (data.getDistanceKm() != null) {
                msg.append("• Расстояние: ").append(data.getDistanceKm()).append(" км\n");
            }
            
            msg.append("• Цена: ").append(data.getDeliveryPrice()).append("₽\n");
            msg.append("• Комментарий: ").append(data.getComment() != null ? data.getComment() : "—").append("\n\n");
            msg.append("⏳ Ожидайте, скоро курьер возьмёт заказ!");
            
            sendMessage(chatId, msg.toString());

        } catch (Exception e) {
            log.error("Ошибка создания заказа: telegramId={}", telegramId, e);
            dataMap.remove(telegramId);
            sendMessage(chatId, "❌ Ошибка при создании заказа: " + e.getMessage());
        }
    }



    /**
     * Обработать текстовое сообщение от юзера.
     *
     * @return true если сообщение обработано, false если юзер не в процессе создания заказа
     */
    public boolean handleMessage(Update update) {
        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText();

        // Проверяем, есть ли юзер в процессе создания заказа
        OrderCreationData data = dataMap.get(telegramId);
        if (data == null || data.getState() == OrderCreationState.NONE) {
            return false; // юзер не создает заказ.

        }

        log.debug("Обработка шага создания заказа: telegramId={}, state={}",
                telegramId, data.getState());

        // Обрабатываем в зависимости от текущего шага

        switch (data.getState()) {
            case WAITING_RECIPIENT_NAME:
                handleRecipientName(chatId, telegramId, text, data);
                break;
            case WAITING_RECIPIENT_PHONE:
                handleRecipientPhone(chatId, telegramId, text, data);
                break;
            case WAITING_DELIVERY_ADDRESS:
                handleDeliveryAddress(chatId, telegramId, text, data);
                break;
            case WAITING_PRICE_CONFIRMATION:
                // Юзер ввёл свою цену вместо подтверждения
                handleManualPrice(chatId, telegramId, text, data);
                break;
            case WAITING_DELIVERY_PRICE:
                handleDeliveryPrice(chatId, telegramId, text, data);
                break;
            case WAITING_COMMENT:
                handleComment(chatId, telegramId, text, data);
                break;
            default:
                return false;
        }
        return true;
    }

    /**
     * Отправить сообщение.
     */
    private void sendMessage(Long chatId, String text) {
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId.toString());
        sendMessage.setText(text);
        sendMessage.setParseMode("Markdown");

        try {
            bot.execute(sendMessage);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки сообщения: chatId={}", chatId, e);
        }
    }
}




