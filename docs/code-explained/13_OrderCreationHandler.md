# ТОТАЛЬНЫЙ РАЗБОР: OrderCreationHandler.java
## Как бот ведёт магазин за ручку по созданию (мульти)заказа

> **Уровень**: "я нажал 'Создать заказ', бот что‑то орёт про шаги 1 из 6 – ХОЧУ ЗНАТЬ КТО ЭТО ДЕЛАЕТ"  
> **Цель**: разобрать `OrderCreationHandler` так, чтобы ты понимал:
> - где хранятся промежуточные данные заказа,
> - как переключаются состояния (шаги),
> - как работает геокодинг и автоподбор цены,
> - как включается мультиадрес.

---

## 0. Общая картина

Когда магазин в боте нажимает `"📦 Создать заказ"`:

```text
Bot.onUpdateReceived (text "📦 Создать заказ")
    ↓
OrderCreationHandler.startOrderCreation(telegramId, chatId)
    ↓
Шаг 1: выбор даты (inline‑кнопки)
    ↓
Шаг 2: имя получателя (текст)
    ↓
Шаг 3: телефон получателя (текст)
    ↓
Шаг 4: адрес доставки (текст)
    ↓
  ├─если геокодинг/тарифы сработали → автоцена + подтверждение
  └─если нет → ручной ввод цены
    ↓
Шаг 5: комментарий к точке
    ↓
Шаг 6: мультиадрес – добавить ещё точку или завершить
    ↓
OrderService.createOrder / createMultiStopOrder → БД
```

Сам диалог живёт здесь (`OrderCreationHandler`),  
а "железная" логика создания/пересчёта заказа — в `OrderService` и `DeliveryPriceService`.

---

## 1. Поля и хранилище состояний

### Важный фрагмент в начале класса

```java
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
```

### Кто тут кто

- `OrderCreationHandler` — класс, который:
  - отвечает за **пошаговый диалог** создания заказа,
  - хранит промежуточные данные в `dataMap`,
  - в конце зовёт `OrderService` для сохранения.

- `ShopService`:
  - нужен, чтобы:
    - убедиться, что у телеги есть магазин,
    - проверить, активирован ли он,
    - взять адрес/координаты магазина для расчёта расстояния.

- `OrderService`:
  - создаёт заказы (`createOrder`, `createMultiStopOrder`),
  - потом ещё используется при мультистопах.

- `GeocodingService`:
  - превращает текстовый адрес в координаты + регион:

    ```java
    Optional<GeocodingResult> geocode(String address);
    boolean isInAllowedRegion(GeocodingResult geo);
    ```

- `DeliveryPriceService`:
  - по координатам считает:
    - расстояние,
    - рекомендуемую цену,
    - "описание тарифа".

- `Bot`:
  - через него мы реально отправляем сообщения (`bot.execute(...)`).
  - `@Lazy` — чтобы не было цикла зависимостей.

- `dataMap`:
  - `Map<Long, OrderCreationData>`:
    - ключ: `telegramId` магазина,
    - значение: объект `OrderCreationData` с:
      - текущим `OrderCreationState`,
      - `deliveryDate`,
      - `recipientName/Phone`,
      - `deliveryAddress`,
      - `deliveryLatitude/Longitude`,
      - `distanceKm`,
      - `suggestedPrice`,
      - `deliveryPrice`,
      - списком точек (`stops`) для мультиадреса.
  - `ConcurrentHashMap` — чтобы несколько магазинов могли одновременно создавать заказы (параллельные потоки).

Аналогия:

- `dataMap` — склад, где лежат **черновики заказов**, по одному черновику на каждый `telegramId`.

---

## 2. Старт: `startOrderCreation`

### Код

```java
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
```

### Разбор

1. **Проверяем, что есть магазин**:

   - `shopService.findByUserTelegramId(telegramId)`:
     - возвращает `Optional<Shop>`:
       - `present` — всё ок,
       - `empty` — магазина нет.
   - Если `empty`:
     - шлём сообщение, что магазина нет,
     - просим сначала пройти регистрацию магазина,
     - `return`.

2. **Проверяем, активен ли магазин**:

   - `shop.getIsActive()`:
     - `false` → магазин зарегистрирован, но ещё не активирован админом,
     - шлём "магазин не активирован",
     - `return`.

