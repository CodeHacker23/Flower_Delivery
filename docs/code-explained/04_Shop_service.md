# ТОТАЛЬНЫЙ РАЗБОР: ShopService.java
## Магазины: бизнес-логика между ботом и базой

> **Уровень**: "Я уже понял модель и репозиторий, хочу понять МЕЖДУ"  
> **Цель**: Осознать, что делает сервис, зачем он нужен и почему это не должен делать ни репозиторий, ни хендлер  
> **Стиль**: объясняем пьяному тимлиду, который в 3 ночи вспомнил, что у него релиз

---

## Где мы находимся в архитектуре

У тебя уже есть:

- `User` / `Shop` — **модели** (структура данных, таблицы).
- `ShopRepository` — **репозиторий** (как мы ходим в БД).

Но кто решает:

- "можно ли создать магазин?",
- "что делать, если магазин уже есть?",
- "как логировать эти действия?",
- "что возвращать боту?"

Этим занимается **`ShopService`** — слой бизнес‑логики.

Схема:

```text
Telegram Bot (Handlers)  →  ShopService  →  ShopRepository  →  БД
                                 ↑
                              UserRepository
```

Хендлеры:

- принимают апдейты от Telegram,
- знают, КОГДА дернуть сервис,
- не лазают в БД напрямую (это важно).

Сервис:

- решает ЧТО делать (бизнес‑правила),
- использует репозитории как "тупые" исполнители запросов.

---

## Полный код для ориентира

```java
package org.example.flower_delivery.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.model.User;
import org.example.flower_delivery.repository.ShopRepository;
import org.example.flower_delivery.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Сервис для работы с магазинами (Shop).
 *
 * Здесь живёт бизнес-логика:
 * - поиск магазина по пользователю / telegramId
 * - создание нового магазина
 * - активация магазина и т.д.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ShopService {

    private final ShopRepository shopRepository;
    private final UserRepository userRepository;

    /**
     * Найти магазин по пользователю.
     */
    public Optional<Shop> findByUser(User user) {
        log.debug("Поиск магазина по пользователю: userId={}", user.getId());
        return shopRepository.findByUser(user);
    }

    /**
     * Найти магазин по ID пользователя (users.id).
     */
    public Optional<Shop> findByUserId(UUID userId){
        log.debug("Поиск магазина по userId={}", userId);
        return shopRepository.findByUserId(userId);
    }

    /**
     * Найти магазин по Telegram ID пользователя.
     */
    public Optional<Shop> findByUserTelegramId(Long telegramId){
        log.debug("Поиск магазина по telegramId={}", telegramId);
        return shopRepository.findByUserTelegramId(telegramId);
    }

    /**
     * Создать новый магазин для пользователя.
     *
     * Пока без координат — только базовые поля.
     */
    public Shop createShopForUser(Long telegramId,
                                  String shopName,
                                  String pickupAddress,
                                  String phone) {

        log.info("Создание магазина: telegramId={}, shopName={}", telegramId, shopName);

        // 1. Находим пользователя по telegramId
        User user = userRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalStateException("Пользователь не найден для telegramId=" + telegramId));

        // 2. Проверяем, что у него ещё нет магазина
        shopRepository.findByUser(user).ifPresent(existing -> {
            throw new IllegalStateException("У пользователя уже есть магазин");
        });

        // 3. Собираем сущность Shop
        Shop shop = Shop.builder()
                .user(user)
                .shopName(shopName)
                .pickupAddress(pickupAddress)
                .phone(phone)
                .isActive(false) // по умолчанию магазин не активен
                .build();

        // 4. Сохраняем в БД
        Shop saved = shopRepository.save(shop);
        log.info("Магазин создан: shopId={}, userId={}", saved.getId(), user.getId());

        return saved;
    }

    /**
     * Сохранить магазин (обновить существующий).
     */
    public Shop save(Shop shop) {
        log.debug("Сохранение магазина: shopId={}", shop.getId());
        return shopRepository.save(shop);
    }
}
```

---

# СТРОКИ 1–11 — вводная

```java
package org.example.flower_delivery.service;
```

- Мы в пакете `service` — тут живут сервисы, т.е. бизнес‑логика.

Импорты:

```java
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.model.User;
import org.example.flower_delivery.repository.ShopRepository;
import org.example.flower_delivery.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
```

По смыслу:

- `Shop` / `User` — сущности, с которыми работаем.
- `ShopRepository` / `UserRepository` — доступ к БД.
- `@Service` — пометка Spring: "это сервисный слой".
- `@Transactional` — все методы выполняются в транзакции:
  - если всё ок — изменения коммитятся,
  - если где‑то бросили исключение — откатываются.

Lombok:

- `@RequiredArgsConstructor` — генерит конструктор с `final` полями.
- `@Slf4j` — даёт нам поле `log` для логирования.

