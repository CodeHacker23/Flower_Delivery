# ТОТАЛЬНЫЙ РАЗБОР: CallbackQueryHandler.java
## Что происходит, когда ты жмёшь КНОПКУ под сообщением

> **Уровень**: хочу понять, что за чёрт дергается в коде,  
> когда я тыкаю "Магазин", "Курьер", "✏️ Редактировать", "❌ Отменить".

Задача этого файла — чтобы ты мог:

- открыть его в любое состояние (трезвый/в ноль/накуренный),
- посмотреть на любую строчку вида  
  `callbackQuery.getMessage().getChatId()`  
  и понимать: **кто это**, **что это**, **откуда оно**, **нахрена**.

---

## 0. Что вообще такое CallbackQuery

Ты уже знаешь два типа кнопок:

- **Reply‑клавиатура** — снизу экрана, как обычная клавиатура:

  ```
  [ 📦 Создать заказ ]  [ 📋 Мои заказы ]
  [ 🏪 Мой магазин     ]
  ```

  При нажатии приходит **обычное сообщение** с текстом `"📦 Создать заказ"`.

- **Inline‑кнопки** — ПОД сообщением:

  ```
  Сообщение
  [ Магазин ] [ Курьер ]
  ```

  При нажатии Telegram НЕ присылает новый текст.  
  Он присылает **CallbackQuery** внутри `Update`.

Схема:

```text
Пользователь жмёт inline‑кнопку
    ↓
Telegram → шлёт Update с полем callback_query
    ↓
Bot.onUpdateReceived(update)
    ↓
CallbackQueryHandler.handle(update)
```

Именно **CallbackQueryHandler** решает:

- какая кнопка была нажата,
- что это значит (выбор роли, создание заказа, отмена, редакт),
- какой хендлер/сервис надо дёрнуть.

---

## 1. Объявление класса и поля (без импорта мозга)