3. **Создаём черновик заказа**:

   ```java
   OrderCreationData data = new OrderCreationData();
   data.setState(OrderCreationState.WAITING_DELIVERY_DATE);
   dataMap.put(telegramId, data);
   ```

   - новый `OrderCreationData`:
     - пустой черновик,
     - без даты/адреса/получателя.
   - `setState(WAITING_DELIVERY_DATE)`:
     - помечаем "сейчас мы на шаге: ждём дату доставки".
   - кладём в `dataMap` под ключом `telegramId`.

4. **Ограничения по времени (после 21:00)**:

   ```java
   LocalTime now = LocalTime.now();
   LocalTime endOfDay = LocalTime.of(21, 0);
   ```

   - если время > 21:00:
     - сразу говорим, что дата только "завтра",
     - и зовём `sendMessageWithDateButtons(..., onlyTomorrow = true)`.
   - если раньше:
     - можно и "сегодня", и "завтра",
     - `sendMessageWithDateButtons(..., onlyTomorrow = false)`.

**Итог:**  
мы создали запись в `dataMap[telegramId]` со `state = WAITING_DELIVERY_DATE`  
и отправили пользователю первое сообщение с кнопками выбора даты.

---

## 3. Кнопки даты: `sendMessageWithDateButtons`

### Код

```java
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
```

### Смысл

- Создаём `SendMessage` с текстом "Шаг 1 из 6, выберите дату".
- Вешаем на него `InlineKeyboardMarkup`:
  - если `onlyTomorrow = true`:
    - одна кнопка: `"delivery_date_tomorrow"`,
  - иначе:
    - две кнопки: `"delivery_date_today"` и `"delivery_date_tomorrow"`.

Каждая кнопка:

- отображает красивый текст:
  - `"📅 Сегодня (10.02)"`,
  - `"📅 Завтра (11.02)"`,
- несёт в себе `callbackData`:
  - `"delivery_date_today"` или `"delivery_date_tomorrow"`.

Потом `CallbackQueryHandler` при нажатии этих кнопок вызывает:

```java
orderCreationHandler.handleDateSelection(telegramId, chatId, callbackData);
```

---

## 4. Обработка выбора даты: `handleDateSelection`

### Код

```java
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
```

### Разбор

1. Достаём черновик:

   ```java
   OrderCreationData data = dataMap.get(telegramId);
   ```

   - если его нет или `state != WAITING_DELIVERY_DATE`:
     - это вообще не наш callback → `return`.

2. Определяем дату:

   - если `"delivery_date_today"`:
     - `selectedDate = LocalDate.now()`,
     - `dateText = "сегодня"`.
   - иначе (второй вариант — `"delivery_date_tomorrow"`):
     - `LocalDate.now().plusDays(1)`,
     - `"завтра"`.

3. Сохраняем в `data`:

   ```java
   data.setDeliveryDate(selectedDate);
   data.setState(OrderCreationState.WAITING_RECIPIENT_NAME);
   ```

   - дата зафиксирована,
   - состояние переключили на "ждём имя получателя".

4. Шлём следующее сообщение:

   - говорим, какую дату выбрали,
   - просим ввести имя получателя.

---

## 5. Имя и телефон получателя: `handleRecipientName` / `handleRecipientPhone`

Оба метода приватные, вызываются из "главного" метода обработки текста,  
но чтобы мозг не плавился — разберём буквально по словам.

Представь, что где‑то выше в классе есть что‑то типа:

```java
public boolean handleText(Update update) {
    Message message = update.getMessage();
    Long telegramId = message.getFrom().getId();
    Long chatId = message.getChatId();
    String text = message.getText();

    OrderCreationData data = dataMap.get(telegramId);
    if (data == null) {
        return false;
    }

    switch (data.getState()) {
        case WAITING_RECIPIENT_NAME -> {
            handleRecipientName(telegramId, chatId, text);
            return true;
        }
        case WAITING_RECIPIENT_PHONE -> {
            handleRecipientPhone(telegramId, chatId, text);
            return true;
        }
        // ... другие стейты ...
        default -> {
            return false;
        }
    }
}
```

### 5.1. `handleRecipientName` — тащим имя получателя

```java
private void handleRecipientName(Long telegramId, Long chatId, String text) {
    OrderCreationData data = dataMap.get(telegramId);
    if (data == null || data.getState() != OrderCreationState.WAITING_RECIPIENT_NAME) {
        return;
    }

    String name = text.trim();
    if (name.isEmpty()) {
        sendMessage(chatId, "❌ Имя не может быть пустым.\n\n" +
                "Введите *имя получателя* ещё раз:");
        return;
    }

    data.setRecipientName(name);
    data.setState(OrderCreationState.WAITING_RECIPIENT_PHONE);

    sendMessage(chatId, "✅ Имя получателя: *" + name + "*\n\n" +
            "Шаг 3 из 6\n" +
            "Введите *телефон получателя* (в любом удобном формате):");
}
```

