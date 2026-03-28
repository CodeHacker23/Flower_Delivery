# ТОТАЛЬНЫЙ РАЗБОР: OrderStop.java
## Одна точка маршрута в мультиадресном заказе

> **Уровень**: "Я вижу OrderStop и у меня в голове только 'что за херня'"  
> **Цель**: понять каждое слово в `OrderStop.java`, чтобы потом самому уметь добавить новые поля/логику без страха.

---

## 0. Что такое `OrderStop` в жизни

У тебя есть заказ (`Order`) с несколькими точками доставки:

- Магазин: "Надо отвезти два букета":
  - Точка 1: `Ленина 15`, 400₽
  - Точка 2: `Ленина 17`, 150₽

В БД:

- есть ОДНА строка в `orders` (сам заказ),
- и ДВЕ строки в `order_stops`:

| id (UUID) | order_id | stop_number | delivery_address | delivery_price | stop_status |
|----------|----------|-------------|------------------|----------------|------------|
| ...      | ORD1     | 1           | Ленина 15        | 400            | PENDING    |
| ...      | ORD1     | 2           | Ленина 17        | 150            | PENDING    |

`OrderStop.java` — это Java‑класс, который описывает **одну такую строку**.

---

## 1. Объявление и аннотации

### Код (без импортов)

```java
@Entity
@Table(name = "order_stops")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderStop {
```

### Разбор

- `@Entity` — "это сущность JPA", будет мапиться на таблицу.
- `@Table(name = "order_stops")` — говорим, что таблица в БД называется `order_stops`.
- `@Getter` / `@Setter` — Lombok сгенерит геттеры/сеттеры для всех полей.
- `@NoArgsConstructor` / `@AllArgsConstructor` — конструктор без аргументов и конструктор со всеми полями.
- `@Builder` — даёт `OrderStop.builder()...build()`.

`public class OrderStop` — основной класс: "одна точка маршрута".

---

## 2. `id`: уникальный идентификатор точки

### Код

```java
@Id
@GeneratedValue(strategy = GenerationType.UUID)
@Column(name = "id", nullable = false, updatable = false)
private UUID id;
```

### Разбор

- `UUID` — уникальный идентификатор, как и у `Order`/`User`/`Shop`.
- `@Id` — primary key.
- `@GeneratedValue(UUID)` — Hibernate сам генерит.
- `@Column(name = "id", nullable = false, updatable = false)`:
  - `nullable = false` — точка без id не существует,
  - `updatable = false` — id не меняется после создания.

В БД это колонка `order_stops.id`.

---

## 3. `order`: к какому заказу относится точка

### Код

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "order_id", nullable = false)
private Order order;
```

### Разбор

- `Order order`:
  - тип `Order` — сущность заказа из `Order.java`,
  - поле `order` — ссылка на "родительский" заказ.

- `@ManyToOne`:
  - много точек (`OrderStop`) → один заказ (`Order`).
  - одна строка в `orders` может иметь много строк в `order_stops`.

- `fetch = FetchType.LAZY`:
  - когда достаём `OrderStop` из БД,
  - сам `Order` не тянется сразу,
  - подгрузится только при `stop.getOrder()`.

- `@JoinColumn(name = "order_id", nullable = false)`:
  - в таблице `order_stops` есть поле `order_id`,
  - это foreign key на `orders.id`,
  - `nullable = false` — точка без заказа невозможна.

ASCII‑схема:

```text
orders
  id (UUID)  <───+
                \
                 +── order_stops.order_id (много строк)
```

---

## 4. `stopNumber`: порядковый номер точки

### Код

```java
@Column(name = "stop_number", nullable = false)
private Integer stopNumber;
```

### Разбор

- `Integer stopNumber`:
  - номер точки в маршруте:
    - `1` — первая точка,
    - `2`, `3`, ... — следующие.

- Зачем:
  - чтобы знать порядок: `1 → 2 → 3`,
  - и чтобы красиво рисовать маршрут (`Ленина 15 → Ленина 17 → ...`),
  - чтобы понимать, где "первая"/"дополнительная".

- Используется в методах:

```java
public boolean isFirstStop() {
    return stopNumber != null && stopNumber == 1;
}

public boolean isAdditionalStop() {
    return stopNumber != null && stopNumber > 1;
}
```

---

## 5. Инфа о получателе

### Имя

```java
@Column(name = "recipient_name", nullable = false, length = 255)
private String recipientName;
```

- Имя получателя **конкретно на этой точке**:
  - потому что в мультиадресе у каждой точки может быть свой человек.

### Телефон

```java
@Column(name = "recipient_phone", nullable = false, length = 50)
private String recipientPhone;
```

- Телефон того, кто живёт по этому адресу.
- Курьер звонит по НЁМУ, когда доезжает до этой точки.

---

## 6. Адрес и координаты точки

### Адрес

```java
@Column(name = "delivery_address", nullable = false, length = 500)
private String deliveryAddress;
```

- Адрес **этой точки**:
  - `"ул. Ленина, д. 15, подъезд 3, кв. 12"`.

### Координаты

```java
@Column(name = "delivery_latitude", precision = 10, scale = 8)
private BigDecimal deliveryLatitude;