### Код (верх файла, без импортов)

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class CallbackQueryHandler {

    private final UserService userService;

    @Autowired
    @Lazy
    private Bot bot;

    @Autowired
    @Lazy
    private ShopRegistrationHandler shopRegistrationHandler;

    @Autowired
    @Lazy
    private OrderCreationHandler orderCreationHandler;

    private final ShopService shopService;

    private final org.example.flower_delivery.service.OrderService orderService;

    @Autowired
    @Lazy
    private OrderEditHandler orderEditHandler;

    @Autowired
    @Lazy
    private MyOrdersSelectionHandler myOrdersSelectionHandler;

    @Autowired
    @Lazy
    private CourierRegistrationHandler courierRegistrationHandler;
```

### Что это всё за звери

- `CallbackQueryHandler` — класс, который:
  - принимает на себя ВСЕ нажатия inline‑кнопок,
  - по `callbackData` решает, что делать.

- `UserService` — умеет менять роль юзера (`SHOP`, `COURIER`, `ADMIN`).
- `Bot` — твой главный бот:
  - через него вызываем `bot.execute(...)` для отправки сообщений/ответов.
  - `@Lazy` + `@Autowired` → чтобы не схлопнуться в рекурсивный ад (`Bot` ссылается на хендлеры, хендлеры на `Bot`).

- `ShopRegistrationHandler` — сценарий регистрации магазина.
- `OrderCreationHandler` — сценарий создания заказа (даты, адреса, мультиадрес).
- `ShopService` — логика по магазинам (поиск, инфо).
- `OrderService` — логика по заказам (отмена и т.д.).
- `OrderEditHandler` — редактирование заказа.
- `MyOrdersSelectionHandler` — выбор одного заказа из списка "Мои заказы".
- `CourierRegistrationHandler` — регистрация курьера (ФИО, телефон, селфи).

То есть `CallbackQueryHandler` — это **центральный роутер**, а выше перечисленные — пассажиры,  
которых он вызывает, когда их маршрут.

---

## 2. Главный метод: `handle(Update update)`

### Код

```java
public void handle(Update update) {
    // Проверяем, что в Update есть CallbackQuery
    if (!update.hasCallbackQuery()) {
        log.warn("Update не содержит CallbackQuery: {}", update);
        return;
    }

    CallbackQuery callbackQuery = update.getCallbackQuery();
    String callbackData = callbackQuery.getData();  // Данные кнопки (например "role_shop")
    Long telegramId = callbackQuery.getFrom().getId();  // ID пользователя, который нажал
    Long chatId = callbackQuery.getMessage().getChatId();  // ID чата

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
```

### 2.1. `update.hasCallbackQuery()` и `getCallbackQuery()`

```java
if (!update.hasCallbackQuery()) {
    log.warn("Update не содержит CallbackQuery: {}", update);
    return;
}

CallbackQuery callbackQuery = update.getCallbackQuery();
```

- **`update`** — объект класса `Update`, прилетевший в `Bot.onUpdateReceived`, потом сюда.
  - Внутри может быть:
    - `message` (обычное сообщение),
    - `callback_query` (то, о чём мы сейчас),
    - всякая дрянь типа `edited_message` и т.д.

- **`hasCallbackQuery()`**:
  - возвращает `true`, если в этом `Update` есть `callback_query`.
  - если `false` — этот хендлер вообще вызван не по адресу, выходим.

- **`getCallbackQuery()`**:
  - достаёт объект `CallbackQuery` из `Update`.

Если не проверить `hasCallbackQuery` и сразу лезть в `getCallbackQuery()` на апдейте без callback’а — поймаешь `null`, потом `NullPointerException`.

### 2.2. Что за `callbackQuery.getData()`, `getFrom().getId()`, `getMessage().getChatId()`

```java
String callbackData = callbackQuery.getData();
Long telegramId = callbackQuery.getFrom().getId();
Long chatId = callbackQuery.getMessage().getChatId();
```

Разбор по цепочке:

- `callbackQuery` — объект, который описывает **нажатие кнопки**.

1. **`getData()`**
   - возвращает строку `callback_data`, которую ты зашил в кнопку при её создании.
   - примеры:
     - `"role_shop"`,
     - `"role_courier"`,
     - `"create_order"`,
     - `"order_cancel_123e4567-e89b-12d3-a456-426614174000"`.

2. **`getFrom().getId()`**
   - `getFrom()` — кто нажал кнопку (Telegram‑юзер).
   - `.getId()` — его Telegram‑ID (число типа `642867793`).
   - мы используем это как ключ в БД (`users.telegram_id`).

3. **`getMessage().getChatId()`**
   - `getMessage()` — то самое сообщение, ПОД которым была кнопка.
   - `.getChatId()` — ID чата, где это сообщение висит.
   - в этот чат мы будем слать ответы (`SendMessage`).

Аналогия:

- `telegramId` — "кто нажал кнопку".
- `chatId` — "в какой чат нужно отвечать".
- `callbackData` — "какую именно кнопку нажали".

---

## 3. Почему везде `answerCallbackQuery(...)` и что это такое

Ты видишь, что почти при каждом `if (callbackData ...)` первым делом идёт:

```java
answerCallbackQuery(callbackQuery.getId(), "какой‑то текст");
```

Смотрим на реализацию:

```java
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
```

Разбор:

- **`AnswerCallbackQuery`** — специальный тип запроса для Telegram:
  - это **не** сообщение в чат,
  - это ответ на событие нажатия кнопки.

- Зачем:
  - если его НЕ послать, у юзера на кнопке будет крутиться бесконечный "часик/загрузка",
  - Telegram ждёт от бота подтверждения: "я получил нажатие".

- Параметры:
  - `callbackQueryId` — ID конкретного нажатия (берём из `callbackQuery.getId()`),
  - `text` — маленький тостер‑текст, который вылезет у юзера (снизу экрана или в алерте),
  - `showAlert(false)`:
    - `false` — маленькая серенькая полоска уведомления,
    - `true` — всплывающее модальное окошко (мы не юзаем, чтобы не бесить).

**Итог:**  
каждый раз, когда кнопка нажата, мы:

1. подтверждаем нажатие (`answerCallbackQuery(...)`),
2. **отдельно** шлём сообщения в чат (`SendMessage`), если нужно.

---

## 4. Выбор роли: `role_shop` / `role_courier`

### Ветка в `handle`

```java
if (callbackData.startsWith("role_")) {
    answerCallbackQuery(callbackQuery.getId(), "✅ Роль выбрана!");
    handleRoleSelection(callbackData, telegramId, chatId);
}
```

### Код `handleRoleSelection`

```java
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
```

### По словам

- `Role` — твой enum с ролями пользователя (`COURIER`, `SHOP`, `ADMIN`).
- `selectedRole` — переменная, в которую мы положим выбранную роль.

```java
if (callbackData.equals("role_shop")) {
    selectedRole = Role.SHOP;
} else if (callbackData.equals("role_courier")) {
    selectedRole = Role.COURIER;
}
```

- `callbackData` = то, что ты зашивал в кнопки в `StartCommandHandler.createRoleSelectionKeyboard()`:
  - `"role_shop"` для кнопки "Магазин",
  - `"role_courier"` для "Курьер".

- `userService.updateUserRole(telegramId, selectedRole)`:
  - лезет в таблицу `users`,
  - находит запись по `telegramId`,
  - ставит поле `role = selectedRole`.

Дальше:

- Если `Role.SHOP`:
  - через `sendMessage` говорим "выбрал магазин",
  - зовём `shopRegistrationHandler.startRegistrationFromCallback(...)`:
    - диалог регистрации магазина.
- Если `Role.COURIER`:
  - аналогично, но через `courierRegistrationHandler`.

`sendMessage(chatId, ...)` — твой вспомогательный метод ниже:  
оборачивает текст в `SendMessage` и шлёт через `bot.execute(...)`.

---

## 5. "Создать заказ" и "Мой магазин"

### `"create_order"`

```java
} else if (callbackData.equals("create_order")) {
    // Магазин хочет создать заказ
    answerCallbackQuery(callbackQuery.getId(), "📦 Создаём заказ...");
    orderCreationHandler.startOrderCreation(telegramId, chatId);
}
```

- `callbackData = "create_order"`:
  - эта строка была зашита в какую‑то inline‑кнопку (например, "📦 Создать заказ").
- `orderCreationHandler.startOrderCreation(telegramId, chatId)`:
  - запускает сценарий:
    - спросить дату,
    - спросить получателя,
    - адреса,
    - и т.д.

### `"shop_info"`

```java
} else if (callbackData.equals("shop_info")) {
    // Магазин хочет посмотреть информацию о себе
    answerCallbackQuery(callbackQuery.getId(), "🏪 Информация о магазине");
    handleShopInfo(telegramId, chatId);
}
```

`handleShopInfo`:

```java
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
```

- ищем магазин по `telegramId`,
- если нет → пишем, что нет,
- если есть → шлём карточку магазина.

---

## 6. Мультиадрес: `add_stop_yes`, `add_stop_no`, `confirm_additional_price_*`

```java
} else if (callbackData.equals("add_stop_yes")) {
    answerCallbackQuery(callbackQuery.getId(), "➕ Добавляем адрес...");
    orderCreationHandler.handleAddStopDecision(telegramId, chatId, true);
} else if (callbackData.equals("add_stop_no")) {
    answerCallbackQuery(callbackQuery.getId(), "✅ Завершаем...");
    orderCreationHandler.handleAddStopDecision(telegramId, chatId, false);
} else if (callbackData.startsWith("confirm_additional_price_")) {
    String priceStr = callbackData.replace("confirm_additional_price_", "");
    answerCallbackQuery(callbackQuery.getId(), "✅ Цена подтверждена");
    orderCreationHandler.handleAdditionalPriceConfirmation(telegramId, chatId, new java.math.BigDecimal(priceStr));
```

- `"add_stop_yes"` / `"add_stop_no"`:
  - ты нажал "добавить ещё адрес?" → да/нет.
  - дальше логика мультиадресов скрыта в `OrderCreationHandler`.
- `"confirm_additional_price_XXX"`:
  - из `callbackData` вырезаем `XXX` (`priceStr`),
  - создаём `BigDecimal` цены,
  - дёргаем `handleAdditionalPriceConfirmation`.

Здесь `CallbackQueryHandler` только маршрутизирует, вся бизнес‑логика по тарифам/расстояниям не здесь.

---

## 7. Отмена заказа: `order_cancel_*`

### Ветки

```java
} else if (callbackData.startsWith("order_cancel_ok_")) {
    String orderIdStr = callbackData.replace("order_cancel_ok_", "");
    answerCallbackQuery(callbackQuery.getId(), "Отменяю заказ...");
    handleOrderCancelConfirm(chatId, orderIdStr);

} else if (callbackData.equals("order_cancel_no")) {
    answerCallbackQuery(callbackQuery.getId(), "Ок, заказ не отменён");
    sendMessage(chatId, "✅ Заказ остаётся в силе.");

} else if (callbackData.startsWith("order_cancel_")) {
    String orderIdStr = callbackData.replace("order_cancel_", "");
    answerCallbackQuery(callbackQuery.getId(), "Отменить заказ?");
    handleOrderCancelAsk(chatId, orderIdStr);
}
```

### `handleOrderCancelAsk` — спросить "ты уверен?"

```java
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
```

- шлём новое сообщение "точно отменить?",
- под ним две inline‑кнопки:
  - `"order_cancel_ok_<id>"`,
  - `"order_cancel_no"`.

### `handleOrderCancelConfirm` — реально отменить

```java
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
```

- парсим `orderIdStr` в `UUID`,
- зовём `orderService.cancelOrder(orderId)`,
- выводим результат в чат.

---

## 8. Редактирование заказа: `order_edit_*` и `dispatchOrderEdit`

```java
} else if (callbackData.startsWith("order_edit_")) {
    answerCallbackQuery(callbackQuery.getId(), "✏️ Редактирование");
    dispatchOrderEdit(telegramId, chatId, callbackData);
}
```

`dispatchOrderEdit`:

```java
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
```

Смысл:

- по содержимому `callbackData` мы понимаем, **что именно хочет отредактировать**:
  - дату,
  - поле (адрес/телефон/коммент),
  - точку маршрута,
  - или просто открыть меню редактирования.
- Дальше всё отдаётся в `OrderEditHandler`, который уже знает бизнес‑логику.

---

## 9. Выбор заказа из "Моих заказов"

```java
} else if (callbackData.equals("orders_select")) {
    // Начать выбор заказа по номеру / ID из последнего списка
    answerCallbackQuery(callbackQuery.getId(), "🔎 Выбор заказа");
    myOrdersSelectionHandler.startSelection(telegramId, chatId);
}
```

- `"orders_select"` — callbackData, пришедшее с кнопки "🔎 Выбрать заказ".
- `myOrdersSelectionHandler.startSelection(...)`:
  - шлёт текст "введи номер или ID",
  - дальше ловит следующее текстовое сообщение и связывает с конкретным заказом.

---

## 10. Вспомогательный `sendMessage`

```java
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
```

- короткая обёртка над "отправить текст в чат":
  - `chatId` → строка,
  - текст,
  - Markdown включён.

---

## 11. Итоговая карта `CallbackQueryHandler`

```text
Update с callback_query
    ↓
