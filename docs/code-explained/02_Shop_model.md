# ТОТАЛЬНЫЙ РАЗБОР: Shop.java
## Для тех, кто вообще нихуя не понимает

> **Уровень**: "User вроде понял, давай следующую жертву"  
> **Цель**: Осознать, как в проекте устроен МАГАЗИН на уровне кода и БД  
> **Подход**: Как будто объясняю пьяному другу в 3 ночи, который только что создал ООО "Тюльпан и Пиво"

---

## Зачем вообще этот `Shop.java` существует

У нас есть:

- `User` — базовый пользователь (телеграм, роль, активен/не активен)
- `Shop` — магазин, который *принадлежит* какому‑то `User`

В БД это две таблицы:

- `users` — все пользователи
- `shops` — магазины

Один `User` → максимум один `Shop`.  
То есть один телеграм‑аккаунт = один магазин.

`Shop.java` — это Java‑представление таблицы `shops`.
Hibernate/JPA по этому классу понимают:

- какую таблицу читать/писать,
- какие там есть колонки,
- как они связаны с `User`.

Дальше пойдём **строка за строкой**, но чуть компактнее, чем в разборе `User`,
потому что ты уже видел эти конструкции.

---

# СТРОКА 1

```java
package org.example.flower_delivery.model;
```

- `package` — объявляем, в каком "логическом каталоге" живёт этот класс.
- `org.example.flower_delivery.model`:
  - `org.example.flower_delivery` — корень проекта,
  - `.model` — папка "модели", то есть классы, которые описывают данные/таблицы.

Если бы тут написали другой пакет, а файл лежал бы физически не там — IDE начала бы орать,
импорты бы ломались, Spring мог бы вообще не найти сущность.

---

# СТРОКИ 3–10 — импорты

```java
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
```

Разбор по группам:

- `jakarta.persistence.*` — всё, что связано с JPA/ORM:
  - `@Entity`, `@Table`, `@Id`, `@Column`, `@OneToOne` и т.д.
  - Это язык общения Java ↔ SQL.

- `lombok.*` — чтобы не писать руками 500 строк геттеров/сеттеров/конструкторов.

- `CreationTimestamp` / `UpdateTimestamp` — Hibernate‑аннотации, которые сами ставят:
  - когда запись создана (`created_at`),
  - когда обновлена (`updated_at`).

- `BigDecimal` — тип для денег/координат с фиксированной запятой.
- `LocalDateTime` — дата+время без часового пояса.
- `UUID` — уникальный ID магазина.

---

# СТРОКИ 12–21 — комментарий к классу

```java
/**
 * Модель магазина (Shop) — Java-представление таблицы shops.
 * ...
 */
```

Чисто для людей: объяснение, что это именно модель таблицы `shops`,
и что она связана 1:1 с `users`.

---

# СТРОКИ 23–29 — аннотации класса

```java
@Entity
@Table(name = "shops")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shop {
```

Разбор:

- `@Entity` — "ЭТО СУЩНОСТЬ". Hibernate должен её маппить на таблицу.
- `@Table(name = "shops")` — говорим: таблица в БД называется `shops`.

Lombok:

- `@Getter` / `@Setter` — генерят геттеры/сеттеры для ВСЕХ полей.
- `@NoArgsConstructor` — конструктор без аргументов (`new Shop()`).
- `@AllArgsConstructor` — конструктор со всеми полями.
- `@Builder` — паттерн `Shop.builder()...build()`.

`public class Shop` — объявление самого класса.

---

# ПОЛЕ 1: `id` (строки 31–40)

```java
@Id
@GeneratedValue(strategy = GenerationType.UUID)
@Column(name = "id", nullable = false, updatable = false)
private UUID id;
```

- `@Id` — primary key.
- `@GeneratedValue(UUID)` — Hibernate сам генерит UUID.
- `@Column`:
  - `name = "id"` — имя колонки в таблице `shops`.
  - `nullable = false` — не может быть `NULL`.
  - `updatable = false` — после создания не меняется.

Тип: `UUID` — глобально уникальный идентификатор.

Если попытаться самовольно менять id у существующей записи — можно словить кучу багов,
поэтому `updatable = false` — защита от рукожопства.

---

# ПОЛЕ 2: `user` (строки 43–55)

```java
@OneToOne(fetch = FetchType.LAZY)
@JoinColumn(
        name = "user_id",           // имя колонки в таблице shops
        nullable = false,           // NOT NULL
        unique = true               // один user = один shop (1:1)
)
private User user;
```

Это СВЯЗЬ с таблицей `users`.

- `@OneToOne` — тип связи:
  - Один `User` ↔ Один `Shop`.
- `fetch = FetchType.LAZY` — лениво грузим `User`:
  - пока ты не дернешь `shop.getUser()`, Hibernate не пойдёт в БД за `users`.

- `@JoinColumn`:
  - `name = "user_id"` — колонка в `shops`, которая указывает на `users.id`.
  - `nullable = false` — магазин не может существовать без юзера.
  - `unique = true` — один `user_id` → только один магазин.

`private User user;` — тут лежит сам объект `User` (под капотом — foreign key в БД).

ASCII‑связь:

