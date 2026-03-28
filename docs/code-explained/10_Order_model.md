# ТОТАЛЬНЫЙ РАЗБОР: Order.java
## Как система видит заказ: от магазина до мультиадреса

> **Уровень**: ты пьяный/накуренный, смотришь на `Order.java` и видишь просто кашу.  
> Наша задача — превратить кашу в чёткую картину: что за поля, связи, статусы,  
> и куда потом всё это улетает.

---

## 0. Что вообще такое `Order.java`

Этот класс — **Java‑представление таблицы `orders`** в БД.

В нём хранится **одна заявка** магазина на доставку:

- кто магазин (`shop`),
- кто курьер (`courier`),
- кому везём (`recipientName`/`recipientPhone`),
- куда везём (`deliveryAddress`, координаты),
- за сколько (`deliveryPrice`),
- какого числа (`deliveryDate`),
- в каком статусе (`status` = NEW / DELIVERED / CANCELLED ...),
- если мультиадрес — список точек (`stops`).

Магазинов может быть много, заказов — ещё больше,  
каждый заказ — одна строка в таблице `orders`,  
и каждый такой объект `Order` в Java = одна такая строка.

Сейчас пойдём **строка за строкой** по коду.

---

## 1. Аннотации класса

### Код

```java
@Entity
@Table(name = "orders")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class Order {
```

### Смысл

- `@Entity` — говорим Hibernate:  
  **"это сущность, мапь её на таблицу"**.

- `@Table(name = "orders")` — явно указываем название таблицы в БД:
  - без этого он бы пытался угадать (`order`, `ORDER` и т.д.).

- `@Getter` / `@Setter`:
  - Lombok генерит все `getId()`, `setId(...)`, `getShop()`, `setShop(...)` и т.д.,
  - тебе не нужно писать 100500 геттеров/сеттеров руками.

- `@AllArgsConstructor` / `@NoArgsConstructor`:
  - конструктор со всеми полями,
  - конструктор без аргументов — нужен Hibernate’у.

- `@Builder`:
  - даёт статический метод `Order.builder()`:

    ```java
    Order order = Order.builder()
        .shop(shop)
        .recipientName("Анна")
        ...
        .build();
    ```

  - так гораздо удобнее собирать объект, чем через конструктор с 20 параметрами.

`public class Order` — сам класс, с которым мы будем работать в коде.

---

## 2. Поле `id`: уникальный идентификатор заказа

### Код

```java
@Id
@GeneratedValue(strategy = GenerationType.UUID)
@Column(name = "id", nullable = false, updatable = false)
private UUID id;
```

### Разбор

- `UUID id`:
  - `UUID` — "универсальный уникальный идентификатор":
    - строка вида `550e8400-e29b-41d4-a716-446655440000`,
    - вероятность коллизии ≈ как выиграть в лотерею, будучи мёртвым.
  - `id` — имя поля, наш primary key.

- `@Id`:
  - помечаем это поле как **primary key** сущности.

- `@GeneratedValue(strategy = GenerationType.UUID)`:
  - говорим Hibernate: "генерь мне UUID сам, я не буду руками".

- `@Column(name = "id", nullable = false, updatable = false)`:
  - `name = "id"` — имя колонки в таблице `orders`.
  - `nullable = false` — в БД не может быть `NULL`.
  - `updatable = false` — после создания его нельзя менять.

Аналогия:

- `id` — это как номер заказа на кухне в Макдаке:
  - уникален,
  - появляется автоматически,
  - никто потом не переписывает "ваш номер был 5, стал 7".

---

## 3. Поле `shop`: какой магазин создал заказ

### Код

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "shop_id", nullable = false)
private Shop shop;
```

### Разбор

- `Shop shop`:
  - тип `Shop` — твой класс из `Shop.java`,
  - поле `shop` — ссылка **какой магазин** создал этот заказ.

- `@ManyToOne`:
  - много заказов → один магазин.
  - т.е. в таблице `orders` будет куча строк, где `shop_id` одинаковый.

- `fetch = FetchType.LAZY`:
  - когда мы загружаем `Order` из БД, Hibernate:
    - **НЕ** тянет `Shop` сразу,
    - тащит "прокси" и реально достаёт магазин только когда кто‑то вызовет `order.getShop()`.
  - иначе каждый запрос заказов тащил бы за собой все магазины.

- `@JoinColumn(name = "shop_id", nullable = false)`:
  - в таблице `orders` есть колонка `shop_id`,
  - она хранит `shops.id`,
  - `nullable = false` → заказ без магазина невозможен.

ASCII‑схема:

```text
shops
  id (UUID)  ←───+
                  \
                   \
                    +── orders.shop_id (много строк)