CallbackQueryHandler.handle(update)
    ↓
callbackData:

  "role_shop" / "role_courier"
      → handleRoleSelection(...)
      → userService.updateUserRole(...)
      → shopRegistrationHandler / courierRegistrationHandler

  "create_order"
      → orderCreationHandler.startOrderCreation(...)

  "shop_info"
      → handleShopInfo(...)

  "delivery_date_*"
      → orderCreationHandler.handleDateSelection(...)

  "confirm_price_*"
      → orderCreationHandler.handlePriceConfirmation(...)

  "add_stop_yes/no"
      → orderCreationHandler.handleAddStopDecision(...)

  "confirm_additional_price_*"
      → orderCreationHandler.handleAdditionalPriceConfirmation(...)

  "order_cancel_*"
      → handleOrderCancelAsk / handleOrderCancelConfirm

  "order_edit_*"
      → dispatchOrderEdit(...) → OrderEditHandler

  "orders_select"
      → myOrdersSelectionHandler.startSelection(...)
```

По сути:

- **ВСЕ inline‑кнопки** в проекте **обрабатываются здесь**,
- сам же `CallbackQueryHandler` почти не делает бизнес‑логики,
- он только:
  - вытаскивает `telegramId`, `chatId`, `callbackData`,
  - решает, куда это дальше отдать,
  - подтверждает нажатие через `AnswerCallbackQuery`.

Если захочешь — можем дальше в таком же стиле разъебать любую конкретную строку из этого файла  
(например `callbackData.replace("order_cancel_", "")` или `new java.math.BigDecimal(priceStr)`),  
и потом такой же стиль размножать по остальным гайд‑файлам.  
