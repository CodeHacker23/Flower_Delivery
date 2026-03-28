# ТОТАЛЬНЫЙ РАЗБОР: ShopRepository.java
## Как сходить в БД и не написать ни одного SQL-а

> **Уровень**: "Я уже понял модели, теперь хочу понять, кто с ними трахает базу"  
> **Цель**: Осознать, как репозиторий превращает обычные методы Java в SQL‑запросы  
> **Стиль**: Жёстко, по‑делу, с матами, но так, чтобы потом можно было это объяснить на собесе

---

## Зачем вообще нужен репозиторий

У нас есть таблица `shops` и модель `Shop.java`.

Можно было бы каждый раз руками писать:

```sql
SELECT * FROM shops WHERE user_id = ...;
INSERT INTO shops (...) VALUES (...);
UPDATE shops SET ... WHERE id = ...;
```

Но это:

- боль,
- копипаста,
- риск SQL‑инъекций,
- тысяча однотипных кусков кода.

Spring Data JPA говорит:  
**"Не пиши SQL, напиши нормальный Java‑метод, а я угадаю SQL за тебя"**.

`ShopRepository` — это интерфейс, через который мы:

- ищем магазин по юзеру,
- ищем по Telegram ID юзера,
- достаём все активные магазины.

И ВСЁ ЭТО — без единого явного `SELECT` в Java‑коде.

---

## Полный код, чтобы был перед глазами

```java
package org.example.flower_delivery.repository;

import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий для работы с магазинами (Shop).
 *
 * Это как «прослойка» между кодом и таблицей shops:
 * вместо SQL мы пишем методы, а Spring Data JPA сам генерирует запросы.
 */
@Repository
public interface ShopRepository extends JpaRepository<Shop, UUID> {

    /**
     * Найти магазин по пользователю.
     *
     * Один пользователь → один магазин (1:1),
     * поэтому возвращаем Optional<Shop>.
     */
    Optional<Shop> findByUser(User user);

    /**
     * Найти магазин по ID пользователя (users.id).
     */
    Optional<Shop> findByUserId(UUID userId);

    /**
     * Найти магазин по Telegram ID пользователя.
     *
     * Магия Spring Data:
     * user.telegramId → user_TelegramId → findByUserTelegramId(...)
     */
    Optional<Shop> findByUserTelegramId(Long telegramId);

    /**
     * Найти все активные магазины.
     */
    List<Shop> findByIsActiveTrue();
}
```

---

# СТРОКА 1 — пакет

```java
package org.example.flower_delivery.repository;
```

- Мы в папке `repository` — здесь живут классы/интерфейсы, которые общаются с БД.
- Логика примерно такая:
  - `model` — что лежит в БД (структура)
  - `repository` — как туда ходим (CRUD, поиск)
  - `service` — бизнес‑логика (правила, проверки)

---

# СТРОКИ 3–10 — импорты

```java
import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
```

Разбор:

- `Shop` / `User` — наши сущности.
- `JpaRepository` — главный интерфейс Spring Data JPA:
  - даёт готовые методы: `save`, `findById`, `findAll`, `delete` и т.д.
- `@Repository` — аннотация‑маркер:
  - говорит Spring: "Вот тут будет репозиторий, создай для него бин и оборачивай ошибки в DataAccessException".

- `List` — список результатов (когда много магазинов).
- `Optional` — обёртка "может быть значение, а может не быть".
- `UUID` — тип ID магазина/пользователя.

---

# СТРОКИ 12–17 — комментарий

```java
/**
 * Репозиторий для работы с магазинами (Shop).
 *
 * Это как «прослойка» между кодом и таблицей shops:
 * вместо SQL мы пишем методы, а Spring Data JPA сам генерирует запросы.
 */
```

Человеческое объяснение:

- Этот интерфейс = точка входа в таблицу `shops`.
- Мы НЕ пишем `SELECT ...` руками,
  мы пишем методы вида `findBy...`, а фреймворк генерирует SQL.

---

# СТРОКИ 18–19 — объявление интерфейса

```java
@Repository
public interface ShopRepository extends JpaRepository<Shop, UUID> {
```

Разбор по словам:

- `@Repository` — уже разобрали: пометочка для Spring.

- `public` — доступно отовсюду.

- `interface` — это НЕ класс, а интерфейс:
  - сам он кода не содержит,
  - он только объявляет "вот такие методы должны быть".
  - Реализацию подставляет Spring Data JPA автоматически.

- `ShopRepository` — имя интерфейса.

- `extends JpaRepository<Shop, UUID>`:
  - `extends` — "наследуется от".
  - `JpaRepository<Shop, UUID>`:
    - первый параметр: с какой сущностью работаем (`Shop`),
    - второй: тип её ID (`UUID`).

Что нам даёт `JpaRepository<Shop, UUID>` БЕЗ всяких методов:

- `save(Shop entity)`
- `findById(UUID id)`
- `findAll()`
- `deleteById(UUID id)`
- `count()`
- и ещё куча готовых CRUD‑методов.

То есть, даже если бы интерфейс был пустой:

```java
public interface ShopRepository extends JpaRepository<Shop, UUID> {
}
```

— мы уже могли бы сохранять и читать магазины из БД.

---

# МЕТОД 1: `findByUser` (строки 22–28)

```java
Optional<Shop> findByUser(User user);
```

Звучит магически, но это очень простая хрень:

- Имя метода в Spring Data = рецепт SQL‑запроса.

`findByUser` =  
**"Найти по полю `user` в сущности `Shop`"**.

Spring переводит это в SQL примерно такого вида:

```sql
SELECT *
FROM shops
WHERE user_id = :user.id;
```

Почему `Optional<Shop>`?

- Один пользователь → максимум один магазин.
- Может быть:
  - магазин есть → `Optional` с объектом,
  - магазина нет → `Optional.empty()`.

Сценарии использования:

- В `ShopService`:

```java
Optional<Shop> shopOpt = shopRepository.findByUser(user);
```

---

# МЕТОД 2: `findByUserId` (строки 30–33)

```java
Optional<Shop> findByUserId(UUID userId);
```

Тут ещё интереснее.

`userId` — это НЕ поле в классе `Shop`.  
В классе `Shop` поле называется `user`, типа `User`.

Но Spring Data умеет такой финт:

- `findByUserId` = иди в поле `user`, потом его поле `id`.

То есть Java‑путь:

```java
shop.getUser().getId()
```

превращается во внутренний SQL‑путь:

```sql
WHERE user_id = :userId
```

Зачем нужен такой метод, если есть `findByUser(User user)`?

- Иногда у тебя на руках только `UUID userId`, без объекта `User`.
- Чтобы не делать лишний запрос `findById` для `User`, ты можешь сразу пойти в магазины по этому ID.

---

# МЕТОД 3: `findByUserTelegramId` (строки 35–41)

```java
Optional<Shop> findByUserTelegramId(Long telegramId);
```

Вот тут уже начинается та самая "магия имён".

В `Shop` НЕТ поля `userTelegramId`.  
Но есть:

- поле `user` типа `User`,
- а в `User` есть поле `telegramId`.

Spring Data умеет ходить ПО ГРАФУ объектов:

- `findByUserTelegramId` = "зайди в поле `user`, там возьми `telegramId`, по нему фильтруй".

Java‑путь:

```java
shop.getUser().getTelegramId()
```

SQL‑логика:

```sql
SELECT s.*
FROM shops s
JOIN users u ON s.user_id = u.id
WHERE u.telegram_id = :telegramId;
```

То есть:

- Ты пишешь ОДНУ строку Java,
- Spring Data сам собирает JOIN и WHERE.

Это очень удобно:

- у тебя обычно на руках только `telegramId`,
- по нему ты можешь сразу вытащить магазин.

---

# МЕТОД 4: `findByIsActiveTrue` (строки 43–46)

```java
List<Shop> findByIsActiveTrue();
```

В сущности `Shop` есть поле:

```java
private Boolean isActive;
```

Spring Data раскладывает имя метода:

- `findBy` — "найди всё, что подходит".
- `IsActive` — имя поля `isActive`.
- `True` — значение `true`.

SQL‑эквивалент:

```sql
SELECT *
FROM shops
WHERE is_active = true;
```

Возвращаем `List<Shop>` — потому что активных магазинов может быть много.

Пример использования:

```java
List<Shop> activeShops = shopRepository.findByIsActiveTrue();
```

---

## Связь с `ShopService`

В `ShopService` ты увидишь методы уровня:

- "создать магазин для пользователя",
- "найти магазин по telegramId",
- "активировать магазин".

И все они под капотом дергают **вот эти методы** репозитория.

Архитектурно:

```text
Controller/Handler (Telegram)  →  ShopService  →  ShopRepository  →  БД (shops)
```

Репозиторий:

- НИЧЕГО не знает о Telegram,
- НИЧЕГО не знает о бизнес‑логике,
- только ходит в БД.

---

## Что ты должен запомнить про `ShopRepository`

- Это интерфейс (НЕ класс) — реализацию генерит Spring Data.
- `extends JpaRepository<Shop, UUID>` даёт тебе готовый CRUD.
- Дополнительные методы `findBy...` строят SQL по имени метода:
  - `findByUser` → `WHERE user_id = ...`
  - `findByUserId` → то же самое, но без загрузки `User`
  - `findByUserTelegramId` → JOIN с `users` по `telegram_id`
  - `findByIsActiveTrue` → `WHERE is_active = true`

---

## Что дальше разбирать

Логика по магазинам дальше идёт такая:

1. `Shop.java` — структура данных (уже разобрали).
2. `ShopRepository.java` — как достаём/сохраняем (этот файл).
3. **Дальше идеально смотреть `ShopService.java`**:
   - как мы используем репозиторий,
   - какая бизнес‑логика поверх "сырой" БД,
   - как это связывается с регистрацией магазина.

Если хочешь, следующим файлом в `code-explained` сделаем
`04_Shop_service.md` и разберём сервис магазинов в том же стиле.