```

---

## 4. Поле `courier`: кто выполняет заказ

### Код

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "courier_id")
private User courier;
```

### Разбор

- `User courier`:
  - тип `User` — базовый пользователь (из `User.java`),
  - может быть `null`, если заказ ещё никем не взят.

- `@ManyToOne`:
  - один курьер (`User` с ролью `COURIER`) может выполнить **много заказов**,
  - но один заказ — максимум один курьер.

- `@JoinColumn(name = "courier_id")`:
  - в таблице `orders` есть колонка `courier_id`,
  - foreign key на `users.id`,
  - `nullable` по умолчанию `true` → пока ни кем не взят.

---

## 5. Информация о получателе

### Имя

```java
@Column(name = "recipient_name", nullable = false, length = 255)
private String recipientName;
```

- `recipientName` — как зовут человека, кому везут:
  - `"Анна"`,
  - `"Мария Ивановна"`.
- `nullable = false` — без имени жить можно, но бизнес хочет, чтобы было.
- `length = 255` — ограничение в БД.

### Телефон

```java
@Column(name = "recipient_phone", nullable = false, length = 50)
private String recipientPhone;
```

- телефон получателя:
  - курьер звонит сюда, когда подъехал.
- `length = 50` — с запасом на `+7`, пробелы, скобки.

### Адрес доставки

```java
@Column(name = "delivery_address", nullable = false, length = 500)
private String deliveryAddress;
```

- текстовый адрес:
  - "ул. Ленина, 15, подъезд 3, домофон 123".
- используется:
  - для отображения человеку,
  - как сырьё для геокодинга → координаты.

---

## 6. Координаты точки доставки

### Широта

```java
@Column(name = "delivery_latitude", precision = 10, scale = 8)
private BigDecimal deliveryLatitude;
```

- `BigDecimal` вместо `double`:
  - меньше проблем с плавающей запятой.
- `precision = 10, scale = 8`:
  - всего до 10 цифр,
  - из них 8 после запятой:
    - формат `XX.XXXXXXXX`.
- Может быть `null`, если адрес ещё не геокодировали.

### Долгота

```java
@Column(name = "delivery_longitude", precision = 11, scale = 8)
private BigDecimal deliveryLongitude;
```

- чуть больше разрядов до запятой (11), но тоже 8 после запятой.
- тоже может быть `null`.

Эта пара используется для:

- расчёта расстояния (через OSRM),
- построения маршрутов,
- генерации ссылок на карты.

---

## 7. Стоимость доставки

### Код

```java
@Column(name =  "delivery_price",  nullable = false, precision = 10, scale = 2)
private BigDecimal deliveryPrice;
```

### Разбор

- `BigDecimal deliveryPrice` — деньги.  
  В Java все нормальные люди делают деньги через `BigDecimal`, а не `double`.

- `precision = 10, scale = 2`:
  - до 10 цифр, из них 2 после запятой:
    - максимум `99999999.99`.

- `nullable = false` — цена должна быть всегда:
  - или введена руками,
  - или посчитана по дистанции.

---

## 8. Комментарий к заказу

```java
@Column(name = "comment", columnDefinition = "TEXT")
private String comment;
```

- всё, что магазин захочет дописать:
  - "домофон 123",
  - "позвонить за 30 минут",
  - "не говорить, что это от мужа".
- `TEXT` — тип в БД: почти безлимитная строка.

---

## 9. Статус заказа

### Код

```java
@Enumerated(EnumType.STRING)
@Column(name = "status", nullable = false, length = 20)
@Builder.Default
private OrderStatus status = OrderStatus.NEW;
```

### Разбор

- `OrderStatus` — твой enum с состояниями:
  - `NEW`, `ACCEPTED`, `IN_SHOP`, `PICKED_UP`, `ON_WAY`, `DELIVERED`, `RETURNED`, `CANCELLED`, etc.

- `@Enumerated(EnumType.STRING)`:
  - говорит JPA:
    - "сохраняй enum как строку в БД",
    - т.е. в БД будет `"NEW"`, `"DELIVERED"`, а не `0`, `1`.
  - Если бы было `ORDINAL` (цифры) и ты вставил новый статус в середину — всё поехало бы.

- `@Column(..., length = 20)`:
  - длины 20 символов хватает на `"RETURNED"` и прочее.

- `@Builder.Default` + `= OrderStatus.NEW`:
  - при создании через `Order.builder()`:

    ```java
    Order.builder()
         .shop(shop)
         ...
         .build();
    ```

    статус по умолчанию будет `NEW`, если ты явно не указал другой.