---

# СТРОКИ 17–24 — комментарий к классу

```java
/**
 * Сервис для работы с магазинами (Shop).
 *
 * Здесь живёт бизнес-логика:
 * - поиск магазина по пользователю / telegramId
 * - создание нового магазина
 * - активация магазина и т.д.
 */
```

Кратко: всё, что относится к "смыслу работы магазинов", а не к чистым SQL‑запросам,
должно жить здесь, а не в репозитории/хендлере.

---

# СТРОКИ 25–28 — аннотации класса

```java
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ShopService {
```

Разбор:

- `@Service` — Spring создаёт бин `shopService`, который можно инжектить в другие классы.
- `@RequiredArgsConstructor` — создаст конструктор с параметрами для всех `final` полей:

  ```java
  private final ShopRepository shopRepository;
  private final UserRepository userRepository;
  ```

  → Spring сможет заинжектить репозитории автоматически.

- `@Slf4j` — даёт логгер `log`:
  - `log.debug(...)`
  - `log.info(...)`

- `@Transactional` — все публичные методы сервиса обёрнуты в транзакцию.

---

# ПОЛЯ СЕРВИСА (строки 31–32)

```java
private final ShopRepository shopRepository;
private final UserRepository userRepository;
```

- `shopRepository` — ходим в таблицу `shops`.
- `userRepository` — ходим в таблицу `users` (например, чтобы найти `User` по `telegramId`).

Оба `final` — т.е. инициализируются один раз через конструктор (который сгенерит Lombok).

---

# МЕТОД 1: `findByUser` (строки 35–40)

```java
public Optional<Shop> findByUser(User user) {
    log.debug("Поиск магазина по пользователю: userId={}", user.getId());
    return shopRepository.findByUser(user);
}
```

Смысл:

- В лог пишем, кого ищем (`userId`).
- Просто делегируем вызов в репозиторий.

Почему это вообще в сервисе, а не сразу в хендлере?

- Сегодня это просто "делегировать".
- Завтра сюда можно добавить:
  - кэширование,
  - какие‑то проверки,
  - метрики,
  - права доступа.

И хендлерам не придётся переписывать код — они просто вызывают сервис.

---

# МЕТОД 2: `findByUserId` (строки 43–49)

```java
public Optional<Shop>  findByUserId(UUID userId){
    log.debug("Поиск магазина по userId={}", userId);
    return shopRepository.findByUserId(userId);
}
```

То же самое, только у нас на руках не объект `User`, а его `UUID`.

Где это может использоваться:

- из админской части, где мы работаем по ID,
- из каких‑то фоновых задач.

---

# МЕТОД 3: `findByUserTelegramId` (строки 52–57)

```java
public Optional<Shop> findByUserTelegramId(Long telegramId){
    log.debug("Поиск магазина по telegramId={}", telegramId);
    return shopRepository.findByUserTelegramId(telegramId);
}
```

Типичный сценарий для бота:

- у тебя есть только `telegramId` (`update.getMessage().getFrom().getId()`),
- тебе нужно понять, есть ли у этого человека магазин.

Ты не лезешь в репозиторий напрямую — ты говоришь сервису:

```java
var shopOptional = shopService.findByUserTelegramId(telegramId);
```

---

# МЕТОД 4: `createShopForUser` (строки 59–94)

Вот тут уже начинается настоящая бизнес‑логика, не просто "делегировать":

```java
public Shop createShopForUser(Long telegramId,
                              String shopName,
                              String pickupAddress,
                              String phone) {

    log.info("Создание магазина: telegramId={}, shopName={}", telegramId, shopName);

    // 1. Находим пользователя по telegramId
    User user = userRepository.findByTelegramId(telegramId)
            .orElseThrow(() -> new IllegalStateException("Пользователь не найден для telegramId=" + telegramId));

    // 2. Проверяем, что у него ещё нет магазина
    shopRepository.findByUser(user).ifPresent(existing -> {
        throw new IllegalStateException("У пользователя уже есть магазин");
    });

    // 3. Собираем сущность Shop
    Shop shop = Shop.builder()
            .user(user)
            .shopName(shopName)
            .pickupAddress(pickupAddress)
            .phone(phone)
            .isActive(false) // по умолчанию магазин не активен
            .build();

    // 4. Сохраняем в БД
    Shop saved = shopRepository.save(shop);
    log.info("Магазин создан: shopId={}, userId={}", saved.getId(), user.getId());

    return saved;
}
```

Разберём по шагам.

### Шаг 0 — входные данные

Метод принимает:

- `telegramId` — по нему находим `User`.
- `shopName` — имя магазина (из регистрации).
- `pickupAddress` — адрес забора.
- `phone` — телефон магазина.

