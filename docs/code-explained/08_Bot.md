# ТОТАЛЬНЫЙ РАЗБОР: Bot.java
## Главный мозг бота: как всё крутится вокруг onUpdateReceived

> **Уровень**: "Хочу открыть один файл и понять, как вообще живёт весь бот"  
> **Цель**: Разобрать `Bot.java` так, чтобы ты видел:
> - откуда прилетают все `Update`,
> - как они разлетаются по хендлерам,
> - где используются `chatId`, `telegramId`, `message`, `callbackQuery`, `contact`, `photo`  
> **Стиль**: объясняю так, чтобы ты, проснувшись через год с бодуна, глянул сюда и быстро вспомнил, как всё устроено

---

## 0. Где `Bot` в архитектуре

Сверху вниз:

```text
Telegram  →  TelegramBots (библиотека)  →  Bot.onUpdateReceived(Update)
                                        ↓
                       StartCommandHandler / CallbackQueryHandler /
                       ShopRegistrationHandler / CourierRegistrationHandler /
                       OrderCreationHandler / MyOrdersSelectionHandler / OrderEditHandler ...
```

`Bot`:

- регистрируется в Telegram (через токен и username),
- получает **каждый** `Update`,
- решает:
  - это нажатие кнопки? → `CallbackQueryHandler`,
  - это текст? → раздать по хендлерам в правильном порядке,
  - это контакт? → отдать регистрациям,
  - это фото? → отдать курьерской регистрации,
- содержит общие вспомогательные методы:
  - `sendShopMenu` — рисует меню магазина (Reply‑клавиатура),
  - `sendSimpleMessage` — простая отправка текста.

---

## 1. Шапка файла и поля

```java
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
```

Главное:

- `TelegramLongPollingBot` — базовый класс из библиотеки:
  - он сам ходит на Telegram‑сервер за апдейтами,
  - когда есть новый `Update` → вызывает твой `onUpdateReceived`.

Аннотации класса:

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class Bot extends TelegramLongPollingBot {
```

- `@Component` — бин `bot` для Spring (тебе не нужно руками создавать `new Bot(...)`).
- `@RequiredArgsConstructor` — конструктор с `final` полями (хендлеры, сервисы).
- `@Slf4j` — логгер `log`.

Поля:

```java
@Value("${telegram.bot.token}")
private String botToken;

@Value("${telegram.bot.username}")
private String botUsername;

private final StartCommandHandler startCommandHandler;
private final CallbackQueryHandler callbackQueryHandler;
private final ShopRegistrationHandler shopRegistrationHandler;
private final OrderCreationHandler orderCreationHandler;
private final MyOrdersSelectionHandler myOrdersSelectionHandler;
private final org.example.flower_delivery.handler.OrderEditHandler orderEditHandler;
private final ShopService shopService;
private final OrderService orderService;
private final org.example.flower_delivery.service.CourierService courierService;
```

- `botToken`, `botUsername`:
  - `@Value` вытягивает их из `application.properties` (`telegram.bot.token`, `telegram.bot.username`),
  - так ты не хардкодишь токен в коде.
- остальные — ссылки на хендлеры и сервисы, которые будут вызываться внутри `onUpdateReceived`.

---

## 2. Ключевой метод: `onUpdateReceived(Update update)`

```java
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

        // Если юзер выбирает заказ из списка "Мои заказы"
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
        // Кнопка меню: Мои заказы
        else if (text.equals("📋 Мои заказы")) {
            handleMyOrdersButton(update);
        }
        // Здесь позже добавим обработку других команд (/help, /orders и т.д.)
    }
}
```

Это **центральный роутер** для ВСЕХ апдейтов, кроме callback‑кнопок (которые сразу уходят в `CallbackQueryHandler`).

Разбираем по слоям.

---

### 2.1. Сначала — inline‑кнопки (`CallbackQuery`)

```java
if (update.hasCallbackQuery()) {
    callbackQueryHandler.handle(update);
    return;
}
```

- `hasCallbackQuery()`:
  - `true`, если это нажатие inline‑кнопки.
- Если да:
  - дальше НИКОГО не трогаем,
  - просто отдаём всё в `CallbackQueryHandler.handle(update)`,
  - `return` — чтобы не обрабатывать этот `Update` как обычное сообщение.

Ты уже разобрал `CallbackQueryHandler` в `07_...`, здесь главное — понять порядок:

- **inline‑кнопки обрабатываются ПЕРВЫМИ**, до любых сообщений/контактов.

---

### 2.2. Контакт: "Поделиться номером телефона"

```java
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
```

- `hasMessage()` — в апдейте есть объект `Message`.
- `getMessage().hasContact()` — в `Message` прилетел `Contact`:
  - это результат нажатия Reply‑кнопки `"📱 Поделиться номером телефона"`.

Дальше логика:

- Сначала даём шанс **регистрации магазина**:
  - если юзер сейчас на шаге "дай телефон для магазина",  
    `ShopRegistrationHandler.handleContact(update)` вернёт `true`, и мы выходим.
- Если не магазин:
  - кидаем `update` в `CourierRegistrationHandler.handleContact(update)`:
    - регистрация курьера тоже использует контакт.

То есть по сути:

- один и тот же тип апдейта (`contact`)
- может относиться:
  - либо к регистрации магазина,
  - либо к регистрации курьера,
  - порядок важен.

---

### 2.3. Фото: селфи с паспортом для курьера

```java
if (update.hasMessage() && update.getMessage().hasPhoto()) {
    if (courierRegistrationHandler.handlePhoto(update)) {
        return; // Фото обработано регистрацией курьера
    }
}
```

- `hasPhoto()` — в `Message` есть список фотографий.
- Это используется **только в одном месте**:
  - последняя стадия регистрации курьера — селфи с паспортом.
- Поэтому просто отдаём в `CourierRegistrationHandler.handlePhoto`.

---

### 2.4. Текстовые сообщения: всё остальное

```java
if (update.hasMessage() && update.getMessage().hasText()) {
    String text = update.getMessage().getText();
    Long telegramId = update.getMessage().getFrom().getId();
    Long chatId = update.getMessage().getChatId();
    
    // ... далее куча if-ов
}
```

Сначала выдёргиваем:

- `text` — сам текст,
- `telegramId` — кто это пишет,
- `chatId` — куда отвечать.

Дальше важно понять **порядок**, в котором мы раздаём текст хендлерам.

---

### 2.4.1. Сначала — активные сценарии (курьер, магазин, создание заказа, выбор/редактирование)

```java
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