---

## 10. Дата доставки

```java
@Column(name = "delivery_date", nullable = false)
private LocalDate deliveryDate;
```

- `LocalDate` — **дата без времени** (`2026-02-10`):
  - день, когда планируется доставка (сегодня / завтра).
- Почему без времени:
  - в ТЗ у тебя сейчас нет точного слота времени,
  - для MVP хватит "сегодня/завтра", потом можно расширить.

---

## 11. Временные метки: когда что произошло

### createdAt / updatedAt

```java
@CreationTimestamp
@Column(name = "created_at", nullable = false, updatable = false)
private LocalDateTime createdAt;

@UpdateTimestamp
@Column(name = "updated_at", nullable = false)
private LocalDateTime updatedAt;
```

- `createdAt`:
  - ставится Hibernate автоматически при создании строки,
  - `updatable = false` — нельзя перезаписать.

- `updatedAt`:
  - автоматически обновляется при каждом изменении строки.

Используется:

- для логов,
- для статистики ("сколько заказов за день/неделю"),
- для отладки.

### acceptedAt / pickedUpAt / deliveredAt

```java
@Column(name = "accepted_at")
private LocalDateTime acceptedAt;

@Column(name = "picked_up_at")
private LocalDateTime pickedUpAt;

@Column(name = "delivered_at")
private LocalDateTime deliveredAt;
```

- `acceptedAt`:
  - когда курьер взял заказ.
- `pickedUpAt`:
  - когда забрал из магазина.
- `deliveredAt`:
  - когда отдал получателю.

Все три могут быть `NULL`, пока это действие не произошло.

---

## 12. Флаги мультиадреса

### Код

```java
@Column(name = "is_multi_stop", nullable = false)
@Builder.Default
private Boolean isMultiStop = false;

@Column(name = "total_stops", nullable = false)
@Builder.Default
private Integer totalStops = 1;
```

### Смысл

- `isMultiStop`:
  - `false` → обычный заказ в одну точку,
  - `true` → есть несколько точек (`stops.size() > 1`).

- `totalStops`:
  - сколько точек всего (для мультиадреса),
  - по умолчанию 1.

Эти поля дублируют инфу из `stops`, но:

- с ними удобнее строить простые запросы в БД:
  - "дай мне все мультиадресные заказы",
  - "сколько точек в заказе".

---

## 13. Список точек доставки (`stops`)

### Код

```java
@OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
@OrderBy("stopNumber ASC")
@Builder.Default
private List<OrderStop> stops = new ArrayList<>();
```

### Разбор

- `List<OrderStop> stops`:
  - список объектов `OrderStop`,
  - каждая `OrderStop` — отдельная точка маршрута:
    - адрес,
    - свой получатель,
    - цена,
    - статус этой точки.

- `@OneToMany(mappedBy = "order", ...)`:
  - "у одного заказа — много точек".
  - `mappedBy = "order"`:
    - связь описана в классе `OrderStop` в поле `order`:

      ```java
      @ManyToOne
      @JoinColumn(name = "order_id")
      private Order order;
      ```

    - т.е. foreign key (`order_id`) физически лежит в таблице `order_stops`.

- `cascade = CascadeType.ALL`:
  - если ты `save`/`delete` заказ,
  - Hibernate автоматически применяет это и к `stops`:
    - удалишь заказ → удалятся и все его точки.

- `orphanRemoval = true`:
  - если точку убрать из списка `stops` и сохранить заказ,
  - то и в БД соответствующая строка `order_stops` будет удалена.

- `@OrderBy("stopNumber ASC")`:
  - при загрузке `stops` из БД, сортируем по полю `stopNumber` по возрастанию,
  - т.е. список в Java уже сразу в порядке маршрута.

- `@Builder.Default stops = new ArrayList<>()`:
  - даже при `Order.builder().build()` у тебя не будет `null`,
  - будет пустой список, с которым безопасно работать.

---

## 14. Вспомогательные методы

### `hasCourier()`

```java
public boolean hasCourier() {
    return courier != null;
}
```

- Просто отвечает: назначен ли уже курьер.

### `isAvailable()`

```java
public boolean isAvailable() {
    return status == OrderStatus.NEW;
}
```

- заказ можно "взять", только если статус `NEW`.

### `isCompleted()`

```java
public boolean isCompleted() {
    return status == OrderStatus.DELIVERED ||
            status == OrderStatus.RETURNED ||
            status == OrderStatus.CANCELLED;
}
```