Это всё прилетает из `ShopRegistrationHandler`, который общается с юзером в Telegram.

### Шаг 1 — логируем начало

```java
log.info("Создание магазина: telegramId={}, shopName={}", telegramId, shopName);
```

В лог попадёт что‑то типа:

```text
Создание магазина: telegramId=642867793, shopName=Цветы у подъезда
```

Это потом очень помогает, когда ты разбираешься, кто что создавал.

### Шаг 2 — находим `User` по `telegramId`

```java
User user = userRepository.findByTelegramId(telegramId)
        .orElseThrow(() -> new IllegalStateException("Пользователь не найден для telegramId=" + telegramId));
```

Перевод на человеческий:

- "Попробуй найти пользователя с таким `telegramId`".
- Если нашёл — отлично, берём его.
- Если НЕ нашёл — кидаем исключение `IllegalStateException`:
  - это значит "логика в некорректном состоянии":
  - мы не должны пытаться создать магазин для несуществующего юзера.

`orElseThrow(...)` — это способ работы с `Optional`:

- `Optional` пуст → кинь указанное исключение.

### Шаг 3 — проверяем, нет ли уже магазина

```java
shopRepository.findByUser(user).ifPresent(existing -> {
    throw new IllegalStateException("У пользователя уже есть магазин");
});
```

Логика:

- "Попробуй найти магазин для этого `User`".
- Если нашли (`ifPresent`):
  - немедленно кидаем исключение `"У пользователя уже есть магазин"`.

Зачем так строго?

- По ТЗ: один юзер → один магазин.
- Это бизнес‑правило, и оно должно быть в **сервисе**, а не размазано по хендлерам.

Если бы мы НЕ проверяли:

- юзер мог бы через баг/глючный клиент создать себе второй магазин,
- потом бы всё ломалось при показе меню и т.д.

### Шаг 4 — собираем объект `Shop`

```java
Shop shop = Shop.builder()
        .user(user)
        .shopName(shopName)
        .pickupAddress(pickupAddress)
        .phone(phone)
        .isActive(false) // по умолчанию магазин не активен
        .build();
```

Тут используется Lombok‑Builder:

- Более наглядно, чем длинный конструктор:

```java
new Shop(null, user, shopName, pickupAddress, ..., false, null, null)
```

Мы явно читаем:

- чей пользователь,
- как зовут магазин,
- какой адрес,
- какой телефон,
- `isActive(false)` — важный бизнес‑момент:
  - только что зарегистрированный магазин = ещё не активен,
  - пока админ (или временная команда `/r`) не активирует.

Координаты (`latitude` / `longitude`) пока не задаём — по комментариям это "позже, с геокодингом".

### Шаг 5 — сохраняем в БД

```java
Shop saved = shopRepository.save(shop);
log.info("Магазин создан: shopId={}, userId={}", saved.getId(), user.getId());

return saved;
```

- `save(shop)`:
  - если `id == null` → `INSERT`,
  - если `id != null` → `UPDATE`.
- `saved` — уже с заполненными:
  - `id` (UUID из БД),
  - `createdAt`,
  - `updatedAt`.

Лог пишем с конкретным `shopId`, чтобы потом можно было в БД найти запись.

---

# МЕТОД 5: `save` (строки 96–102)

```java
public Shop save(Shop shop) {
    log.debug("Сохранение магазина: shopId={}", shop.getId());
    return shopRepository.save(shop);
}
```

Универсальный метод "сохранить изменения":

- Можно использовать когда:
  - админ меняет флаг активности,
  - обновляются координаты после геокодинга,
  - меняем телефон магазина и т.д.

Опять же:

- логируем операцию,
- через сервис → репозиторий → БД.

---

## Архитектурный вывод

`ShopService` — это:

- НЕ Telegram‑код,
- НЕ SQL‑код,
- а "мозг" по работе с магазинами.

Он:

- знает, что:
  - магазин нельзя создать без существующего пользователя,
  - у пользователя может быть только один магазин,
  - новый магазин стартует неактивным,
- отвечает за логи, проверки и общую целостность.

---

## Что дальше разбирать

По линии "магазины" у тебя уже есть:

1. `01_User_model.md`
2. `02_Shop_model.md`
3. `03_Shop_repository.md`
4. `04_Shop_service.md` (этот файл)

Дальше логично перейти к **боту и хендлерам**, которые этим пользуются:

- `ShopRegistrationHandler.java` — пошаговая регистрация магазина через Telegram.
- Куски `Bot.java`, которые:
  - маршрутизируют апдейты,
  - вызывают `ShopService` и потом показывают меню магазина.

Если хочешь — следующим сделаем `05_ShopRegistrationHandler.md` и разберём,
как именно бот шаг за шагом регистрирует магазин, пользуясь этим сервисом.