#### Разбор по кускам (с лёгким матом)

- `private void handleRecipientName(...`
  - `private` — метод **для внутреннего пользования**. Как чёрный ход в бар: клиентам туда нельзя.
  - `void` — метод **ничего не возвращает**. Он просто мутузит `dataMap` и шлёт сообщения.
  - `handleRecipientName` — название: "обработать имя получателя".

- `Long telegramId, Long chatId, String text`
  - `telegramId` — ID юзера в Telegram (уникальный, как ИНН, только в телеге).
  - `chatId` — с кем мы общаемся (в личке совпадает с `telegramId`, в группе — нет).
  - `text` — то, что человек реально вбил в чат (может быть как "Маша", так и "ыыы").

- `OrderCreationData data = dataMap.get(telegramId);`
  - лезем в наш склад `dataMap` и вытаскиваем черновик заказа **по ключу** = `telegramId`.
  - если до этого не было `startOrderCreation`, тут будет `null`, и мы честно скажем "я тут ни при чём".

- `if (data == null || data.getState() != OrderCreationState.WAITING_RECIPIENT_NAME) { return; }`
  - `data == null` — значит, никто не начинал заказ, а ты мне тут имя шлёшь. Игнор.
  - `data.getState() != WAITING_RECIPIENT_NAME` — мы сейчас не на шаге "имя".  
    Может, уже адрес ждём, а ты решил внезапно ещё раз имя вкинуть.  
    Логика: **не отвечает — значит, ты не в том контексте**.

- `String name = text.trim();`
  - `trim()` — как триммером бороду: срезает пробелы по краям.
  - Если человек написал `"   Вася   "`, мы запомним `"Вася"`, а не всю эту простыню.

- `if (name.isEmpty()) { ... }`
  - если после тримминга там пустота (`""`),  
    значит человек либо нажал "отправить" без текста, либо шутник.
  - мы его по‑доброму шлём **"имя не может быть пустым"** и не двигаем стейт.

- `data.setRecipientName(name);`
  - сохраняем имя в черновик.

- `data.setState(OrderCreationState.WAITING_RECIPIENT_PHONE);`
  - переключаем внутренний флажок: "теперь ждём телефон".
  - Это как у официанта: сначала спросил имя на бронь, поставил галочку, пошёл к следующему вопросу.

- `sendMessage(chatId, "✅ Имя получателя: *" + name + "* ...`
  - подтверждаем, что имя приняли,
  - подсвечиваем его жирным,
  - объявляем "Шаг 3 из 6" и просим телефон.

---

### 5.2. `handleRecipientPhone` — тащим телефон получателя

```java
private void handleRecipientPhone(Long telegramId, Long chatId, String text) {
    OrderCreationData data = dataMap.get(telegramId);
    if (data == null || data.getState() != OrderCreationState.WAITING_RECIPIENT_PHONE) {
        return;
    }

    String phone = text.trim();
    if (phone.isEmpty()) {
        sendMessage(chatId, "❌ Телефон не может быть пустым.\n\n" +
                "Введите *телефон получателя* ещё раз:");
        return;
    }

    data.setRecipientPhone(phone);
    data.setState(OrderCreationState.WAITING_DELIVERY_ADDRESS);

    sendMessage(chatId, "✅ Телефон получателя: *" + phone + "*\n\n" +
            "Шаг 4 из 6\n" +
            "Введите *адрес доставки* (улица, дом, подъезд, этаж, квартира):");
}
```

#### Что тут происходит

Почти тот же паттерн, просто уже телефон:

- Снова достаём `data` из `dataMap` по `telegramId`.
- Проверяем, что мы именно на шаге `WAITING_RECIPIENT_PHONE`.
- Стрижём пробелы `phone = text.trim()`.
- Если пусто — орём культурно "телефон не может быть пустым" и не двигаемся дальше.
- Сохраняем `recipientPhone` в `data`.
- Переключаем стейт на `WAITING_DELIVERY_ADDRESS`.
- Шлём следующее сообщение с запросом адреса.

Аналогия из жизни:

- Ты звонишь в доставку:
  - Сначала у тебя спрашивают "на какое имя заказ?",
  - потом "какой номер телефона?";  
  если ты молчишь — оператор повторяет вопрос, а не начинает угадывать адрес.

---

## 6. Адрес доставки: `handleDeliveryAddress`

Тут начинается самое мясо: геокодинг, проверка региона, расстояние, цена.

```java
private void handleDeliveryAddress(Long telegramId, Long chatId, String text) {
    OrderCreationData data = dataMap.get(telegramId);
    if (data == null || data.getState() != OrderCreationState.WAITING_DELIVERY_ADDRESS) {
        return;
    }

    String address = text.trim();
    if (address.isEmpty()) {
        sendMessage(chatId, "❌ Адрес не может быть пустым.\n\n" +
                "Введите *адрес доставки* ещё раз:");
        return;
    }

    data.setDeliveryAddress(address);

    var shopOptional = shopService.findByUserTelegramId(telegramId);
    if (shopOptional.isEmpty()) {
        sendMessage(chatId, "❌ Не удалось найти твой магазин.\n" +
                "Попробуй заново через /start");
        dataMap.remove(telegramId);
        return;
    }

    Shop shop = shopOptional.get();

    // Пробуем геокодировать адрес
    var geoOptional = geocodingService.geocode(address);
    if (geoOptional.isEmpty()) {
        // Геокодинг не смог найти адрес — переходим на ручной ввод цены
        data.setState(OrderCreationState.WAITING_MANUAL_PRICE);
        sendMessage(chatId, "⚠️ Не удалось автоматически определить координаты адреса.\n\n" +
                "Введи *стоимость доставки* вручную (в рублях):");
        return;
    }

    var geo = geoOptional.get();

    // Проверяем, что адрес в разрешённом регионе
    if (!geocodingService.isInAllowedRegion(geo)) {
        data.setState(OrderCreationState.WAITING_MANUAL_PRICE);
        sendMessage(chatId, "⚠️ Адрес находится вне зоны доставки.\n\n" +
                "Если всё равно хочешь оформить заказ, введи *стоимость доставки* вручную (в рублях):");
        return;
    }

    data.setDeliveryLatitude(geo.getLatitude());
    data.setDeliveryLongitude(geo.getLongitude());

    // Считаем расстояние и рекомендуемую цену
    var priceResult = deliveryPriceService.calculatePrice(
            shop.getLatitude(), shop.getLongitude(),
            geo.getLatitude(), geo.getLongitude()
    );

    data.setDistanceKm(priceResult.distanceKm());
    data.setSuggestedPrice(priceResult.suggestedPrice());
    data.setDeliveryPrice(priceResult.suggestedPrice());

    data.setState(OrderCreationState.WAITING_CONFIRM_PRICE_OR_EDIT);

    sendMessage(chatId,
            "📍 Адрес доставки:\n" +
                    "*" + address + "*\n\n" +
                    "Расстояние от магазина: *" + priceResult.distanceKm() + " км*\n" +
                    "Рекомендуемая цена доставки: *" + priceResult.suggestedPrice() + " ₽*\n\n" +
                    "Шаг 4 из 6\n" +
                    "Если цена ок — просто напиши *ОК*.\n" +
                    "Если хочешь другую — введи свою цену (в рублях):");
}
```

### Пошагово, что за дичь тут творится

1. **Проверка стейта**

   - Как и раньше, мы сначала убеждаемся, что:
     - есть `data` в `dataMap`,
     - стейт — `WAITING_DELIVERY_ADDRESS`.
   - Если нет — молча выходим.

2. **Чистим и проверяем адрес**

   - `address = text.trim();` → убираем пробелы.
   - Если `address.isEmpty()` — ругаемся и просим ввести ещё раз.

3. **Сохраняем адрес в черновик**

   ```java
   data.setDeliveryAddress(address);
   ```

4. **Снова достаём магазин**

   - Почему снова?  
     - потому что между шагами могло пройти время,
     - теоретически магазин могли удалить/деактивировать (или мы просто играем безопасно).
   - Если магазина не находим:
     - даём ошибку,
     - чистим `dataMap.remove(telegramId)` — выкидываем черновик,
     - просим начать всё заново.