- законченный заказ:
  - доставлен,
  - возвращён,
  - или отменён.

### `getDeadline()` и `isOverdue()`

```java
public LocalDateTime getDeadline() {
    if (acceptedAt == null) {
        return null;
    }
    return acceptedAt.plusMinutes(30);
}

public boolean isOverdue() {
    LocalDateTime deadline = getDeadline();
    if (deadline == null) {
        return false;
    }
    return LocalDateTime.now().isAfter(deadline);
}
```

- `getDeadline()`:
  - если заказ ещё не принят (`acceptedAt == null`) → дедлайна нет.
  - иначе → `acceptedAt + 30 минут`.
- `isOverdue()`:
  - если дедлайна нет → не просрочен.
  - если есть → сравниваем с `LocalDateTime.now()`.

Это можно использовать потом для подсветки просроченных заказов.

### Мультиадресные методы

#### `isMultiStopOrder()`

```java
public boolean isMultiStopOrder() {
    return Boolean.TRUE.equals(isMultiStop) || totalStops > 1;
}
```

- на всякий случай проверяем и флаг, и количество точек.

#### `addStop(OrderStop stop)`

```java
public void addStop(OrderStop stop) {
    stops.add(stop);
    stop.setOrder(this);
    this.totalStops = stops.size();
    this.isMultiStop = stops.size() > 1;
}
```

- добавляем точку в список,
- выставляем у точки `order = this`,
- обновляем счётчики:
  - `totalStops`,
  - `isMultiStop`.

#### `getTotalDeliveryPrice()`

```java
public BigDecimal getTotalDeliveryPrice() {
    if (stops == null || stops.isEmpty()) {
        return deliveryPrice;
    }
    return stops.stream()
            .map(OrderStop::getDeliveryPrice)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
}
```

- если нет точек — возвращаем обычную `deliveryPrice`.
- если есть — складываем цены по всем точкам.

#### `areAllStopsDelivered()`

```java
public boolean areAllStopsDelivered() {
    if (stops == null || stops.isEmpty()) {
        return status == OrderStatus.DELIVERED;
    }
    return stops.stream().allMatch(OrderStop::isDelivered);
}
```

- если нет точек — просто смотрим статус заказа.
- если есть — проверяем, что каждая точка помечена как доставленная.

#### `getNextPendingStop()`

```java
public java.util.Optional<OrderStop> getNextPendingStop() {
    if (stops == null) {
        return java.util.Optional.empty();
    }
    return stops.stream()
            .filter(s -> s.getStopStatus() == StopStatus.PENDING)
            .findFirst();
}
```

- ищем первую точку, у которой статус `PENDING` (ждёт доставки).

#### `getRouteDescription()`

```java
public String getRouteDescription() {
    if (stops == null || stops.isEmpty()) {
        return deliveryAddress;
    }
    return stops.stream()
            .map(OrderStop::getDeliveryAddress)
            .reduce((a, b) -> a + " → " + b)
            .orElse(deliveryAddress);
}
```

- если нет точек — маршрут = один `deliveryAddress`.
- если есть — склеиваем адреса вида:

  ```
  "Ленина 15 → Ленина 17 → Труда 10"
  ```

---

## 15. Итоговая схема `Order`

ASCII:

```text
users (курьеры)
  id (UUID)
   ^
   | many-to-one
   |
orders
  id (UUID PK)
  shop_id (FK → shops.id)
  courier_id (FK → users.id, может быть NULL)
  recipient_name
  recipient_phone
  delivery_address
  delivery_latitude / delivery_longitude
  delivery_price
  comment
  status (NEW / ...)
  delivery_date
  created_at / updated_at
  accepted_at / picked_up_at / delivered_at
  is_multi_stop
  total_stops
   |
   | one-to-many (mappedBy = order)
   v
order_stops
  id (UUID)
  order_id (FK → orders.id)
  stop_number
  delivery_address
  delivery_price
  stop_status (PENDING / DELIVERED / ...)
```

---

## Что дальше

Про заказы логично дальше разобрать:

- `OrderStop.java` — модель одной точки маршрута,
- `OrderStatus.java` / `StopStatus.java` — enum’ы статусов,
- `OrderService` / `OrderRepository` — как мы эти `Order` ищем/меняем,
- `OrderCreationHandler` — как бот ведёт диалог по созданию заказа (всё то же, что с регистрациями, но глубже).

Их можно оформить как:

- `11_OrderStop_model.md`,
- `12_Order_service.md`,
- `13_OrderCreationHandler.md`,

в таком же стиле: "каждое слово → объяснение, зачем оно и что будет, если убрать".

