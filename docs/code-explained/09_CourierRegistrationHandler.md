# ТОТАЛЬНЫЙ РАЗБОР: CourierRegistrationHandler.java
## Версия: "объясни КАЖДОЕ слово, сука"

> **Уровень**: хочу, чтобы даже накуренный в ноль, открыв этот файл,  
> понял, что происходит в регистрации курьера, от /start до селфи с паспортом.

---

## 0. Коротко: что делает этот класс

`CourierRegistrationHandler` — это **пошаговый сценарий регистрации курьера**.

Диалог с юзером:

1. Юзер выбирает роль **"Курьер"** → жмёт inline‑кнопку.
2. Мы просим: **"введи имя и фамилию"** (текст).
3. Потом просим: **"поделись номером телефона"** (кнопка → `Contact`).
4. Потом просим: **"пришли селфи с паспортом"** (фото → `Photo`).
5. Когда всё есть — создаём запись `Courier` в БД.

Все промежуточные данные (имя, телефон, file_id фото, текущий шаг)  
живут в специальном объекте `CourierRegistrationData`, который мы  
храним в мапе `registrationDataMap` по ключу `telegramId`.

Дальше — **код и разбор пословно**.

---

## 1. Объявление класса и поля

### Код

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class CourierRegistrationHandler {

    private final CourierService courierService;

    @Autowired
    @Lazy
    private Bot bot;

    /**
     * Временные данные регистрации курьера.
     * Ключ: telegramId курьера.
     */
    private final Map<Long, CourierRegistrationData> registrationDataMap = new ConcurrentHashMap<>();
```

### Разбор

- `public class CourierRegistrationHandler`  
  - `public` — класс виден отовсюду.  
  - `class` — объявляем новый тип.  
  - `CourierRegistrationHandler` — имя типа: "обработчик регистрации курьера".

- `private final CourierService courierService;`  
  - `CourierService` — другой класс‑сервис, который:
    - знает, как искать курьеров по `telegramId`,
    - как создавать курьера в БД (`registerCourier`),
    - как активировать/блокировать.  
  - `courierService` — поле, через которое мы этот сервис зовём.
  - `final` — поле инициализируется один раз (через конструктор) и потом не меняется.

- `private Bot bot;`  
  - `Bot` — твой главный класс бота, наследник `TelegramLongPollingBot`.  
  - Мы храним на него ссылку, чтобы вызывать:
    - `bot.execute(...)` — отправить сообщение в Telegram,
    - `bot` может делать ещё что‑то общее (но тут только отправка).
  - Над ним висят:
    - `@Autowired` — Spring сам подставит сюда экземпляр `Bot`.
    - `@Lazy` — подставит **прокси**, чтобы не было цикла `Bot ↔ Handler`.

- `private final Map<Long, CourierRegistrationData> registrationDataMap = new ConcurrentHashMap<>();`  
  - `Map<K,V>` — ассоциативный массив "ключ → значение".  
  - `Long` — ключ: `telegramId` пользователя.  
  - `CourierRegistrationData` — значение: объект, в котором:
    - `fullName` — ФИО,
    - `phone` — телефон,
    - `passportPhotoFileId` — file_id фото,
    - `state` — шаг диалога.
  - `ConcurrentHashMap` — реализация `Map`, которая умеет нормально жить в многопоточке:
    - у нас бот обрабатывает несколько юзеров параллельно,
    - разные потоки могут одновременно лезть в эту мапу.
  - `registrationDataMap` по смыслу:

    > "таблица всех текущих регистраций курьеров:  
    > ключ — Telegram ID, значение — где он сейчас в сценарии и что уже ввёл".

---

## 2. Старт регистрации из кнопки "Курьер"

### Код

```java
public void startRegistrationFromCallback(Long telegramId, Long chatId, String ignoredFullName) {
    log.info("Начало регистрации курьера: telegramId={}", telegramId);

    // Проверяем, не зарегистрирован ли курьер уже
    if (courierService.findByTelegramId(telegramId).isPresent()) {
        sendSimpleMessage(chatId, "❌ Ты уже зарегистрирован как курьер.");
        return;
    }

    // Создаём данные регистрации и ставим первый шаг — ждём ФИО
    CourierRegistrationData data = new CourierRegistrationData();
    data.setState(CourierRegistrationState.WAITING_FULL_NAME);
    registrationDataMap.put(telegramId, data);

    // Спрашиваем имя и фамилию
    sendSimpleMessage(chatId,
            "🚴 *Регистрация курьера*\n\n" +
                    "Шаг 1 из 3\n" +
                    "Напиши, пожалуйста, своё *имя и фамилию*.\n\n" +
                    "Пример: `Иван Петров`");
}
```

### Откуда вызывается этот метод

В `CallbackQueryHandler.handleRoleSelection` есть:

```java
if (selectedRole == Role.COURIER) {
    // ...
    courierRegistrationHandler.startRegistrationFromCallback(telegramId, chatId, null);
}
```

- Ты жмёшь inline‑кнопку "Курьер".
- В `callbackData` приходит `"role_courier"`.
- `CallbackQueryHandler` решает, что это выбор роли курьера.
- Он:
  - пишет в БД роль,
  - и зовёт `startRegistrationFromCallback(...)`.

### Разбор строки за строкой

```java
public void startRegistrationFromCallback(Long telegramId, Long chatId, String ignoredFullName) {
```

- `telegramId` — Telegram‑ID пользователя (кто нажал кнопку).  
- `chatId` — ID чата, в котором мы будем с ним общаться.  
- `ignoredFullName` — третий параметр, который мы сейчас не используем  
  (его можно было бы удалить, но он нам не мешает).

```java
log.info("Начало регистрации курьера: telegramId={}", telegramId);
```

- просто пишем в лог событие — чтобы потом в логах видеть,  
  что для этого `telegramId` стартовала регистрация.

```java
if (courierService.findByTelegramId(telegramId).isPresent()) {
    sendSimpleMessage(chatId, "❌ Ты уже зарегистрирован как курьер.");
    return;
}
```

- `courierService.findByTelegramId(telegramId)`:
  - ходит в БД через `CourierRepository`,
  - пытается найти `Courier`, связанного с этим `telegramId`.
  - возвращает `Optional<Courier>`.
- `.isPresent()`:
  - `true` → курьер уже есть,
  - `false` → курьера пока нет.

Если уже есть:

- шлём сообщение "ты уже зарегистрирован",
- `return;` — не запускаем сценарий ещё раз.

```java
CourierRegistrationData data = new CourierRegistrationData();
```

- создаём **новый, пустой контейнер** для временных данных регистрации.

```java
data.setState(CourierRegistrationState.WAITING_FULL_NAME);
```

Разбираем по словам:

- `data` — только что созданный объект `CourierRegistrationData`.
- `setState(...)` — метод‑сеттер, который присваивает значение полю `state`:

  ```java
  public void setState(CourierRegistrationState state) {
      this.state = state;
  }
  ```

- `CourierRegistrationState.WAITING_FULL_NAME`:
  - enum‑константа, одно из значений типа `CourierRegistrationState`.
  - переводится как "жду полное имя".

То есть:

> **data.setState(CourierRegistrationState.WAITING_FULL_NAME);**  
> = "Пометь, что для этого курьера регистрации мы на шаге 1 — ждём, когда он введёт ФИО".

```java
registrationDataMap.put(telegramId, data);
```

- `registrationDataMap` — наша глобальная мапа состояний.
- `.put(telegramId, data)`:
  - кладём `data` под ключом `telegramId`.

По смыслу:

> "Запомнили, что пользователь с таким Telegram‑ID сейчас проходит регистрацию курьера и находится на шаге WAITING_FULL_NAME".

```java
sendSimpleMessage(chatId,
        "🚴 *Регистрация курьера*\n\n" +
                "Шаг 1 из 3\n" +
                "Напиши, пожалуйста, своё *имя и фамилию*.\n\n" +
                "Пример: `Иван Петров`");
```

- `sendSimpleMessage` — наш приватный метод ниже:
  - упаковывает текст в `SendMessage` и шлёт через `bot.execute(...)`.
- `chatId` — куда отправлять (личный чат с юзером).
- Строка сообщения — просто Markdown‑текст.

---

## 3. Обработка текстовых сообщений: `handleText(Update update)`

### Код

```java
public boolean handleText(Update update) {
    Long telegramId = update.getMessage().getFrom().getId();
    Long chatId = update.getMessage().getChatId();
    String text = update.getMessage().getText();

    CourierRegistrationData data = registrationDataMap.get(telegramId);
    if (data == null || data.getState() == CourierRegistrationState.NONE) {
        return false;
    }

    if (data.getState() == CourierRegistrationState.WAITING_FULL_NAME) {
        // ... шаг 1 (ФИО)
    }

    if (data.getState() == CourierRegistrationState.WAITING_PHONE) {
        // ... шаг 2 (напоминаем про кнопку контакта)
    }

    if (data.getState() == CourierRegistrationState.WAITING_PASSPORT_PHOTO) {
        // ... шаг 3 (напоминаем, что ждём фото)
    }

    return false;
}
```

### Откуда берётся `update.getMessage().getFrom().getId()`

- `update` — пришёл из `Bot.onUpdateReceived`, где уже проверили, что это **сообщение с текстом**.

Вызовы по цепочке:

1. `update.getMessage()`  
   - достаём объект `Message` из `Update`.
   - в нём лежит всё про конкретное сообщение:
     - текст, фото, контакт и т.д.
2. `.getFrom()`  
   - возвращает Telegram‑пользователя, который это сообщение отправил.
   - это **НЕ** твой `model.User`, это класс из TelegramBots.
3. `.getId()`  
   - Telegram‑ID этого пользователя (`Long`).

То есть:

```java
Long telegramId = update.getMessage().getFrom().getId();
```

= "вытащи из апдейта сообщение, из него — отправителя, из него — его Telegram ID".

`chatId` и `text` аналогично:

- `getChatId()` — ID чата (куда отвечать).
- `getText()` — текст сообщения.

### Как мы решаем, обрабатывать ли этот текст вообще

```java
CourierRegistrationData data = registrationDataMap.get(telegramId);
if (data == null || data.getState() == CourierRegistrationState.NONE) {
    return false;
}
```

- `registrationDataMap.get(telegramId)`:
  - достаём наши временные данные по этому юзеру.
- `data == null`:
  - юзер не начинал регистрацию курьера,
  - или мы уже её завершили и удалили из карты.
- `data.getState() == NONE`:
  - явное состояние "ничего не ждём".

Если одно из условий выполнено:

- возвращаем `false`:
  - это сигнал `Bot`: "**это сообщение не относится к регистрации курьера, передавай дальше**".

---

## 4. Шаг 1: мы действительно ждём ФИО

### Код блока

```java
if (data.getState() == CourierRegistrationState.WAITING_FULL_NAME) {
    // Валидация имени
    if (text.length() < 3) {
        sendSimpleMessage(chatId, "❌ Имя слишком короткое. Введи имя и фамилию полностью:");
        return true;
    }
    if (text.length() > 255) {
        sendSimpleMessage(chatId, "❌ Имя слишком длинное. Максимум 255 символов.");
        return true;
    }

    data.setFullName(text);
    data.setState(CourierRegistrationState.WAITING_PHONE);

    // Просим номер телефона через кнопку контакта
    sendMessageWithContactButton(chatId,
            "✅ Имя: *" + text + "*\n\n" +
                    "Шаг 2 из 3\n" +
                    "Теперь нажми кнопку ниже и поделись своим *номером телефона*.\n\n" +
                    "Этот номер будут видеть магазин и получатель.");
    return true;
}
```

### По словам

- `if (data.getState() == CourierRegistrationState.WAITING_FULL_NAME)`:
  - `data.getState()` — читаем текущее состояние регистрации.
  - `CourierRegistrationState.WAITING_FULL_NAME` — значение enum-а "ждём ФИО".
  - `==` — проверяем, совпало ли.
  - Если да — **обрабатываем этот текст как ФИО**.

- `text.length()`:
  - `text` — строка, которую прислал юзер,
  - `.length()` — длина строки в символах.
  - `< 3` → слишком коротко → шлём ошибку.
  - `> 255` → не влезет в БД → шлём ошибку.

- `data.setFullName(text);`
  - `setFullName` — сеттер для поля `fullName` в `CourierRegistrationData`.
  - По смыслу: "запомни это ФИО во временных данных для этого курьера".

- `data.setState(CourierRegistrationState.WAITING_PHONE);`
  - `setState` — сеттер для поля `state`.
  - `CourierRegistrationState.WAITING_PHONE` — enum‑значение "теперь ждём телефон".
  - По смыслу:

    > "с этого момента этот курьер считается находящимся на шаге 'ждём телефон'".

- `sendMessageWithContactButton(chatId, "...")`:
  - наш метод ниже:
    - создаёт `SendMessage`,
    - добавляет Reply‑клавиатуру с кнопкой `"📱 Поделиться номером телефона"`,
    - эта кнопка при нажатии шлёт `Contact` → `handleContact`.

- `return true;`:
  - мы говорим: "**это сообщение я обработал, дальше его никто не трогает**".

---

## 5. Шаг 2: если он пишет текст, а мы ждём кнопку

Код:

```java
if (data.getState() == CourierRegistrationState.WAITING_PHONE) {
    // Мы ждём контакт, а не текст
    sendSimpleMessage(chatId,
            "👆 Сейчас нажми кнопку *\"Поделиться номером телефона\"* внизу экрана.");
    return true;
}
```

Логика:

- `data.getState() == WAITING_PHONE`:
  - мы уже сохранили ФИО,
  - уже отправили кнопку "поделиться номером".
- Если в этот момент приходит **текст**, а не `Contact`:
  - мы не доверяем текстовому номеру,
  - не хотим парсить странные форматы,
  - хотим только официальный `Contact` от Telegram.
- Поэтому просто напоминаем:
  - "нажми, пожалуйста, кнопку, а не пиши текст".

Опять `return true;` → это сообщение считается обработанным этим хендлером.

---

## 6. Шаг 3: если он пишет текст, а мы ждём фото

Код:

```java
if (data.getState() == CourierRegistrationState.WAITING_PASSPORT_PHOTO) {
    sendSimpleMessage(chatId,
            "📸 Осталось отправить *селфи с паспортом* как фото.\n" +
                    "Просто прикрепи фото и отправь его сюда.");
    return true;
}
```

Логика:

- `state == WAITING_PASSPORT_PHOTO`:
  - телефон уже есть,
  - мы просили селфи.
- Любой текст:
  - мягко игнорим,
  - ещё раз объясняем, что нужно **фото**, а не текст.

---

## 7. Обработка контакта: `handleContact(Update update)`

Здесь дальше по файлу тот же принцип:

- сначала достаём `telegramId`, `chatId`, `Contact`,
- проверяем `data` и `state`,
- кладём `phone` через `data.setPhone(...)`,
- сдвигаем `state` на `WAITING_PASSPORT_PHOTO`,
- убираем клавиатуру,
- просим селфи.

Все строки вида:

- `registrationDataMap.get(telegramId)` — "достань рюкзак по ключу telegramId",
- `data.setPhone(phone)` — "положи телефон в этот рюкзак",
- `data.setState(CourierRegistrationState.WAITING_PASSPORT_PHOTO)` — "теперь ждём фотку",
- `update.getMessage().getContact().getPhoneNumber()` — "из апдейта → сообщение → контакт → номер".

работают по тем же правилам, что я показал выше для `setState` и `getState`.

Если хочешь, следующий шаг — могу так же **разобрать любую конкретную строку** из этого файла (например `update.getMessage().getPhoto().get(...)`) и потом по шаблону дописать весь низ файла в том же стиле. 
# ТОТАЛЬНЫЙ РАЗБОР: CourierRegistrationHandler.java
## Как бот дрючит курьера: ФИО → телефон → селфи с паспортом

> **Уровень**: "Хочу понимать, что происходит, когда я жму 'Курьер' и шлёпаю селфи"  
> **Цель**: Разобрать регистрацию курьера по шагам и по цепочкам: `update.getMessage().getFrom().getId()`, `getPhoto()`, `fileId` и т.д.  
> **Стиль**: как будто ты сам проходишь регистрацию, но параллельно читаешь исходники и материшься

---

## 0. Где этот хендлер в архитектуре

Схема:

```text
/start → StartCommandHandler  → inline‑клавиатура "Магазин / Курьер"
                      ↓
нажатие "Курьер" → CallbackQueryHandler (role_courier)
                      ↓
CourierRegistrationHandler.startRegistrationFromCallback(...)
                      ↓
Шаг 1: текст (ФИО) → handleText(...)
Шаг 2: контакт → handleContact(...)
Шаг 3: фото → handlePhoto(...)
                      ↓
CourierService.registerCourier(...) → БД (couriers)
```

`CourierRegistrationHandler`:

- ведёт состояние регистрации курьера по `telegramId`,
- задаёт три вопроса:
  - ФИО,
  - телефон (контакт),
  - селфи с паспортом,
- в конце создаёт `Courier` через `CourierService`.

---

## 1. Шапка файла и зависимости

```java
package org.example.flower_delivery.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.Bot;
import org.example.flower_delivery.model.Courier;
import org.example.flower_delivery.model.CourierRegistrationData;
import org.example.flower_delivery.model.CourierRegistrationState;
import org.example.flower_delivery.service.CourierService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Contact;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardRemove;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
```

Кто тут кто:

- `CourierService` — сервис, который реально создаёт/активирует курьеров в БД.
- `CourierRegistrationData` — временный объект с ФИО, телефоном и fileId фотки.
- `CourierRegistrationState` — enum c шагами (`WAITING_FULL_NAME`, `WAITING_PHONE`, `WAITING_PASSPORT_PHOTO`).
- `Bot` — нужен, чтобы отправлять сообщения, клавиатуры, убирать клавиатуру.
- `Update`, `Contact`:
  - `Update` — общий конверт от Telegram (как всегда),
  - `Contact` — объект с телефоном юзера, когда он нажимает "Поделиться номером".
- `ReplyKeyboardMarkup` / `ReplyKeyboardRemove` / `KeyboardButton`:
  - рисуем кнопку "📱 Поделиться номером телефона",
  - потом убираем клавиатуру.

---

## 2. Аннотации и поля

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class CourierRegistrationHandler {

    private final CourierService courierService;

    @Autowired
    @Lazy
    private Bot bot;

    /**
     * Временные данные регистрации курьера.
     * Ключ: telegramId курьера.
     */
    private final Map<Long, CourierRegistrationData> registrationDataMap = new ConcurrentHashMap<>();
```

- `@Component` — Spring создаёт бин `courierRegistrationHandler`.
- `@RequiredArgsConstructor` — конструктор с параметром `CourierService`.
- `@Slf4j` — лог `log`.

Поля:

- `courierService` — мозг по курьерам (создание, поиск, активация).
- `bot`:
  - `@Autowired` + `@Lazy`:
    - чтобы не было цикла `Bot ↔ CourierRegistrationHandler`.
  - через него вызываем `bot.execute(...)`.
- `registrationDataMap`:
  - ключ: `Long telegramId` (ID юзера в Telegram),
  - значение: `CourierRegistrationData` с текущим состоянием.
  - `ConcurrentHashMap` — потокобезопасная, потому что апдейты могут обрабатываться параллельно.

---

## 3. Старт регистрации из CallbackQuery (`role_courier`)

```java
public void startRegistrationFromCallback(Long telegramId, Long chatId, String ignoredFullName) {
    log.info("Начало регистрации курьера: telegramId={}", telegramId);

    // Проверяем, не зарегистрирован ли курьер уже
    if (courierService.findByTelegramId(telegramId).isPresent()) {
        sendSimpleMessage(chatId, "❌ Ты уже зарегистрирован как курьер.");
        return;
    }

    // Создаём данные регистрации и ставим первый шаг — ждём ФИО
    CourierRegistrationData data = new CourierRegistrationData();
    data.setState(CourierRegistrationState.WAITING_FULL_NAME);
    registrationDataMap.put(telegramId, data);

    // Спрашиваем имя и фамилию
    sendSimpleMessage(chatId,
            "🚴 *Регистрация курьера*\n\n" +
                    "Шаг 1 из 3\n" +
                    "Напиши, пожалуйста, своё *имя и фамилию*.\n\n" +
                    "Пример: `Иван Петров`");
}
```

Как сюда попадаем:

- в `CallbackQueryHandler.handleRoleSelection` при выборе роли `Курьер` мы зовём:

```java
courierRegistrationHandler.startRegistrationFromCallback(telegramId, chatId, null);
```

Пошагово:

1. Логируем начало регистрации.
2. Через `courierService.findByTelegramId(telegramId)` проверяем:
   - если курьер уже есть → шлём "ты уже зарегистрирован" и выходим.
3. Если нет:
   - создаём `CourierRegistrationData`,
   - ставим `state = WAITING_FULL_NAME`,
   - кладём в `registrationDataMap` под ключом `telegramId`.
4. Шлём пользователю сообщение "Шаг 1 из 3: напиши имя и фамилию".

То есть `startRegistrationFromCallback`:

- инициализирует состояние,
- задаёт первый вопрос,
- НИЧЕГО не пишет в БД пока.

---

## 4. Обработка текста: ФИО → жди телефон → жди фото

```java
public boolean handleText(Update update) {
    Long telegramId = update.getMessage().getFrom().getId();
    Long chatId = update.getMessage().getChatId();
    String text = update.getMessage().getText();

    CourierRegistrationData data = registrationDataMap.get(telegramId);
    if (data == null || data.getState() == CourierRegistrationState.NONE) {
        return false;
    }

    if (data.getState() == CourierRegistrationState.WAITING_FULL_NAME) {
        // ...
    }

    if (data.getState() == CourierRegistrationState.WAITING_PHONE) {
        // ...
    }

    if (data.getState() == CourierRegistrationState.WAITING_PASSPORT_PHOTO) {
        // ...
    }

    return false;
}
```

### 4.1. Откуда берутся `update.getMessage().getFrom().getId()`, `getChatId()`, `getText()`

- `update` — всё тот же конверт от Telegram.
- `update.getMessage()` — объект `Message`:
  - код сюда попадает из `Bot.onUpdateReceived`, где уже проверили `hasMessage()` и `hasText()`.
- `getFrom().getId()`:
  - `getFrom()` — **кто отправил** сообщение (Telegram‑пользователь).
  - `.getId()` — его Telegram‑ID (`Long`).
- `getChatId()` — ID чата (куда отвечать, как всегда).
- `getText()` — сам текст, который он набрал (ФИО или какой‑то мусор).

### 4.2. Проверка, в регистрации ли он вообще

```java
CourierRegistrationData data = registrationDataMap.get(telegramId);
if (data == null || data.getState() == CourierRegistrationState.NONE) {
    return false;
}
```

- Если `data == null`:
  - мы ещё не создавали запись для этого `telegramId` → значит он **не** в процессе регистрации курьера.
- Если `state == NONE`:
  - явно помечено, что регистрации нет.

Возвращаем `false`:

- говорим `Bot`: "это сообщение не моё, дальше пусть другие хендлеры попробуют его обработать".

### 4.3. Шаг 1: `WAITING_FULL_NAME`

```java
if (data.getState() == CourierRegistrationState.WAITING_FULL_NAME) {
    // Валидация имени
    if (text.length() < 3) {
        sendSimpleMessage(chatId, "❌ Имя слишком короткое. Введи имя и фамилию полностью:");
        return true;
    }
    if (text.length() > 255) {
        sendSimpleMessage(chatId, "❌ Имя слишком длинное. Максимум 255 символов.");
        return true;
    }

    data.setFullName(text);
    data.setState(CourierRegistrationState.WAITING_PHONE);

    // Просим номер телефона через кнопку контакта
    sendMessageWithContactButton(chatId,
            "✅ Имя: *" + text + "*\n\n" +
                    "Шаг 2 из 3\n" +
                    "Теперь нажми кнопку ниже и поделись своим *номером телефона*.\n\n" +
                    "Этот номер будут видеть магазин и получатель.");
    return true;
}
```

- Валидация:
  - длина ≥ 3,
  - длина ≤ 255.
- Если ок:
  - кладём `fullName` в `data`,
  - меняем состояние на `WAITING_PHONE`,
  - отправляем сообщение с кнопкой "📱 Поделиться номером телефона".

`sendMessageWithContactButton`:

```java
KeyboardButton contactButton = new KeyboardButton("📱 Поделиться номером телефона");
contactButton.setRequestContact(true);
```

- При нажатии этой кнопки Telegram:
  - сам показывает системное окно,
  - сам шлёт апдейт с `Message.hasContact() == true`.

### 4.4. Шаг 2: если он вместо кнопки пишет текст

```java
if (data.getState() == CourierRegistrationState.WAITING_PHONE) {
    // Мы ждём контакт, а не текст
    sendSimpleMessage(chatId,
            "👆 Сейчас нажми кнопку *\"Поделиться номером телефона\"* внизу экрана.");
    return true;
}
```

- Бывает, что человек игнорит кнопку и пишет "мой номер: +7...".
- Но нам надо именно `Contact` от Telegram:
  - тогда он точно владелец номера,
  - не будет опечаток,
  - можно потом звать `getPhoneNumber()` безопасно.
- Поэтому на любой текст в этом состоянии:
  - мы просто мягко возвращаем его к кнопке.

### 4.5. Шаг 3: он пишет текст вместо фото

```java
if (data.getState() == CourierRegistrationState.WAITING_PASSPORT_PHOTO) {
    sendSimpleMessage(chatId,
            "📸 Осталось отправить *селфи с паспортом* как фото.\n" +
                    "Просто прикрепи фото и отправь его сюда.");
    return true;
}
```

- Если он уже прошёл телефон, но шлёт какой‑то текст:
  - напоминаем, что мы ждём именно **фото**, а не текст.

---

## 5. Обработка контакта: телефон курьера

```java
public boolean handleContact(Update update) {
    Long telegramId = update.getMessage().getFrom().getId();
    Long chatId = update.getMessage().getChatId();

    CourierRegistrationData data = registrationDataMap.get(telegramId);
    if (data == null || data.getState() != CourierRegistrationState.WAITING_PHONE) {
        return false;
    }

    Contact contact = update.getMessage().getContact();
    String phone = contact.getPhoneNumber();

    log.info("Регистрация курьера: получен телефон telegramId={}, phone={}", telegramId, phone);

    data.setPhone(phone);
    data.setState(CourierRegistrationState.WAITING_PASSPORT_PHOTO);

    try {
        // Убираем клавиатуру и просим селфи с паспортом
        sendMessageWithKeyboardRemove(chatId,
                "✅ Телефон сохранён: *" + phone + "*\n\n" +
                        "Шаг 3 из 3\n" +
                        "Теперь отправь, пожалуйста, *селфи с паспортом*.\n" +
                        "Просто сделай фото, где видно тебя и разворот паспорта, и пришли сюда как обычное фото.");

    } catch (Exception e) {
        log.error("Ошибка регистрации курьера: telegramId={}", telegramId, e);
        sendMessageWithKeyboardRemove(chatId,
                "❌ Ошибка при регистрации курьера: " + e.getMessage());
    }

    return true;
}
```

Откуда берётся `Contact`:

- в `Bot.onUpdateReceived`:

```java
if (update.hasMessage() && update.getMessage().hasContact()) {
    if (shopRegistrationHandler.handleContact(update)) { ... }
    if (courierRegistrationHandler.handleContact(update)) { ... }
}
```

- `hasContact()` — в `Message` пришёл объект `Contact` (результат кнопки).
- `getContact()` — достаём его.

`Contact`:

- это типа:
  - телефон,
  - имя,
  - иногда `userId`.
- `getPhoneNumber()` — номер, как его даёт Telegram (`+7...`).

Шаги:

1. Проверяем, что для этого `telegramId` мы реально на шаге `WAITING_PHONE`:
   - если нет → возвращаем `false`, значит контакт относится не к нам.
2. Достаём `contact` и `phone`.
3. В лог пишем номер.
4. Кладём `phone` в `data`, меняем `state` на `WAITING_PASSPORT_PHOTO`.
5. Через `sendMessageWithKeyboardRemove`:
   - убираем Reply‑клавиатуру,
   - просим селфи с паспортом.

---

## 6. Обработка фото: селфи с паспортом

```java
public boolean handlePhoto(Update update) {
    Long telegramId = update.getMessage().getFrom().getId();
    Long chatId = update.getMessage().getChatId();

    CourierRegistrationData data = registrationDataMap.get(telegramId);
    if (data == null || data.getState() != CourierRegistrationState.WAITING_PASSPORT_PHOTO) {
        return false;
    }

    if (update.getMessage().getPhoto() == null || update.getMessage().getPhoto().isEmpty()) {
        sendSimpleMessage(chatId, "❌ Не вижу фото. Пришли, пожалуйста, именно *фото*, не файл.");
        return true;
    }

    // Берём самое "большое" фото из списка (последний элемент)
    var photos = update.getMessage().getPhoto();
    String fileId = photos.get(photos.size() - 1).getFileId();

    data.setPassportPhotoFileId(fileId);

    try {
        Courier courier = courierService.registerCourier(
                telegramId,
                data.getFullName(),
                data.getPhone(),
                data.getPassportPhotoFileId()
        );
        log.info("Курьер успешно создан: courierId={}, telegramId={}",
                courier.getId(), telegramId);

        // Чистим временные данные
        registrationDataMap.remove(telegramId);

        sendSimpleMessage(chatId,
                "🎉 *Регистрация курьера завершена!*\n\n" +
                        "👤 Имя: " + courier.getFullName() + "\n" +
                        "📱 Телефон: " + courier.getPhone() + "\n\n" +
                        "⏳ Сейчас твой профиль ждёт *активации администратором*.\n" +
                        "После активации ты сможешь брать заказы.");

    } catch (Exception e) {
        log.error("Ошибка завершения регистрации курьера: telegramId={}", telegramId, e);
        registrationDataMap.remove(telegramId);
        sendSimpleMessage(chatId,
                "❌ Ошибка при сохранении данных курьера: " + e.getMessage());
    }

    return true;
}
```

Ключевые моменты:

### 6.1. Что такое `getPhoto()`

- `update.getMessage().getPhoto()`:
  - возвращает **список `PhotoSize`**:
    - Telegram всегда сохраняет фото в нескольких размерах,
    - этот список отсортирован по "качеству"/размеру.
- `photos.get(photos.size() - 1)`:
  - берём последнюю (обычно самую большую по качеству) версию.
- `.getFileId()`:
  - строка‑идентификатор файла на стороне Telegram.
  - мы её храним в БД (`Courer.passportPhotoFileId`),
  - потом по ней можно скачать фотку, если понадобится (через Telegram Bot API).

### 6.2. Вызов `courierService.registerCourier(...)`

```java
Courier courier = courierService.registerCourier(
        telegramId,
        data.getFullName(),
        data.getPhone(),
        data.getPassportPhotoFileId()
);
```

- Передаём:
  - `telegramId` — находим/привязываем `User`,
  - `fullName` — то, что ввёл курьер,
  - `phone` — из `Contact`,
  - `passportPhotoFileId` — from `getPhoto().getFileId()`.

Сервис:

- проверяет, что курьера ещё нет,
- создаёт запись `Courier` в БД с:
  - `status = PENDING`,
  - `isActive = false`,
  - ссылкой на `User`.

### 6.3. Что дальше

- Если всё успешно:
  - логируем,
  - удаляем временные данные из `registrationDataMap`,
  - шлём сообщение "регистрация завершена, ждёшь активации".
- Если ошибка:
  - чистим `registrationDataMap`,
  - шлём сообщение с ошибкой.

---

## 7. Вспомогательные методы отправки сообщений

### 7.1. `sendMessageWithContactButton`

```java
private void sendMessageWithContactButton(Long chatId, String text) {
    SendMessage message = new SendMessage();
    message.setChatId(chatId.toString());
    message.setText(text);
    message.setParseMode("Markdown");

    // Кнопка "Поделиться номером телефона"
    KeyboardButton contactButton = new KeyboardButton("📱 Поделиться номером телефона");
    contactButton.setRequestContact(true);

    KeyboardRow row = new KeyboardRow();
    row.add(contactButton);

    ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
    keyboard.setKeyboard(List.of(row));
    keyboard.setResizeKeyboard(true);
    keyboard.setOneTimeKeyboard(true);

    message.setReplyMarkup(keyboard);

    try {
        bot.execute(message);
    } catch (TelegramApiException e) {
        log.error("Ошибка отправки сообщения с кнопкой контакта: chatId={}", chatId, e);
    }
}
```

- Рисуем **Reply‑клавиатуру** с одной кнопкой:
  - `requestContact = true` → Telegram сам запросит контакт.

### 7.2. `sendMessageWithKeyboardRemove`

```java
private void sendMessageWithKeyboardRemove(Long chatId, String text) {
    SendMessage message = new SendMessage();
    message.setChatId(chatId.toString());
    message.setText(text);
    message.setParseMode("Markdown");
    message.setReplyMarkup(new ReplyKeyboardRemove(true));

    try {
        bot.execute(message);
    } catch (TelegramApiException e) {
        log.error("Ошибка отправки сообщения: chatId={}", chatId, e);
    }
}
```

- Отправляем текст **и убираем кастомную клавиатуру**:
  - после того как телефон уже получен.

### 7.3. `sendSimpleMessage`

```java
private void sendSimpleMessage(Long chatId, String text) {
    SendMessage message = new SendMessage();
    message.setChatId(chatId.toString());
    message.setText(text);
    message.setParseMode("Markdown");
    try {
        bot.execute(message);
    } catch (TelegramApiException e) {
        log.error("Ошибка отправки сообщения: chatId={}", chatId, e);
    }
}
```

- Обычная отправка текста c Markdown без клавиатурных извращений.

---

## 8. Итоговая схема регистрации курьера

```text
1) Пользователь нажимает "Курьер" в приветствии
   → CallbackQueryHandler (role_courier)
   → courierRegistrationHandler.startRegistrationFromCallback(telegramId, chatId)
      - проверка, что курьера ещё нет
      - создаём запись в registrationDataMap[telegramId]
      - state = WAITING_FULL_NAME
      - спрашиваем ФИО

2) Пользователь пишет "Иван Петров"
   → Bot.onUpdateReceived (message + text)
   → courierRegistrationHandler.handleText(update)
      - state = WAITING_FULL_NAME
      - валидируем строку
      - сохраняем fullName
      - state = WAITING_PHONE
      - шлём кнопку "📱 Поделиться номером телефона"

3) Пользователь жмёт кнопку "Поделиться номером телефона"
   → Bot.onUpdateReceived (message + contact)
   → courierRegistrationHandler.handleContact(update)
      - state = WAITING_PHONE
      - сохраняем phone
      - state = WAITING_PASSPORT_PHOTO
      - убираем клавиатуру и просим селфи с паспортом

4) Пользователь шлёпает селфи с паспортом
   → Bot.onUpdateReceived (message + photo)
   → courierRegistrationHandler.handlePhoto(update)
      - state = WAITING_PASSPORT_PHOTO
      - берём последний `PhotoSize` → fileId
      - сохраняем passportPhotoFileId
      - вызываем courierService.registerCourier(...)
      - создаётся запись в таблице `couriers`
      - чистим registrationDataMap[telegramId]
      - шлём "регистрация завершена, жди активации"
```

---

## Что дальше разбирать

По курьерской линии логично продолжить:

- `Courier.java` — модель (у тебя уже есть базовый разбор, можно дополнить),
- `CourierService.java` — как мы ищем/создаём/активируем курьеров,
- потом перейти к тому, как будет выглядеть **меню курьера** и "умный подбор заказов по пути".

Все это можно продолжить в `10_Courier_service.md`, `11_Courier_model.md` и т.д.,
в таком же тотальном стиле, чтобы у тебя по всему проекту была живая документация.