5. **Геокодинг: превращаем текст в координаты**

   ```java
   var geoOptional = geocodingService.geocode(address);
   if (geoOptional.isEmpty()) {
       data.setState(OrderCreationState.WAITING_MANUAL_PRICE);
       sendMessage(... "Не удалось автоматически определить координаты...");
       return;
   }
   ```

   - `geocode(address)`:
     - под капотом идёт в DaData/другой сервис,
     - пробует понять, что за адрес,  
       и вернуть лат/лон + инфо о регионе.
   - Если сервис такой: "я хз что это за 'подвал у Ашота'":
     - мы не ломаемся,
     - просто переключаемся на план Б: **ручной ввод цены**.

6. **Проверка региона**

   ```java
   if (!geocodingService.isInAllowedRegion(geo)) {
       data.setState(OrderCreationState.WAITING_MANUAL_PRICE);
       sendMessage(chatId, "⚠️ Адрес находится вне зоны доставки...\n" + ...);
       return;
   }
   ```

   - тут мы как суровый таксист:
     - "я за МКАД не поеду, только если доплатишь".
   - Если адрес вне разрешённого региона:
     - всё равно даём шанс оформить заказ,
     - но уже без автоматики — пусть магазин сам ставит цену.

7. **Сохраняем координаты**

   ```java
   data.setDeliveryLatitude(geo.getLatitude());
   data.setDeliveryLongitude(geo.getLongitude());
   ```

   - теперь в черновике есть:
     - `адрес` (строка),
     - `lat/lon` (координаты),
     - дата, имя, телефон.

8. **Считаем расстояние и цену**

   ```java
   var priceResult = deliveryPriceService.calculatePrice(
           shop.getLatitude(), shop.getLongitude(),
           geo.getLatitude(), geo.getLongitude()
   );
   ```

   - `calculatePrice(...)`:
     - берёт координаты магазина и точки доставки,
     - через OSRM или похожую хрень считает **дорогу по улицам** (а не "по прямой через дома"),
     - на основании настроек тарифов:
       - базовая стоимость,
       - цена за км,
       - минималка и т.п.,
     - возвращает объект с:
       - `distanceKm()` — сколько км,
       - `suggestedPrice()` — рекомендуемая цена в рублях.

9. **Сохраняем в черновике расстояние/цену**

   ```java
   data.setDistanceKm(priceResult.distanceKm());
   data.setSuggestedPrice(priceResult.suggestedPrice());
   data.setDeliveryPrice(priceResult.suggestedPrice());
   ```

   - `suggestedPrice` — типа "наша математическая рекомендация".
   - `deliveryPrice` — текущая фактическая цена в черновике.
     - Сначала ставим её = рекомендуемой,
     - но потом магазин может сказать "нет, 500 ₽ мало, давай 700".

10. **Меняем стейт**

    ```java
    data.setState(OrderCreationState.WAITING_CONFIRM_PRICE_OR_EDIT);
    ```

    - переходим в состояние:
      - "ждём, что скажет магазин про цену:
        - либо `ОК`,
        - либо пришлёт свою сумму".

11. **Пишем магазину итог по шагу**

    - показываем:
      - адрес,
      - расстояние,
      - рекомендуемую цену,
    - объясняем, что можно:
      - написать `ОК`,
      - либо ввести свою цену.

---

## 7. Подтверждение/изменение цены: `handlePriceConfirmationOrEdit`

```java
private void handlePriceConfirmationOrEdit(Long telegramId, Long chatId, String text) {
    OrderCreationData data = dataMap.get(telegramId);
    if (data == null || data.getState() != OrderCreationState.WAITING_CONFIRM_PRICE_OR_EDIT) {
        return;
    }

    String normalized = text.trim().toLowerCase();
    if (normalized.equals("ок") || normalized.equals("ok") || normalized.equals("okay")) {
        // Оставляем рекомендованную цену
    } else {
        try {
            int manualPrice = Integer.parseInt(normalized.replace(" ", ""));
            if (manualPrice <= 0) {
                sendMessage(chatId, "❌ Цена должна быть больше нуля.\n\n" +
                        "Введи *стоимость доставки* в рублях ещё раз:");
                return;
            }
            data.setDeliveryPrice(manualPrice);
        } catch (NumberFormatException e) {
            sendMessage(chatId, "❌ Не смог понять цену.\n\n" +
                    "Напиши либо *ОК*, либо число (стоимость доставки в рублях):");
            return;
        }
    }

    data.setState(OrderCreationState.WAITING_COMMENT);

    sendMessage(chatId, "✅ Стоимость доставки: *" + data.getDeliveryPrice() + " ₽*\n\n" +
            "Шаг 5 из 6\n" +
            "Напиши *комментарий к доставке* (подъезд, домофон, как позвонить, особые пожелания)\n" +
            "Если комментария нет — напиши `-`");
}
```