// Если юзер выбирает заказ из списка "Мои заказы"
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
```

Идея:

- У нас есть несколько "режимов":
  - регистрация курьера,
  - регистрация магазина,
  - создание заказа,
  - выбор заказа из списка,
  - редактирование заказа.
- Каждый из этих режимов:
  - хранит где‑то своё состояние (в хендлере),
  - знает "я сейчас жду текст именно от этого юзера".

Поэтому:

- Сначала мы **спрашиваем у каждого сценария**:
  - "Это твоё сообщение?"
  - если да — он обрабатывает и возвращает `true`,
  - `Bot` делает `return` и не лезет дальше.

Почему именно такой порядок:

- если юзер сейчас регистрирует курьера — это приоритетнее, чем случайная команда,
- чтобы `/start` или `"📦 Создать заказ"` не перебили активный диалог посередине.

---

### 2.4.2. Команды и меню после сценариев

```java
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
// Кнопка меню: Мои заказы
else if (text.equals("📋 Мои заказы")) {
    handleMyOrdersButton(update);
}
```

Здесь уже обрабатываются:

- системные команды:
  - `/start` → `StartCommandHandler`,
  - `/r` → временная активация магазина,
  - `/k` → временная активация курьера.
- Reply‑кнопки из меню магазина (`sendShopMenu`):
  - `"📦 Создать заказ"` → запуск сценария создания заказа,
  - `"🏪 Мой магазин"` → показать карточку магазина,
  - `"📋 Мои заказы"` → показать список заказов.

Важно: сюда мы попадаем **только если** сообщение НЕ было съедено
регистрацией/созданием/редактированием выше.

---

## 3. Меню магазина: `sendShopMenu`

Метод вызывается и из `Bot`, и из `StartCommandHandler`:

```java
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
```

- Это **Reply‑клавиатура** (нижняя панель), не inline.
- Кнопки:
  - "📦 Создать заказ" — дальше ловится в `onUpdateReceived` как `text`.
  - "📋 Мои заказы"
  - "🏪 Мой магазин"
- `setOneTimeKeyboard(false)` — клавиатура не исчезает после нажатия.

Это и есть "главное меню магазина", которое ты видишь после активации.

---

## 4. Временные команды `/r` и `/k`

### `/r` — активировать магазин

```java
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
```

- Чисто для разработки:
  - быстро активировать магазин без админки.
- В проде это должна делать админ‑панель.

### `/k` — активировать курьера

```java
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
        sendSimpleMessage(chatId, "✅ Твой профиль курьера уже активирован.");
        return;
    }

    courierService.activateCourier(courier);
    sendSimpleMessage(chatId, "✅ *Профиль курьера активирован!*\n\n" +
            "Теперь ты можешь работать с заказами (как только мы добавим меню курьера 😎).");
}
```

- Аналогично, но для курьера.
- Тоже временное решение, пока нет нормальной админки/бек‑офиса.

---

## 5. `handleMyOrdersButton` и `handleShopInfoButton`

### "📋 Мои заказы"

```java
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
    
    // Формируем список заказов (с маршрутами / статусами / ценой и т.д.)
    // ...

    // Сохраняем список последних заказов для выбора по номеру
    myOrdersSelectionHandler.saveLastOrders(telegramId, orders);

    // Под список добавляем inline‑кнопку "🔎 Выбрать заказ"
    // ...
}
```

- Здесь ты уже видел:
  - собирается текстовый список заказов,
  - сохраняются в хендлер выбора,
  - подставляется inline‑кнопка `"orders_select"`.

### "🏪 Мой магазин"

```java
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
```

- То же, что `handleShopInfo` в `CallbackQueryHandler`, только вызывается по **Reply‑кнопке**, а не по inline‑кнопке.

---

## 6. `getBotUsername()` и `getBotToken()`

```java
@Override
public String getBotUsername() {
    return botUsername;
}