```text
users.id  <----  shops.user_id
   |                   ^
   | 1:1               |
   +-------------------+
```

---

# ПОЛЕ 3: `shopName` (строки 57–65)

```java
@Column(name = "shop_name", nullable = false, length = 255)
private String shopName;
```

Название магазина:

- `nullable = false` — без имени в белый список не попадаем.
- `length = 255` — ограничение длины в БД.

Примеры значений:

- `"Цветы на Ленина"`
- `"Букетная №7"`

---

# ПОЛЕ 4: `pickupAddress` (строки 67–75)

```java
@Column(name = "pickup_address", nullable = false, length = 500)
private String pickupAddress;
```

Адрес, ОТКУДА курьер забирает заказы.

- Обязательно (`nullable = false`),
- До 500 символов (чтобы влезли подъезд, этаж, "вход со двора" и прочая боль).

Потом этот адрес:

- геокодируется в координаты (`latitude`/`longitude`),
- используется как стартовая точка маршрута.

---

# ПОЛЯ 5–6: `latitude` и `longitude` (строки 77–93)

```java
@Column(name = "latitude", precision = 10, scale = 8)
private BigDecimal latitude;

@Column(name = "longitude", precision = 11, scale = 8)
private BigDecimal longitude;
```

Это координаты точки забора (магазина):

- `latitude` — широта,
- `longitude` — долгота.

Почему `BigDecimal`, а не `double`?

- `double` плавает как пьяный — есть ошибки округления.
- Для координат и денег нам нужна аккуратность.

`precision` и `scale`:

- `precision = 10, scale = 8`:
  - всего до 10 цифр,
  - 8 после запятой → формат типа `xx.xxxxxxxx`.

Могут быть `NULL`, если мы ещё не геокодировали адрес.

---

# ПОЛЕ 7: `phone` (строки 95–101)

```java
@Column(name = "phone", length = 50)
private String phone;
```

Телефон магазина:

- НЕ обязательно (`nullable` по умолчанию `true`),
- можно оставить пустым,
- максимум 50 символов.

Отличия:

- У пользователя (`User`) может быть свой телефон.
- У магазина (`Shop`) — свой телефон (например, стационарный магазина).

---

# ПОЛЕ 8: `isActive` (строки 103–111)

```java
@Column(name = "is_active", nullable = false)
@Builder.Default
private Boolean isActive = false;
```

Флаг активности магазина:

- `false` — зарегистрирован, но админ ещё не активировал:
  - не может создавать заказы,
  - видит, что "ожидает активации".
- `true` — можно работать:
  - кнопки "Создать заказ", "Мои заказы" и т.д.

`@Builder.Default` + `= false`:

- если создаём через `Shop.builder()...build()`, а флаг не указали — будет `false` по умолчанию.

---

# ПОЛЯ 9–10: `createdAt`, `updatedAt` (строки 113–129)

```java
@CreationTimestamp
@Column(name = "created_at", nullable = false, updatable = false)
private LocalDateTime createdAt;

@UpdateTimestamp
@Column(name = "updated_at", nullable = false)
private LocalDateTime updatedAt;
```

Технические поля:

- `created_at` — когда этот магазин появился в системе.
  - `@CreationTimestamp` — Hibernate сам ставит текущую дату при `INSERT`.
  - `updatable = false` — потом руками не меняем.

- `updated_at` — когда последний раз меняли запись.
  - `@UpdateTimestamp` — Hibernate сам обновляет при `UPDATE`.

Это удобно:

- для логов,
- для отладки,
- для всякой статистики ("сколько активных магазинов за неделю").

---

# Визуальная схема `Shop`

```text
users
+-------------------------------+
| id (UUID PK)                  |
| telegram_id                   |
| full_name                     |
| role (SHOP/COURIER/ADMIN)    |
| is_active                     |
+-------------------------------+
             1
             |
             | 1:1
             v
shops
+--------------------------------------------+
| id (UUID PK)                              |
| user_id (FK -> users.id, UNIQUE)         |
| shop_name                                 |
| pickup_address                            |
| latitude / longitude                      |
| phone                                     |
| is_active                                 |
| created_at                                |
| updated_at                                |
+--------------------------------------------+
```

---

# Что ты должен вынести из этого файла

- `Shop` = магазин, привязанный 1:1 к `User`.
- В `Shop` лежат:
  - название,
  - адрес забора,
  - координаты этого адреса,
  - телефон магазина,
  - активен он или ещё в ожидании,
  - когда создан/обновлён.
- Всё это напрямую маппится на таблицу `shops` в БД.

---

# Что дальше читать

Логическая цепочка по магазинам:

1. `Shop.java` — ты уже понял (эти данные вообще что?).
2. Дальше логично:
   - `ShopRepository.java` — как мы **лезем в БД** за магазинами.
   - `ShopService.java` — бизнес‑логика магазинов (создание, поиск, активация).
   - `ShopRegistrationHandler.java` — сценарий регистрации магазина в Telegram.

Если хочешь, следующим файлом в этой же папке `code-explained` можем сделать:

- `03_ShopRepository.md` — разбор репозитория,
- или `03_ShopService.md` — разбор сервиса.

Напиши, с чего по магазину тебе интереснее продолжить — с БД или с логики.