### Логика как у торга на рынке

- Сначала снова проверяем, что:
  - у нас есть `data`,
  - стейт = `WAITING_CONFIRM_PRICE_OR_EDIT`.

- `normalized = text.trim().toLowerCase();`
  - убираем пробелы по краям,
  - приводим к нижнему регистру,
  - чтобы `ОК`, `ok`, `Ok` и `OK` воспринимались одинаково.

- Если юзер пишет `"ок" / "ok" / "okay"`:
  - мы ничего не меняем в цене (`deliveryPrice` уже стоит из `suggestedPrice`),
  - просто двигаемся дальше.

- Иначе пробуем разобрать это как число:

  ```java
  int manualPrice = Integer.parseInt(normalized.replace(" ", ""));
  ```

  - если человек написал `"1 200"`, мы убираем пробел и парсим `"1200"`.
  - если там `"сто рублей"` — ловим `NumberFormatException` и честно говорим:
    - "не понял цену, давай ещё раз".

- `manualPrice <= 0` — это либо шутник, либо "доставка бесплатная".  
  Мы так не работаем — просим ввести нормальную цену.

- Если всё ок:
  - `data.setDeliveryPrice(manualPrice);`
  - переходим в стейт `WAITING_COMMENT`.
  - просим комментарий к доставке (или `-`, если нет).

Аналогия:

- Это как если бы ты прислал курьеру смс:
  - "по расчёту 300 ₽, ок?",
  - он тебе:
    - либо "ок",
    - либо "давай 400, там лифта нет".

---

## 8. Комментарий к первой точке и старт мультиадреса

Тут у нас развилка: либо заказ с одной точкой, либо превращаемся в Яндекс.Еду и едем по всему району.

### 8.1. `handleStopComment` — докидываем коммент и сохраняем первую точку

```java
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
```

#### По шагам, с чёрнушкой

- `if (!text.equals("/skip")) { ... }`
  - Команда `/skip` — это как сказать "мне и так всё плохо, комментарии не нужны".
  - Если юзер не написал `/skip`, значит текст — нормальный коммент, типа:
    - "домофон 666, если не откроют — звони соседу пониже".

- `data.setComment(text);`
  - Временно кидаем этот текст в поле `comment` для всего заказа.
  - Почему "временно":
    - для мультистопа у каждой точки потом могут быть **свои** комментарии,
    - но нам всё равно удобно иметь общий текст "на всякий".

- `data.saveFirstStopFromFields();`
  - Очень важная магия:
    - внутри `OrderCreationData` этот метод берёт:
      - `recipientName`,
      - `recipientPhone`,
      - `deliveryAddress`,
      - `deliveryLatitude/Longitude`,
      - `deliveryPrice`,
      - `distanceKm`,
      - и прочую шелуху,
    - и собирает из этого первый `StopData`,
    - закидывает в список `stops`.
  - То есть до этого момента всё лежало "по полям" как каша в голове,
    а этой строчкой мы говорим: "так, оформляем первую официальную точку".

- `if (!text.equals("/skip") && !data.getStops().isEmpty()) { ... }`
  - Если мы **и комментарий писали**, и точка реально сохранилась:
    - достаём первый стоп `get(0)`,
    - прописываем ему `setComment(text)`.
  - Почему два раза:
    - `data.setComment(text)` — общий комментарий к заказу,
    - `stop.setComment(text)` — частный комментарий именно к этой точке.
    - Иногда полезно отличать "общий трёп" и "особые условия для конкретного адреса".

- `askAddAdditionalStop(chatId, data);`
  - После первой точки бот задаёт сакральный вопрос:
    - "хочешь ещё точек, или хватит выгуливать курьера по району?"

---

### 8.2. `askAddAdditionalStop` — предлагаем изнасиловать курьера ещё парой адресов

```java
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
```

#### Смысл

- `data.setState(WAITING_ASK_ADDITIONAL_STOP);`
  - фиксируем, что теперь мы ждём **нажатие кнопки**, а не текст.
  - если на этом шаге юзер начнёт писать что‑то руками — мы скажем "нажми кнопку".

- `currentStops = data.getStops().size();`
  - сколько точек уже навешали на бедного курьера.

- `totalPrice = data.getTotalPrice();`
  - общая сумма по всем стопам (у `OrderCreationData` свой метод).