@Column(name = "delivery_longitude", precision = 11, scale = 8)
private BigDecimal deliveryLongitude;
```

- `deliveryLatitude` / `deliveryLongitude`:
  - координаты этой точки,
  - используются для расчёта расстояния (через OSRM),
  - могут быть `null`, если адрес ещё не геокодирован.

### Вспомогательный метод `hasCoordinates`

```java
public boolean hasCoordinates() {
    return deliveryLatitude != null && deliveryLongitude != null;
}
```

- Просто "есть ли обе координаты".

---

## 7. Цена и расстояние до точки

### Цена

```java
@Column(name = "delivery_price", nullable = false, precision = 10, scale = 2)
private BigDecimal deliveryPrice;
```

- `deliveryPrice` — **цена ДО этой точки**:
  - для точки 1: цена от магазина до неё,
  - для точки 2: цена за путь от точки 1 до точки 2,
  - и т.д.

То есть итоговая стоимость заказа = сумма всех `deliveryPrice` по списку точек.  
(См. `Order.getTotalDeliveryPrice()`.)

### Расстояние

```java
@Column(name = "distance_km", precision = 6, scale = 2)
private BigDecimal distanceKm;
```

- расстояние до этой точки (в км):
  - для первой — от магазина,
  - для каждой следующей — от предыдущей точки.

- `precision = 6, scale = 2`:
  - максимум, например, `9999.99 км`, с точностью до сотых.

---

## 8. Комментарий к точке

```java
@Column(name = "comment", columnDefinition = "TEXT")
private String comment;
```

- Комментарий, относящийся **именно к этой точке**:
  - "домофон 123, не звонить в двери",
  - "передать через охрану".

---

## 9. Статус точки

### Код

```java
@Enumerated(EnumType.STRING)
@Column(name = "stop_status", nullable = false, length = 20)
@Builder.Default
private StopStatus stopStatus = StopStatus.PENDING;
```

### Разбор

- `StopStatus` — enum статуса точки (отдельный от статуса заказа):
  - например: `PENDING`, `IN_PROGRESS`, `DELIVERED`, `CANCELLED` и т.п.

- `@Enumerated(EnumType.STRING)`:
  - храним enum как строку `"PENDING"`, `"DELIVERED"`, а не числа.

- `@Builder.Default` + `= StopStatus.PENDING`:
  - при создании через `.builder()` поле по умолчанию = `PENDING`:
    - точка только что создана → ждет доставки.

### Методы для работы со статусом

```java
public boolean isDelivered() {
    return stopStatus == StopStatus.DELIVERED;
}

public void markDelivered() {
    this.stopStatus = StopStatus.DELIVERED;
    this.deliveredAt = LocalDateTime.now();
}
```

- `isDelivered()` — проверка, доставлена ли точка.
- `markDelivered()`:
  - ставит статус `DELIVERED`,
  - присваивает `deliveredAt = текущее время`.

---

## 10. Когда точка создана и доставлена

### created_at

```java
@CreationTimestamp
@Column(name = "created_at", nullable = false, updatable = false)
private LocalDateTime createdAt;
```

- `@CreationTimestamp` — Hibernate сам проставит дату создания точки.
- `updatable = false` — потом руками не трогаем.

### delivered_at

```java
@Column(name = "delivered_at")
private LocalDateTime deliveredAt;
```

- время, когда точка была доставлена,
- заполняется в `markDelivered()`:

```java
this.deliveredAt = LocalDateTime.now();
```

---

## 11. Вспомогательные методы: "первая ли это точка", "дополнительная ли"

### `isFirstStop`

```java
public boolean isFirstStop() {
    return stopNumber != null && stopNumber == 1;
}
```

- `stopNumber != null` — защита от `NullPointerException`.
- `stopNumber == 1` — первая (основная) точка:
  - именно её цена обычно считается "базовой" доставкой.

### `isAdditionalStop`

```java
public boolean isAdditionalStop() {
    return stopNumber != null && stopNumber > 1;
}
```

- всё, что с номером > 1 — дополнительные точки.
- можно использовать, чтобы:
  - менять текст,
  - применять другие тарифы,
  - по-разному отображать в интерфейсе.

---

## 12. Итоговая схема `Order` + `OrderStop`

ASCII:

```text
Order (orders)
  id (UUID)           ←─+
  shop_id                \
  courier_id              \
  ...                      \
  is_multi_stop            \
  total_stops               \
                             \
OrderStop (order_stops)       \
  id (UUID)                    \
  order_id  --------------------+
  stop_number
  recipient_name
  recipient_phone
  delivery_address
  delivery_latitude / longitude
  delivery_price
  distance_km
  comment
  stop_status (PENDING/DELIVERED/...)
  delivered_at
  created_at
```

- Один `Order` → много `OrderStop`.
- Вся **сумма** по точкам = общая стоимость доставки.
- Статусы точек (`StopStatus`) и статусы заказа (`OrderStatus`) могут жить своей жизнью:
  - заказ может быть `ON_WAY`, при этом
  - часть точек уже `DELIVERED`, часть ещё `PENDING`.

---

## Что дальше разбирать

Теперь по "линии заказов" у тебя есть:

- `10_Order_model.md` — вся заявка целиком,
- `11_OrderStop_model.md` — одна точка маршрута.

Следующие логичные файлы:

- `OrderStatus.java` / `StopStatus.java` — статусы с расшифровкой по бизнесу,
- `OrderRepository` / `OrderService` — как мы ищем и меняем заказы,
- `OrderCreationHandler` — сценарий диалога "создать заказ",
- `MyOrdersSelectionHandler` — выбор конкретного заказа из списка.

Можно продолжить с **`OrderService`** в стиле "каждая строка, каждый метод, где дергается репозиторий, что означает `findById`, `getOrdersByShop`, как считается мультиадрес" и т.д.  
Скажи, с какого файла по заказам хочешь продолжить, и я разложу его так же подробно. 
