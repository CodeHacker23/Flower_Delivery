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
        data.setState(OrderCreationState.WAITING_DELIVERY_ADDRESS);
        dataMap.put(telegramId, data);

        sendMessage(chatId, "📦 *Создание заказа*\n\n" +
                "Шаг 1 из 6\n" +
                "Введите *адрес доставки*:\n\n" +
                "_Пример: ул. Ленина 44, подъезд 2, кв. 15_");
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
        log.info("Магазин выбрал дату доставки: telegramId={}, date={}, label={}",
                telegramId, selectedDate, dateText);

        sendMessage(chatId, "✅ Дата: *" + dateText + "* (" + selectedDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) + ")\n\n" +
                "Шаг 4 из 6\n" +
                "Введите *имя получателя*:");
    }

    private void handleRecipientName(Long chatId, Long telegramId, String text, OrderCreationData data) {
        if (text.length() < 2) {
            log.debug("Магазин прислал слишком короткое имя получателя: telegramId={}, raw='{}'", telegramId, text);
            sendMessage(chatId, "❌ Имя слишком короткое. Введи минимум 2 символа:");
            return;
        }
        data.setRecipientName(text);
        data.setState(OrderCreationState.WAITING_RECIPIENT_PHONE);
        log.info("Магазин задал имя получателя: telegramId={}, name='{}'", telegramId, text);

        sendMessage(chatId, "✅ Получатель: *" + text + "*\n\n" +
                "Шаг 5 из 6\n" +
                "Введите *телефон получателя*:");
    }

    /**
     * Шаг 2: Обработка телефона получателя.
     */
    private void handleRecipientPhone(Long chatId, Long telegramId, String text, OrderCreationData data) {
        if (text.length() < 5) {
            log.debug("Магазин прислал странный телефон получателя: telegramId={}, raw='{}'", telegramId, text);
            sendMessage(chatId, "❌ Телефон слишком короткий. Попробуй ещё раз:");
            return;
        }
        data.setRecipientPhone(text);
        data.setState(OrderCreationState.WAITING_STOP_COMMENT);
        log.info("Магазин задал телефон получателя: telegramId={}, phone='{}'", telegramId, text);

        sendMessage(chatId, "✅ Телефон: *" + text + "*\n\n" +
                "Шаг 6 из 6\n" +
                "Введите *комментарий* к доставке\n" +
                "_Пример: домофон 123, позвонить за 10 мин_\n\n" +
                "или отправьте /skip чтобы пропустить:");
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
        log.info("Магазин задал адрес доставки: telegramId={}, address='{}'", telegramId, text);

        // Пробуем геокодировать адрес
        sendMessage(chatId, "🔍 Определяю расстояние...");
        
        Optional<GeocodingService.GeocodingResult> geocodeResult = geocodingService.geocode(text);
        
        if (geocodeResult.isEmpty()) {
            // Геокодирование не удалось — ручной ввод цены
            log.warn("Не удалось геокодировать адрес: {}", text);
            data.setState(OrderCreationState.WAITING_DELIVERY_PRICE);
            sendMessage(chatId, "⚠️ Не удалось определить адрес автоматически.\n\n" +
                    "Шаг 2 из 6\n" +
                    "Введите *стоимость доставки* вручную:\n\n" +
                    "💡 *Тарифная сетка:*\n" +
                    deliveryPriceService.getTariffDescription());
            return;
        }
        
        GeocodingService.GeocodingResult geo = geocodeResult.get();
        
        // Проверяем регион
        if (!geocodingService.isInAllowedRegion(geo)) {
            data.setState(OrderCreationState.WAITING_DELIVERY_PRICE);
            sendMessage(chatId, "⚠️ Адрес находится за пределами зоны доставки.\n" +
                    "Регион: " + geo.region() + "\n\n" +
                    "Шаг 2 из 6\n" +
                    "Введите *стоимость доставки* вручную:\n\n" +
                    "💡 *Тарифная сетка:*\n" + deliveryPriceService.getTariffDescription());
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
                    "Шаг 2 из 6\n" +
                    "Введите *стоимость доставки* вручную:\n\n" +
                    "💡 *Тарифная сетка:*\n" +
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
                "Шаг 2 из 6\n" +
                "Подтвердите цену или введите свою:";
        
        // Кнопки: подтвердить цену | отменить заказ
        InlineKeyboardButton confirmBtn = InlineKeyboardButton.builder()
                .text("✅ Подтвердить " + calc.price() + "₽")
                .callbackData("confirm_price_" + calc.price())
                .build();
        InlineKeyboardButton cancelBtn = InlineKeyboardButton.builder()
                .text("❌ Отменить заказ")
                .callbackData("order_creation_cancel")
                .build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(confirmBtn, cancelBtn))
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
        data.setState(OrderCreationState.WAITING_DELIVERY_DATE);
        boolean onlyTomorrow = LocalTime.now().isAfter(LocalTime.of(21, 0));
        sendMessageWithDateButtons(chatId, "✅ Цена: *" + price + "₽*\n\n" +
                "Шаг 3 из 6\n" +
                "Выберите дату доставки:", onlyTomorrow);
    }
    
    /**
     * Обработка комментария к первой точке.
     */
    private void handleStopComment(Long chatId, Long telegramId, String text, OrderCreationData data) {
        // Если не /skip — сохраняем комментарий
        if (!text.equals("/skip")) {
            data.setComment(text); // временно сохраняем в общий комментарий
        }
        
        // Сохраняем первую точку в список
        data.saveFirstStopFromFields();
        
        // Если был комментарий - добавляем его к точке
        if (!text.equals("/skip") && !data.getStops().isEmpty()) {
            data.getStops().get(0).setComment(text);
        }
        
        // Спрашиваем про дополнительную точку
        askAddAdditionalStop(chatId, data);
    }
    
    // ============================================
    // МУЛЬТИАДРЕСНАЯ ДОСТАВКА
    // ============================================
    
    /**
     * Спросить: добавить ещё адрес?
     */
    private void askAddAdditionalStop(Long chatId, OrderCreationData data) {
        data.setState(OrderCreationState.WAITING_ASK_ADDITIONAL_STOP);
        
        int currentStops = data.getStops().size();
        BigDecimal totalPrice = data.getTotalPrice();
        
        String text = "✅ *Точка " + currentStops + " добавлена!*\n" +
                "💰 Текущая сумма: *" + totalPrice + "₽*\n\n" +
                "➕ *Добавить ещё один адрес доставки?*";
        
        InlineKeyboardButton yesBtn = InlineKeyboardButton.builder()
                .text("➕ Добавить адрес")
                .callbackData("add_stop_yes")
                .build();
        
        InlineKeyboardButton noBtn = InlineKeyboardButton.builder()
                .text("✅ Завершить")
                .callbackData("add_stop_no")
                .build();
        
        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(yesBtn, noBtn))
                .build();
        
        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setParseMode("Markdown");
        message.setReplyMarkup(keyboard);
        
        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки: chatId={}", chatId, e);
        }
    }
    
    /**
     * Обработка ответа: добавить ещё адрес? (из callback)
     */
    public void handleAddStopDecision(Long telegramId, Long chatId, boolean addMore) {
        OrderCreationData data = dataMap.get(telegramId);
        if (data == null) return;
        
        if (addMore) {
            // Начинаем ввод дополнительной точки
            data.startNewStop();
            data.setState(OrderCreationState.WAITING_ADDITIONAL_RECIPIENT_NAME);
            
            int stopNumber = data.getStops().size() + 1;
            
            sendMessage(chatId, "📍 *Дополнительная точка #" + stopNumber + "*\n\n" +
                    "Введите *имя получателя*:");
        } else {
            // Сразу создаём заказ (комментарии уже есть у каждой точки)
            finalizeOrder(telegramId, chatId, data);
        }
    }
    
    /**
     * Финализация и создание заказа.
     */
    private void finalizeOrder(Long telegramId, Long chatId, OrderCreationData data) {
        // Получаем магазин юзера
        Shop shop = shopService.findByUserTelegramId(telegramId).orElse(null);

        if (shop == null) {
            sendMessage(chatId, "❌ Ошибка: магазин не найден!");
            dataMap.remove(telegramId);
            return;
        }

        try {
            Order order;
            
            if (data.isMultiStop()) {
                order = createMultiStopOrder(shop, data);
            } else {
                order = createSingleStopOrder(shop, data);
            }

            dataMap.remove(telegramId);
            String confirmationMessage = buildOrderConfirmation(data, order);
            sendMessage(chatId, confirmationMessage);

        } catch (Exception e) {
            log.error("Ошибка создания заказа: telegramId={}", telegramId, e);
            dataMap.remove(telegramId);
            sendMessage(chatId, "❌ Ошибка при создании заказа: " + e.getMessage());
        }
    }
    
    /**
     * Склонение слова "точка"
     */
    private String getStopsWord(int count) {
        if (count == 1) return "точка";
        if (count >= 2 && count <= 4) return "точки";
        return "точек";
    }
    
    /**
     * Обработка имени получателя дополнительной точки.
     */
    private void handleAdditionalRecipientName(Long chatId, Long telegramId, String text, OrderCreationData data) {
        if (text.length() < 2) {
            sendMessage(chatId, "❌ Имя слишком короткое. Минимум 2 символа:");
            return;
        }
        
        data.getCurrentStop().setRecipientName(text);
        data.setState(OrderCreationState.WAITING_ADDITIONAL_RECIPIENT_PHONE);
        
        sendMessage(chatId, "✅ Получатель: *" + text + "*\n\n" +
                "Введите *телефон получателя*:");
    }
    
    /**
     * Обработка телефона получателя дополнительной точки.
     */
    private void handleAdditionalRecipientPhone(Long chatId, Long telegramId, String text, OrderCreationData data) {
        if (text.length() < 5) {
            sendMessage(chatId, "❌ Телефон слишком короткий:");
            return;
        }
        
        data.getCurrentStop().setRecipientPhone(text);
        data.setState(OrderCreationState.WAITING_ADDITIONAL_ADDRESS);
        
        sendMessage(chatId, "✅ Телефон: *" + text + "*\n\n" +
                "Введите *адрес доставки*:\n" +
                "_Пример: ул. Ленина 46, кв. 20_");
    }
    
    /**
     * Обработка адреса дополнительной точки.
     */
    private void handleAdditionalAddress(Long chatId, Long telegramId, String text, OrderCreationData data) {
        if (text.length() < 10) {
            sendMessage(chatId, "❌ Адрес слишком короткий:");
            return;
        }
        
        data.getCurrentStop().setDeliveryAddress(text);
        
        // Пробуем геокодировать
        sendMessage(chatId, "🔍 Определяю расстояние...");
        
        Optional<GeocodingService.GeocodingResult> geocodeResult = geocodingService.geocode(text);
        
        if (geocodeResult.isEmpty()) {
            // Не удалось — ручной ввод
            data.setState(OrderCreationState.WAITING_ADDITIONAL_PRICE);
            sendMessage(chatId, "⚠️ Не удалось определить адрес автоматически.\n\n" +
                    "Введите *стоимость доставки* до этой точки:\n" +
                    "_Минимум 300₽_");
            return;
        }
        
        GeocodingService.GeocodingResult geo = geocodeResult.get();
        
        // Сохраняем координаты
        data.getCurrentStop().setDeliveryLatitude(geo.latitude());
        data.getCurrentStop().setDeliveryLongitude(geo.longitude());
        
        // Получаем координаты предыдущей точки
        double[] prevCoords = data.getLastStopCoordinates();
        
        if (prevCoords == null) {
            // Нет координат предыдущей точки — ручной ввод
            data.setState(OrderCreationState.WAITING_ADDITIONAL_PRICE);
            sendMessage(chatId, "✅ Адрес найден: *" + geo.fullAddress() + "*\n\n" +
                    "⚠️ Не удалось рассчитать расстояние.\n\n" +
                    "Введите *стоимость доставки* до этой точки:\n" +
                    "_Минимум 300₽_");
            return;
        }
        
        // Считаем расстояние от предыдущей точки
        DeliveryPriceService.DeliveryCalculation calc = deliveryPriceService.calculateAdditionalStop(
                prevCoords[0], prevCoords[1], geo.latitude(), geo.longitude()
        );
        
        data.getCurrentStop().setDistanceKm(calc.distanceKm());
        data.getCurrentStop().setSuggestedPrice(calc.price());
        data.setState(OrderCreationState.WAITING_ADDITIONAL_PRICE_CONFIRMATION);
        
        // Показываем подтверждение цены
        sendAdditionalPriceConfirmation(chatId, geo.fullAddress(), calc);
    }
    
    /**
     * Отправить сообщение с подтверждением цены дополнительной точки.
     */
    private void sendAdditionalPriceConfirmation(Long chatId, String address, DeliveryPriceService.DeliveryCalculation calc) {
        String text = "✅ *Адрес найден:*\n" + address + "\n\n" +
                "📏 *Расстояние от предыдущей точки:* " + calc.distanceKm() + " км\n" +
                "💰 *Рекомендуемая цена:* +" + calc.price() + "₽\n\n" +
                "Подтвердите цену или введите свою:";
        
        InlineKeyboardButton confirmBtn = InlineKeyboardButton.builder()
                .text("✅ Подтвердить +" + calc.price() + "₽")
                .callbackData("confirm_additional_price_" + calc.price())
                .build();
        InlineKeyboardButton cancelBtn = InlineKeyboardButton.builder()
                .text("❌ Отменить заказ")
                .callbackData("order_creation_cancel")
                .build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(confirmBtn, cancelBtn))
                .build();

        SendMessage message = new SendMessage();
        message.setChatId(chatId.toString());
        message.setText(text);
        message.setParseMode("Markdown");
        message.setReplyMarkup(keyboard);

        try {
            bot.execute(message);
        } catch (TelegramApiException e) {
            log.error("Ошибка отправки: chatId={}", chatId, e);
        }
    }

    /**
     * Отменить создание заказа (по кнопке «Отменить заказ»).
     */
    public void cancelOrderCreation(Long telegramId, Long chatId) {
        dataMap.remove(telegramId);
        sendMessage(chatId, "❌ Создание заказа отменено.");
    }
    
    /**
     * Обработка подтверждения цены дополнительной точки (из callback).
     */
    public void handleAdditionalPriceConfirmation(Long telegramId, Long chatId, BigDecimal price) {
        OrderCreationData data = dataMap.get(telegramId);
        if (data == null || data.getCurrentStop() == null) return;
        
        data.getCurrentStop().setDeliveryPrice(price);
        
        // Спрашиваем комментарий к этой точке
        data.setState(OrderCreationState.WAITING_ADDITIONAL_STOP_COMMENT);
        sendMessage(chatId, "✅ Цена: *+" + price + "₽*\n\n" +
                "Введите *комментарий* к этой точке\n" +
                "_Пример: домофон 456, этаж 5_\n\n" +
                "или отправьте /skip чтобы пропустить:");
    }
    
    /**
     * Обработка комментария к дополнительной точке.
     */
    private void handleAdditionalStopComment(Long chatId, Long telegramId, String text, OrderCreationData data) {
        // Если не /skip — сохраняем комментарий
        if (!text.equals("/skip") && data.getCurrentStop() != null) {
            data.getCurrentStop().setComment(text);
        }
        
        // Сохраняем точку в список
        data.saveCurrentStop();
        
        // Спрашиваем про ещё одну точку
        askAddAdditionalStop(chatId, data);
    }
    
    /**
     * Обработка ручного ввода цены дополнительной точки.
     */
    private void handleAdditionalManualPrice(Long chatId, Long telegramId, String text, OrderCreationData data) {
        try {
            BigDecimal price = new BigDecimal(text.replace(",", "."));
            Double distKm = data.getCurrentStop() != null ? data.getCurrentStop().getDistanceKm() : null;
            BigDecimal minPrice = distKm != null
                    ? deliveryPriceService.getMinPriceForDistance(distKm)
                    : deliveryPriceService.getMinAdditionalStopPrice();

            if (price.compareTo(minPrice) < 0) {
                String distInfo = distKm != null
                        ? "Для " + String.format("%.1f", distKm) + " км минимум — *" + minPrice + "₽* (по тарифу).\n\n"
                        : "";
                sendMessage(chatId, "❌ " + distInfo + "Минимальная цена — *" + minPrice + "₽*\n\n" +
                        "💡 *Тарифная сетка:*\n" + deliveryPriceService.getTariffDescription() + "\n" +
                        "Введите цену от " + minPrice + "₽:");
                return;
            }

            data.getCurrentStop().setDeliveryPrice(price);
            data.setState(OrderCreationState.WAITING_ADDITIONAL_STOP_COMMENT);
            sendMessage(chatId, "✅ Цена: *+" + price + "₽*\n\n" +
                    "Введите *комментарий* к этой точке\n" +
                    "или отправьте /skip чтобы пропустить:");

        } catch (NumberFormatException e) {
            sendMessage(chatId, "❌ Введите число (например: 300):");
        }
    }
    
    /**
     * Обработка ручного ввода цены дополнительной точки (когда геокодирование не удалось).
     */
    private void handleAdditionalPrice(Long chatId, Long telegramId, String text, OrderCreationData data) {
        try {
            BigDecimal price = new BigDecimal(text.replace(",", "."));
            Double distKm = data.getCurrentStop() != null ? data.getCurrentStop().getDistanceKm() : null;
            BigDecimal minPrice = distKm != null
                    ? deliveryPriceService.getMinPriceForDistance(distKm)
                    : deliveryPriceService.getMinAdditionalStopPrice();

            if (price.compareTo(minPrice) < 0) {
                String distInfo = distKm != null
                        ? "Для " + String.format("%.1f", distKm) + " км минимум — *" + minPrice + "₽* (по тарифу).\n\n"
                        : "";
                sendMessage(chatId, "❌ " + distInfo + "Минимальная цена — *" + minPrice + "₽*\n\n" +
                        "💡 *Тарифная сетка:*\n" + deliveryPriceService.getTariffDescription());
                return;
            }

            data.getCurrentStop().setDeliveryPrice(price);
            
            // Спрашиваем комментарий к этой точке
            data.setState(OrderCreationState.WAITING_ADDITIONAL_STOP_COMMENT);
            sendMessage(chatId, "✅ Цена: *+" + price + "₽*\n\n" +
                    "Введите *комментарий* к этой точке\n" +
                    "или отправьте /skip чтобы пропустить:");
            
        } catch (NumberFormatException e) {
            sendMessage(chatId, "❌ Введите число:");
        }
    }



    /**
     * Обработка ручного ввода цены (когда юзер не нажал кнопку подтверждения).
     */
    private void handleManualPrice(Long chatId, Long telegramId, String text, OrderCreationData data) {
        try {
            BigDecimal price = new BigDecimal(text.replace(",", "."));
            BigDecimal minPrice = data.getDistanceKm() != null
                    ? deliveryPriceService.getMinPriceForDistance(data.getDistanceKm())
                    : deliveryPriceService.getMinPrice();

            if (price.compareTo(minPrice) < 0) {
                String distInfo = data.getDistanceKm() != null
                        ? "Для " + String.format("%.1f", data.getDistanceKm()) + " км минимум — *" + minPrice + "₽* (по тарифу).\n\n"
                        : "";
                sendMessage(chatId, "❌ " + distInfo + "Минимальная цена — *" + minPrice + "₽*\n\n" +
                        "💡 *Тарифная сетка:*\n" + deliveryPriceService.getTariffDescription() + "\n" +
                        "Введи цену от " + minPrice + "₽ или нажми кнопку выше:");
                return;
            }

            data.setDeliveryPrice(price);
            data.setState(OrderCreationState.WAITING_DELIVERY_DATE);
            sendMessageWithDateButtons(chatId, "✅ Цена: *" + price + "₽*\n\n" +
                    "Шаг 3 из 6\n" +
                    "Выберите дату доставки:", LocalTime.now().isAfter(LocalTime.of(21, 0)));

        } catch (NumberFormatException e) {
            sendMessage(chatId, "❌ Некорректное число. Введи цену цифрами или нажми кнопку выше:");
        }
    }

    /**
     * Обработка цены доставки (ручной ввод, когда геокодирование не удалось).
     * Валидация по тарифной сетке: минимум = тариф для расстояния (если есть), иначе 300₽.
     */
    private void handleDeliveryPrice(Long chatId, Long telegramId, String text, OrderCreationData data) {
        try {
            BigDecimal price = new BigDecimal(text.replace(",", "."));
            BigDecimal minPrice = data.getDistanceKm() != null
                    ? deliveryPriceService.getMinPriceForDistance(data.getDistanceKm())
                    : deliveryPriceService.getMinPrice();

            if (price.compareTo(minPrice) < 0) {
                String distInfo = data.getDistanceKm() != null
                        ? "Для " + String.format("%.1f", data.getDistanceKm()) + " км минимум — *" + minPrice + "₽* (по тарифу).\n\n"
                        : "";
                sendMessage(chatId, "❌ " + distInfo + "Минимальная цена — *" + minPrice + "₽*\n\n" +
                        "💡 *Тарифная сетка:*\n" + deliveryPriceService.getTariffDescription() + "\n" +
                        "Введи цену от " + minPrice + "₽:");
                return;
            }

            data.setDeliveryPrice(price);
            data.setState(OrderCreationState.WAITING_DELIVERY_DATE);
            boolean onlyTomorrow = LocalTime.now().isAfter(LocalTime.of(21, 0));
            sendMessageWithDateButtons(chatId, "✅ Цена: *" + price + "₽*\n\n" +
                    "Шаг 3 из 6\n" +
                    "Выберите дату доставки:", onlyTomorrow);

        } catch (NumberFormatException e) {
            sendMessage(chatId, "❌ Некорректное число. Введи цену цифрами (например: 350):");
        }
    }

    /**
     * Финальный шаг: Обработка комментария и создание заказа.
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

        try {
            Order order;
            
            if (data.isMultiStop()) {
                // МУЛЬТИАДРЕСНЫЙ ЗАКАЗ
                order = createMultiStopOrder(shop, data);
            } else {
                // ОБЫЧНЫЙ ЗАКАЗ (1 точка)
                order = createSingleStopOrder(shop, data);
            }

            // Очищаем данные
            dataMap.remove(telegramId);

            // Формируем сообщение
            String confirmationMessage = buildOrderConfirmation(data, order);
            sendMessage(chatId, confirmationMessage);

        } catch (Exception e) {
            log.error("Ошибка создания заказа: telegramId={}", telegramId, e);
            dataMap.remove(telegramId);
            sendMessage(chatId, "❌ Ошибка при создании заказа: " + e.getMessage());
        }
    }
    
    /**
     * Создать обычный заказ (1 точка).
     */
    private Order createSingleStopOrder(Shop shop, OrderCreationData data) {
        // Если есть точки в списке — берём первую
        if (!data.getStops().isEmpty()) {
            OrderCreationData.StopData stop = data.getStops().get(0);
            return orderService.createOrder(
                    shop,
                    stop.getRecipientName(),
                    stop.getRecipientPhone(),
                    stop.getDeliveryAddress(),
                    stop.getDeliveryPrice(),
                    data.getComment(),
                    data.getDeliveryDate(),
                    stop.getDeliveryLatitude(),
                    stop.getDeliveryLongitude()
            );
        }
        
        // Иначе из полей
        return orderService.createOrder(
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
    }
    
    /**
     * Создать мультиадресный заказ (несколько точек).
     */
    private Order createMultiStopOrder(Shop shop, OrderCreationData data) {
        return orderService.createMultiStopOrder(
                shop,
                data.getDeliveryDate(),
                data.getComment(),
                data.getStops()
        );
    }
    
    /**
     * Сформировать сообщение подтверждения заказа.
     */
    private String buildOrderConfirmation(OrderCreationData data, Order order) {
        String dateStr = data.getDeliveryDate().equals(LocalDate.now()) ? "сегодня" : "завтра";
        StringBuilder msg = new StringBuilder();
        
        if (data.isMultiStop()) {
            // Мультиадресный заказ
            msg.append("🎉 *Заказ создан!*\n\n");
            msg.append("📦 *Мультиадресная доставка*\n");
            msg.append("📅 Дата: ").append(dateStr).append(" (")
               .append(data.getDeliveryDate().format(DateTimeFormatter.ofPattern("dd.MM"))).append(")\n\n");
            
            for (int i = 0; i < data.getStops().size(); i++) {
                OrderCreationData.StopData stop = data.getStops().get(i);
                msg.append("📍 *Точка ").append(i + 1).append(":*\n");
                msg.append("• Получатель: ").append(stop.getRecipientName()).append("\n");
                msg.append("• Телефон: ").append(stop.getRecipientPhone()).append("\n");
                msg.append("• Адрес: ").append(stop.getDeliveryAddress()).append("\n");
                if (stop.getDistanceKm() != null) {
                    msg.append("• Расстояние: ").append(stop.getDistanceKm()).append(" км\n");
                }
                msg.append("• Цена: ").append(stop.getDeliveryPrice()).append("₽\n");
                if (stop.getComment() != null && !stop.getComment().isEmpty()) {
                    msg.append("• Комментарий: ").append(stop.getComment()).append("\n");
                }
                msg.append("\n");
            }
            
            msg.append("💰 *ИТОГО: ").append(data.getTotalPrice()).append("₽*\n\n");
            msg.append("⏳ Ожидайте, скоро курьер возьмёт заказ!");
            
        } else {
            // Обычный заказ (1 точка)
            msg.append("🎉 *Заказ создан!*\n\n");
            msg.append("📋 *Данные заказа:*\n");
            msg.append("• Дата: ").append(dateStr).append(" (")
               .append(data.getDeliveryDate().format(DateTimeFormatter.ofPattern("dd.MM"))).append(")\n");
            
            OrderCreationData.StopData stop = data.getStops().isEmpty() ? null : data.getStops().get(0);
            String recipientName = stop != null ? stop.getRecipientName() : data.getRecipientName();
            String recipientPhone = stop != null ? stop.getRecipientPhone() : data.getRecipientPhone();
            String address = stop != null ? stop.getDeliveryAddress() : data.getDeliveryAddress();
            Double distance = stop != null ? stop.getDistanceKm() : data.getDistanceKm();
            BigDecimal price = stop != null ? stop.getDeliveryPrice() : data.getDeliveryPrice();
            String comment = stop != null ? stop.getComment() : data.getComment();
            
            msg.append("• Получатель: ").append(recipientName).append("\n");
            msg.append("• Телефон: ").append(recipientPhone).append("\n");
            msg.append("• Адрес: ").append(address).append("\n");
            if (distance != null) {
                msg.append("• Расстояние: ").append(distance).append(" км\n");
            }
            msg.append("• Цена: ").append(price).append("₽\n");
            if (comment != null && !comment.isEmpty()) {
                msg.append("• Комментарий: ").append(comment).append("\n");
            }
            msg.append("\n⏳ Ожидайте, скоро курьер возьмёт заказ!");
        }
        
        return msg.toString();
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
            // ===== ОСНОВНАЯ ТОЧКА =====
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
            case WAITING_STOP_COMMENT:
                // Комментарий к первой точке
                handleStopComment(chatId, telegramId, text, data);
                break;
                
            // ===== ДОПОЛНИТЕЛЬНЫЕ ТОЧКИ =====
            case WAITING_ASK_ADDITIONAL_STOP:
                // Это состояние обрабатывается через callback, не текст
                sendMessage(chatId, "👆 Нажмите кнопку выше");
                break;
            case WAITING_ADDITIONAL_RECIPIENT_NAME:
                handleAdditionalRecipientName(chatId, telegramId, text, data);
                break;
            case WAITING_ADDITIONAL_RECIPIENT_PHONE:
                handleAdditionalRecipientPhone(chatId, telegramId, text, data);
                break;
            case WAITING_ADDITIONAL_ADDRESS:
                handleAdditionalAddress(chatId, telegramId, text, data);
                break;
            case WAITING_ADDITIONAL_PRICE_CONFIRMATION:
                // Юзер ввёл свою цену вместо подтверждения
                handleAdditionalManualPrice(chatId, telegramId, text, data);
                break;
            case WAITING_ADDITIONAL_PRICE:
                handleAdditionalPrice(chatId, telegramId, text, data);
                break;
            case WAITING_ADDITIONAL_STOP_COMMENT:
                // Комментарий к дополнительной точке
                handleAdditionalStopComment(chatId, telegramId, text, data);
                break;
                
            // ===== ЗАВЕРШЕНИЕ (для обратной совместимости) =====
            case WAITING_COMMENT:
                // Этот кейс теперь не используется, но оставляем для безопасности
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