- Кнопки:
  - `"➕ Добавить адрес"` → `callbackData = "add_stop_yes"`,
  - `"✅ Завершить"` → `callbackData = "add_stop_no"`.

**Итого:**  
бот говорит: "первая точка добавлена, сумма такая‑то,  
хочешь устроить курьеру тур по аду — жми плюс,  
надоело — жми завершить".

---

### 8.3. `handleAddStopDecision` — юзер решает, мучить ли курьера дальше

```java
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
```

#### Детали

- `boolean addMore`:
  - `true` → юзер нажал `"➕ Добавить адрес"`,
  - `false` → `"✅ Завершить"`.

- `data.startNewStop();`
  - внутри `OrderCreationData` создаётся свежий `currentStop`,
  - список `stops` пока не пополняем — сначала надо имя/телефон/адрес.

- `data.setState(WAITING_ADDITIONAL_RECIPIENT_NAME);`
  - включаем режим "новая точка, шаг 1: имя".

- `stopNumber = data.getStops().size() + 1;`
  - уже добавленные точки сидят в `stops`,
  - новая будет `+1` к их количеству.

- `finalizeOrder(...)`:
  - если addMore = false:
    - не мудрим, сразу идём в финализацию и создание `Order` в БД.

---

## 9. Дополнительные точки: ад, боль и ещё больше логики

Теперь разберём, как бот обрабатывает **каждую доп. точку**:

- имя → телефон → адрес → геокодинг → цена → коммент → снова вопрос "ещё точка?".

### 9.1. Имя и телефон доп. точки

```java
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
```

```java
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
```

Тут всё как у первой точки, только:

- работаем не с полями `data.*`, а с `data.getCurrentStop()`,
- стейты свои: `WAITING_ADDITIONAL_*`.

Аналогия:  
первая жертва (точка) уже привязана, теперь набираешь следующую — имя, телефон, адрес.

---

### 9.2. Адрес доп. точки, геокодинг и отдельный тариф

```java
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
```

Ключевые моменты:

- Считаем расстояние **между предыдущей точкой и новой**, а не от магазина:
  - потому что курьер едет: магазин → точка 1 → точка 2 → ...
  - и за каждый кусок пути есть своя доплата.

- `getLastStopCoordinates()`:
  - достаёт координаты последней уже сохранённой точки,
  - если их нет — идём в ручной ввод.

- `calculateAdditionalStop(...)`:
  - отдельный тариф для доп. остановки:
    - может быть дешевле/дороже, чем первый участок.

---

### 9.3. Подтверждение/ручная цена для доп. точки и её комментарий

```java
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
```

```java
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
```

Паттерн ровно как у первой точки:

- либо юзер жмёт кнопку подтверждения цены (`handleAdditionalPriceConfirmation`),
- либо вводит свою (`handleAdditionalManualPrice` / `handleAdditionalPrice` — ручной ввод),
- потом — комментарий или `/skip`,
- потом `saveCurrentStop()` кидает точку в общий список,
- снова спрашиваем: "хочешь ещё? 🤡".

---

## 10. Финал: создание заказа и сборка сообщения‑подтверждения

Теперь самое приятное: всё это безумие превращаем в один чёткий `Order` в БД  
и человеческое сообщение, которое можно показать магазину.

### 10.1. `handleComment` — старый путь "одна точка + общий коммент"

```java
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
```

Здесь всё по классике:

- если `/skip` — комментарий не сохраняем,
- находим `Shop` по `telegramId`,
- в зависимости от `data.isMultiStop()`:
  - один заказ с одной точкой,
  - или мультиадрес,
- чистим `dataMap`, чтобы не было зомби‑черновиков,
- шлём красивое подтверждение.

Обрати внимание: логика финализации ещё раз повторяется в `finalizeOrder(...)` — это нужно для нового мультиадресного флоу через `askAddAdditionalStop`, но суть одна и та же.

---

### 10.2. `createSingleStopOrder` — когда магазин не решил превращать курьера в Яндекс.Маршрутку

```java
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
```

Зачем тут два варианта:

- **Новый путь**:
  - когда мы уже используем стопы (`saveFirstStopFromFields`),
  - все данные первой точки лежат в `stops[0]`,
  - и мы читаем всё оттуда.

- **Старый путь / обратная совместимость**:
  - если по какой‑то причине списка стопов нет,
  - читаем напрямую из полей `data.*`.

И в обоих случаях в `OrderService.createOrder(...)` улетает уже чистый набор аргументов:

- `shop`, `имя`, `телефон`, `адрес`, `цена`, `коммент`, `дата`, `lat`, `lon`.