@Override
public String getBotToken() {
    return botToken;
}
```

- Библиотека `TelegramLongPollingBot` требует:
  - `getBotUsername()` — username бота (без `@`),
  - `getBotToken()` — токен для авторизации.
- Эти методы вызываются внутренне, когда бот регистрируется и ходит к Telegram.

Мы просто возвращаем значения, подтянутые через `@Value` из конфига.

---

## Итоговая схема работы `Bot.onUpdateReceived`

```text
onUpdateReceived(update):

1) Если есть callback_query:
      → CallbackQueryHandler.handle(update)
      → return

2) Если есть message + contact:
      → shopRegistrationHandler.handleContact(update) ?
            да → return
      → courierRegistrationHandler.handleContact(update) ?
            да → return

3) Если есть message + photo:
      → courierRegistrationHandler.handlePhoto(update) ?
            да → return

4) Если есть message + text:
      text = message.text
      telegramId = from.id
      chatId = chat.id

      4.1) Активные сценарии
          → courierRegistrationHandler.handleText(update) ?
          → shopRegistrationHandler.handleMessage(update) ?
          → orderCreationHandler.handleMessage(update) ?
          → myOrdersSelectionHandler.handleText(...) ?
          → orderEditHandler.handleText(...) ?
          (если любой сказал "true" → return)

      4.2) Команды / меню:
          /start → StartCommandHandler.handle(update)
          /r     → handleActivateCommand(update)
          /k     → handleActivateCourierCommand(update)
          "📦 Создать заказ" → orderCreationHandler.startOrderCreation(...)
          "🏪 Мой магазин"   → handleShopInfoButton(update)
          "📋 Мои заказы"    → handleMyOrdersButton(update)
```

---

## Что дальше разбирать

По "линии бота" ты уже понимаешь:

- `/start` → `StartCommandHandler`,
- inline‑кнопки → `CallbackQueryHandler`,
- `onUpdateReceived` → как всё отношение между собой.

Дальше логично:

- пройти **линейку курьера** в таком же стиле:
  - `Courier.java` (модель) — у тебя уже есть базовое понимание,
  - `CourierService.java`,
  - `CourierRegistrationHandler.java` (там текст + контакт + фото),
- а потом:
  - `Order.java` / `OrderStop.java`,
  - `OrderService` / `OrderRepository`,
  - `OrderCreationHandler` (создание заказа с мультиадресами),
  - `OrderEditHandler` (редактирование).

Все эти классы можно так же положить в `docs/code-explained` как:

- `09_Courier_model.md`,
- `10_Courier_service.md`,
- `11_CourierRegistrationHandler.md`,
- и т.д.