---

### 10.3. `createMultiStopOrder` — режим "развозим по району всех ваших бывших"

```java
private Order createMultiStopOrder(Shop shop, OrderCreationData data) {
    return orderService.createMultiStopOrder(
            shop,
            data.getDeliveryDate(),
            data.getComment(),
            data.getStops()
    );
}
```

Тут всё просто и жестоко:

- в `OrderService` улетает:
  - магазин,
  - дата,
  - общий комментарий,
  - список `StopData` (каждая точка со своим адресом/ценой/комментом).

Дальше `OrderService` уже:

- создаёт `Order`,
- создаёт `OrderStop` для каждой точки,
- считает общую сумму,
- сохраняет всё в БД.

---

### 10.4. `buildOrderConfirmation` — человеческий итог для магазина

```java
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
```

Это финальное "человеческое резюме" для магазина:

- показывает:
  - дату (словом + числом),
  - все точки, телефоны, адреса, цены, расстояния, комментарии,
  - общую сумму.
- по сути, это то, что владелец магазина может:
  - показать курьеру,
  - переслать клиенту,
  - использовать как "чек".

---

## 11. Большая схема всего `OrderCreationHandler`

```text
Нажали "📦 Создать заказ"
    ↓
startOrderCreation
    ↓
WAITING_DELIVERY_DATE  ─(callback)─► handleDateSelection
    ↓
WAITING_RECIPIENT_NAME ─(text)────► handleRecipientName
    ↓
WAITING_RECIPIENT_PHONE ─(text)───► handleRecipientPhone
    ↓
WAITING_DELIVERY_ADDRESS ─(text)──► handleDeliveryAddress
    ↓
  ├─ геокодинг/тарифы ок:
  │     WAITING_PRICE_CONFIRMATION ─(кнопка)────────► handlePriceConfirmation
  │                                  └─(текст-цена)► handleManualPrice
  │
  └─ геокодинг в дауне / вне зоны:
        WAITING_DELIVERY_PRICE ─(текст-цена)──► handleDeliveryPrice
    ↓
WAITING_STOP_COMMENT ─(текст или /skip)──► handleStopComment
    ↓
WAITING_ASK_ADDITIONAL_STOP ─(кнопка)───► handleAddStopDecision
          ↓
        addMore = true:
            циклы по:
              WAITING_ADDITIONAL_RECIPIENT_NAME  → handleAdditionalRecipientName
              WAITING_ADDITIONAL_RECIPIENT_PHONE → handleAdditionalRecipientPhone
              WAITING_ADDITIONAL_ADDRESS         → handleAdditionalAddress
              WAITING_ADDITIONAL_PRICE_CONFIRMATION/PRICE → handleAdditional...Price...
              WAITING_ADDITIONAL_STOP_COMMENT    → handleAdditionalStopComment
            и снова WAITING_ASK_ADDITIONAL_STOP
          ↓
        addMore = false:
            finalizeOrder / handleComment → createSingleStopOrder / createMultiStopOrder
            → buildOrderConfirmation → sendMessage
```

Если совсем по‑человечески:

- `OrderCreationHandler` — это сценарий квестовой комнаты "создай заказ и не сдохни",
- `OrderCreationData` — блокнот ведущего, где он записывает каждую твою дурную идею,
- `OrderService` — бухгалтер, который по итогам всего этого трэша создаёт нормальный заказ в БД,
- `DeliveryPriceService` — калькулятор, который считает, сколько стоит выгулять курьера,
- `GeocodingService` — GPS‑модуль, который иногда работает, а иногда "я не знаю, где это, но звучит как подвал".

---

## 12. Что дальше?

Теперь у тебя есть:

- полная картина, как бот:
  - ведёт магазин по шагам,
  - собирает все поля,
  - организует мультиадрес,
  - создаёт `Order` и `OrderStop`,
  - и что делает каждый state `OrderCreationState`.

Дальше логичные варианты:

- **1)** Пойти в `OrderCreationData` и разжевать этот класс (все поля, методы `saveFirstStopFromFields`, `startNewStop`, `getLastStopCoordinates`, `getTotalPrice` и т.д.).  
- **2)** Перейти к следующему куску функционала по ТЗ (меню курьера, список доступных заказов и т.п.) и начинать уже реализовывать сами фичи для курьеров.

Выбирай: **разбор внутренностей `OrderCreationData`** или уже **боевые фичи курьеров**?  
