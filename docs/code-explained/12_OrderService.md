# ТОТАЛЬНЫЙ РАЗБОР: OrderService.java
## Как мы создаём, отменяем и правим заказы (особенно мультиадрес)

> **Уровень**: "Я уже понял, что такое Order и OrderStop,  
>  теперь хочу знать, кто всем этим рулит и кто пересчитывает цены/координаты."

---

## 0. Где живёт `OrderService` в архитектуре

Схема по слоям:

```text
Telegram бот (Bot, хендлеры)
    ↓
OrderCreationHandler / OrderEditHandler / CallbackQueryHandler
    ↓
OrderService  ←→  OrderRepository / OrderStopRepository / GeocodingService / DeliveryPriceService
    ↓
orders, order_stops (БД)
```

Хендлеры ничего НЕ знают о SQL и о расчётах расстояний:  
они только говорят `OrderService`:

- "создай заказ",
- "отмени этот orderId",
- "обнови адрес/телефон/комментарий",
- "пересчитай стоимость",
- "отметь точку как доставленную".

`OrderService`:

- общается с репозиториями (`OrderRepository`, `OrderStopRepository`),
- вызывает `GeocodingService` (чтобы по адресу получить координаты),
- вызывает `DeliveryPriceService` (чтобы по координатам посчитать цену),
- следит за целостностью:
  - нельзя отменить уже принятый заказ,
  - нельзя менять дату у не‑NEW заказа,
  - при изменении адреса пересчитать все точки и общую сумму.

---

## 1. Объявление и поля

### Код (без импортов)

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class OrderService {
    
    private final OrderRepository orderRepository;
    private final OrderStopRepository orderStopRepository;
    private final GeocodingService geocodingService;
    private final DeliveryPriceService deliveryPriceService;
```

### Разбор

- `@Service` — говорим Spring:
  - "это сервисный класс",  
  - создавай для него бин `orderService`,  
  - внедряй его в другие классы через конструктор.

- `@Slf4j` — даёт поле `log`:
  - `log.info(...)`, `log.debug(...)`, `log.warn(...)`, `log.error(...)`.

- `@RequiredArgsConstructor`:
  - генерирует конструктор с параметрами для всех `final` полей:

    ```java
    public OrderService(OrderRepository orderRepository,
                        OrderStopRepository orderStopRepository,
                        GeocodingService geocodingService,
                        DeliveryPriceService deliveryPriceService) { ... }
    ```

- Поля:
  - `orderRepository` — работа с таблицей `orders`.
  - `orderStopRepository` — работа с таблицей `order_stops`.
  - `geocodingService` — ходим в DaData/геокодер:
    - `geocode(address)` → координаты,
    - `isInAllowedRegion(...)` → проверка, что адрес в нужном регионе.
  - `deliveryPriceService` — расчёт расстояния/цены по координатам:
    - `calculate(...)`, `calculateAdditionalStop(...)`, `calculateMultiStopDelivery(...)`.

---

## 2. Создание обычного заказа: `createOrder`

### Перегрузка №1 — без координат

```java
@Transactional
public Order createOrder(Shop shop, String recipientName, String recipientPhone,
                         String deliveryAddress,
                         BigDecimal deliveryPrice,
                         String comment,
                         LocalDate deliveryDate) {
    return createOrder(shop, recipientName, recipientPhone, deliveryAddress, 
            deliveryPrice, comment, deliveryDate, null, null);
}
```

Разбор:

- `@Transactional`:
  - Spring открывает транзакцию к БД,
  - если внутри метода всё ок → делает COMMIT,
  - если вылетает исключение → делает ROLLBACK (откат).

- Мы не хотим дублировать код, поэтому:
  - эта версия просто вызывает вторую перегрузку `createOrder` с `deliveryLatitude = null`, `deliveryLongitude = null`.

### Перегрузка №2 — с координатами

```java
@Transactional
public Order createOrder(Shop shop, String recipientName, String recipientPhone,
                         String deliveryAddress,
                         BigDecimal deliveryPrice,
                         String comment,
                         LocalDate deliveryDate,
                         Double deliveryLatitude,
                         Double deliveryLongitude) {
    log.info("Создание заказа: shopId={}, recipient={}, date={}", shop.getId(), recipientName, deliveryDate);

    // Создаём заказ через Builder
    Order.OrderBuilder builder = Order.builder()
            .shop(shop)
            .recipientName(recipientName)
            .recipientPhone(recipientPhone)
            .deliveryAddress(deliveryAddress)
            .deliveryPrice(deliveryPrice)
            .comment(comment)
            .deliveryDate(deliveryDate)
            .status(OrderStatus.NEW);
    
    // Добавляем координаты если есть
    if (deliveryLatitude != null && deliveryLongitude != null) {
        builder.deliveryLatitude(BigDecimal.valueOf(deliveryLatitude));
        builder.deliveryLongitude(BigDecimal.valueOf(deliveryLongitude));
    }

    Order order = builder.build();
    Order savedOrder = orderRepository.save(order);
    log.info("Заказ создан: orderId={}", savedOrder.getId());

    return savedOrder;
}
```

Разбор по шагам:

1. **Лог**:

   ```java
   log.info("Создание заказа: shopId={}, recipient={}, date={}", shop.getId(), recipientName, deliveryDate);
   ```

   - логируем, чтобы при отладке видеть, кто что создал и на когда.

2. **Сборка через Builder**:

   ```java
   Order.OrderBuilder builder = Order.builder()
           .shop(shop)
           .recipientName(recipientName)
           .recipientPhone(recipientPhone)
           .deliveryAddress(deliveryAddress)
           .deliveryPrice(deliveryPrice)
           .comment(comment)
           .deliveryDate(deliveryDate)
           .status(OrderStatus.NEW);
   ```

   - `.shop(shop)` — привязываем заказ к магазину.
   - `.recipientName/Phone` — кладём данные получателя.
   - `.deliveryAddress` — адрес.
   - `.deliveryPrice` — уже рассчитанная или введённая цена.
   - `.comment` — комментарий.
   - `.deliveryDate` — дата.
   - `.status(OrderStatus.NEW)` — новый заказ всегда стартует в статусе `NEW`.

3. **Координаты, если есть**:

   ```java
   if (deliveryLatitude != null && deliveryLongitude != null) {
       builder.deliveryLatitude(BigDecimal.valueOf(deliveryLatitude));
       builder.deliveryLongitude(BigDecimal.valueOf(deliveryLongitude));
   }
   ```

   - иногда ты уже знаешь координаты (например, геокод сделал ранее),
   - тогда сразу кладёшь `deliveryLatitude` / `deliveryLongitude`.

4. **Сохранение**:

   ```java
   Order order = builder.build();
   Order savedOrder = orderRepository.save(order);
   ```

   - `build()` → создаётся объект `Order`,
   - `orderRepository.save(order)`:
     - делает `INSERT` в `orders`,
     - возвращает `savedOrder` с проставленным `id`, `createdAt` и т.п.

5. Возвращаем `savedOrder` — это и есть свежесозданный заказ.

---

## 3. Поиск и список заказов

### Найти заказ по ID

```java
public Optional<Order> findById(UUID orderId) {
    return orderRepository.findById(orderId);
}
```

- тонкая обёртка над репозиторием:
  - `Optional<Order>`:
    - содержит `Order`, если найден,
    - пустой, если нет.

### Все заказы магазина

```java
public List<Order> getOrdersByShop(Shop shop) {
    return orderRepository.findByShop(shop);
}
```

- `orderRepository.findByShop(shop)`:
  - Spring Data JPA сам сгенерит SQL:

    ```sql
    SELECT * FROM orders WHERE shop_id = :shop.id ORDER BY id;
    ```

- в хендлерах (`Bot.handleMyOrdersButton`) это используется, чтобы построить список "Мои заказы".

### Свободные заказы (для курьеров)

```java
public List<Order> getAvailableOrders() {
    return orderRepository.findByStatus(OrderStatus.NEW);
}
```

- ищем все заказы со статусом `NEW`,
- это те, которые может взять курьер.

---

## 4. Отмена заказа: `cancelOrder`

### Код

```java
@Transactional
public boolean cancelOrder(UUID orderId) {
    Optional<Order> opt = orderRepository.findById(orderId);
    if (opt.isEmpty()) {
        return false;
    }
    Order order = opt.get();
    if (order.getStatus() != OrderStatus.NEW) {
        log.warn("Попытка отменить заказ не в статусе NEW: orderId={}, status={}", orderId, order.getStatus());
        return false;
    }
    order.setStatus(OrderStatus.CANCELLED);
    orderRepository.save(order);
    log.info("Заказ отменён: orderId={}", orderId);
    return true;
}
```

### Разбор

1. `findById`:
   - если заказа не существует → `false`.
2. Если заказ найден:
   - проверяем статус:

   ```java
   if (order.getStatus() != OrderStatus.NEW) {
       // Нельзя отменить то, что уже взял курьер или уже доставлено
       return false;
   }
   ```

3. Если статус `NEW`:
   - ставим `CANCELLED`,
   - сохраняем.
4. Возвращаем `true` — отмена удалась.

Используется в `CallbackQueryHandler` при кнопках `"order_cancel_*"`  
и в интерфейсе магазина ("❌ Отменить").

---

## 5. Мультиадрес: `createMultiStopOrder`

### Код (сокращённый)

```java
@Transactional
public Order createMultiStopOrder(Shop shop, LocalDate deliveryDate, String comment,
                                  List<OrderCreationData.StopData> stopsData) {
    log.info("Создание мультиадресного заказа: shopId={}, stops={}, date={}", 
            shop.getId(), stopsData.size(), deliveryDate);
    
    // 1. Считаем общую стоимость
    BigDecimal totalPrice = stopsData.stream()
            .map(OrderCreationData.StopData::getDeliveryPrice)
            .filter(p -> p != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    
    // 2. Берём первую точку как "основу" заказа
    OrderCreationData.StopData firstStop = stopsData.get(0);
    
    // 3. Создаём сущность Order
    Order order = Order.builder()
            .shop(shop)
            .recipientName(firstStop.getRecipientName())
            .recipientPhone(firstStop.getRecipientPhone())
            .deliveryAddress(firstStop.getDeliveryAddress())
            .deliveryPrice(totalPrice)
            .comment(comment)
            .deliveryDate(deliveryDate)
            .status(OrderStatus.NEW)
            .isMultiStop(true)
            .totalStops(stopsData.size())
            .build();
    
    // 4. Координаты первой точки (для обратной совместимости)
    if (firstStop.getDeliveryLatitude() != null && firstStop.getDeliveryLongitude() != null) {
        order.setDeliveryLatitude(BigDecimal.valueOf(firstStop.getDeliveryLatitude()));
        order.setDeliveryLongitude(BigDecimal.valueOf(firstStop.getDeliveryLongitude()));
    }
    
    // 5. Сохраняем заказ, чтобы получить его id
    Order savedOrder = orderRepository.save(order);
    
    // 6. Создаём точки (OrderStop) для каждой StopData
    for (OrderCreationData.StopData stopData : stopsData) {
        OrderStop stop = OrderStop.builder()
                .order(savedOrder)
                .stopNumber(stopData.getStopNumber())
                .recipientName(stopData.getRecipientName())
                .recipientPhone(stopData.getRecipientPhone())
                .deliveryAddress(stopData.getDeliveryAddress())
                .deliveryPrice(stopData.getDeliveryPrice())
                .stopStatus(StopStatus.PENDING)
                .build();

        // координаты / distance / comment, если есть
        // ...

        orderStopRepository.save(stop);
        savedOrder.getStops().add(stop);
    }
    
    log.info("Мультиадресный заказ создан: orderId={}, stops={}, totalPrice={}", 
            savedOrder.getId(), stopsData.size(), totalPrice);
    
    return savedOrder;
}
```

### Что за `OrderCreationData.StopData`

- Это DTO (data transfer object), который собирает `OrderCreationHandler`
  во время диалога по созданию мультиадресного заказа.
- Для каждой точки там лежит:
  - `stopNumber`,
  - `recipientName` / `recipientPhone`,
  - `deliveryAddress`,
  - `deliveryLatitude` / `deliveryLongitude`,
  - `deliveryPrice`,
  - `distanceKm`,
  - `comment`.

### Логика по шагам

1. Считаем `totalPrice`:

```java
BigDecimal totalPrice = stopsData.stream()
        .map(OrderCreationData.StopData::getDeliveryPrice)
        .filter(p -> p != null)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
```

- `stream()` — пробегаемся по списку `stopsData`.
- `.map(StopData::getDeliveryPrice)` — берём только цены.
- `.filter(p -> p != null)` — выкидываем `null`.
- `.reduce(BigDecimal.ZERO, BigDecimal::add)` — складываем все цены.

2. Берём `firstStop`:

```java
OrderCreationData.StopData firstStop = stopsData.get(0);
```

- первая точка служит как "основная":
  - её имя/телефон/адрес попадает в само поле заказа `Order`:
    - для обратной совместимости с кодом, который ещё не знает про мультистопы.

3. Создаём `Order`:

```java
Order order = Order.builder()
        .shop(shop)
        .recipientName(firstStop.getRecipientName())
        ...
        .isMultiStop(true)
        .totalStops(stopsData.size())
        .build();
```

- `deliveryPrice = totalPrice` — в заказе хранится **общая сумма**,
  а в `OrderStop.deliveryPrice` — по каждой точке.

4. Координаты первой точки:

```java
if (firstStop.getDeliveryLatitude() != null && firstStop.getDeliveryLongitude() != null) {
    order.setDeliveryLatitude(BigDecimal.valueOf(firstStop.getDeliveryLatitude()));
    order.setDeliveryLongitude(BigDecimal.valueOf(firstStop.getDeliveryLongitude()));
}
```

- чтобы старый код, который смотрит только на `Order.deliveryLatitude/Longitude`,
  имел хотя бы координаты первой точки.

5. Сохраняем заказ и создаём `OrderStop` для каждой точки:

```java
OrderStop stop = OrderStop.builder()
        .order(savedOrder)
        .stopNumber(stopData.getStopNumber())
        .recipientName(stopData.getRecipientName())
        ...
        .stopStatus(StopStatus.PENDING)
        .build();
```

- `orderStopRepository.save(stop)` — сохраняем точку.
- `savedOrder.getStops().add(stop)` — сразу добавляем в список `stops` у заказа.

В итоге в БД:

- 1 запись в `orders` с `is_multi_stop = true`, `total_stops = N`,
- N записей в `order_stops` с `stop_number` от 1 до N.

---

## 6. Получение точек заказа

```java
public List<OrderStop> getOrderStops(UUID orderId) {
    return orderStopRepository.findByOrderIdOrderByStopNumberAsc(orderId);
}
```

- `orderStopRepository.findByOrderIdOrderByStopNumberAsc(orderId)`:
  - Spring Data JPA сам делает SQL:

    ```sql
    SELECT * FROM order_stops
    WHERE order_id = :orderId
    ORDER BY stop_number ASC;
    ```

- Возвращаем **отсортированный список** точек.

Используется в:

- `Bot.handleMyOrdersButton` — чтобы построить маршрут по точкам.

---

## 7. Отметить точку как доставленную: `markStopDelivered`

### Код

```java
@Transactional
public void markStopDelivered(UUID orderId, int stopNumber) {
    Optional<OrderStop> stopOpt = orderStopRepository.findByOrderIdAndStopNumber(orderId, stopNumber);
    if (stopOpt.isPresent()) {
        OrderStop stop = stopOpt.get();
        stop.markDelivered();
        orderStopRepository.save(stop);
        log.info("Точка #{} заказа {} отмечена как доставленная", stopNumber, orderId);
        
        // Проверяем, все ли точки доставлены
        if (orderStopRepository.areAllStopsDelivered(orderId)) {
            // Меняем статус заказа на DELIVERED
            Order order = orderRepository.findById(orderId).orElse(null);
            if (order != null) {
                order.setStatus(OrderStatus.DELIVERED);
                order.setDeliveredAt(java.time.LocalDateTime.now());
                orderRepository.save(order);
                log.info("Все точки доставлены, заказ {} помечен как DELIVERED", orderId);
            }
        }
    }
}
```

### Разбор

1. Ищем нужную точку по `orderId` и `stopNumber`:

```java
Optional<OrderStop> stopOpt = orderStopRepository.findByOrderIdAndStopNumber(orderId, stopNumber);
```

2. Если точка нашлась:

```java
stop.markDelivered();
orderStopRepository.save(stop);
```

- `markDelivered()`:

```java
public void markDelivered() {
    this.stopStatus = StopStatus.DELIVERED;
    this.deliveredAt = LocalDateTime.now();
}
```

3. Потом проверяем, **все ли точки этого заказа уже доставлены**:

```java
if (orderStopRepository.areAllPostsDelivered(orderId)) {
    Order order = orderRepository.findById(orderId).orElse(null);
    if (order != null) {
        order.setStatus(OrderStatus.DELIVERED);
        order.setDeliveredAt(now);
        orderRepository.save(order);
    }
}
```

- `areAllStopsDelivered(orderId)` — метод репозитория, который проверяет, нет ли среди точек статус `!= DELIVERED`.
- Если все `DELIVERED`:
  - ставим заказу статус `DELIVERED`,
  - проставляем `deliveredAt` у самого `Order`.

Это связывает **микро‑статусы точек** с **общим статусом заказа**.

---

## 8. Редактирование адреса точки: `updateStopAddress`

### Код (важные части)

```java
@Transactional
public boolean updateStopAddress(UUID orderId, int stopNumber, String newAddress) {
    Order order = orderRepository.findById(orderId).orElse(null);
    if (order == null || order.getStatus() != OrderStatus.NEW) return false;
    List<OrderStop> stops = orderStopRepository.findByOrderIdOrderByStopNumberAsc(orderId);

    if (stops.isEmpty()) {
        // Обычный (не мультиадресный) заказ
        if (stopNumber != 1) return false;
        order.setDeliveryAddress(newAddress);
        geocodeOrderAddress(order, newAddress);
        recalcSingleOrderDelivery(order);
        orderRepository.save(order);
        log.info("Заказ {}: обновлён адрес (основной) с перерасчётом цены", orderId);
        return true;
    }

    // Мультиадресный заказ
    Optional<OrderStop> stopOpt = orderStopRepository.findByOrderIdAndStopNumber(orderId, stopNumber);
    if (stopOpt.isEmpty()) return false;
    OrderStop stop = stopOpt.get();
    stop.setDeliveryAddress(newAddress);
    geocodeStopAddress(stop, newAddress);
    if (stopNumber == 1) {
        order.setDeliveryAddress(newAddress);
    }
    recalcMultiStopDelivery(order, stops);
    log.info("Заказ {}: обновлён адрес точки {} с перерасчётом цен", orderId, stopNumber);
    return true;
}
```

### Логика

1. Находим заказ.
2. Если:
   - заказа нет,
   - или его статус НЕ `NEW` (уже в работе/завершён)  
   → **нельзя редактировать**, возвращаем `false`.

3. Загружаем все точки:

```java
List<OrderStop> stops = orderStopRepository.findByOrderIdOrderByStopNumberAsc(orderId);
```

4. Если `stops.isEmpty()`:
   - заказ **не мультиадресный** (нет записей в `order_stops`),
   - тогда:
     - менять можно только `stopNumber == 1`,
     - обновляем `order.deliveryAddress`,
     - вызываем:

       ```java
       geocodeOrderAddress(order, newAddress);
       recalcSingleOrderDelivery(order);
       ```

     - т.е.:
       - геокодируем новый адрес,
       - считаем новое расстояние/цену,
       - сохраняем заказ.

5. Если `stops` не пустой:
   - это **мультиадресный** заказ.
   - находим нужную точку по `orderId + stopNumber`:

   ```java
   Optional<OrderStop> stopOpt = orderStopRepository.findByOrderIdAndStopNumber(orderId, stopNumber);
   ```

   - меняем адрес точки:

   ```java
   stop.setDeliveryAddress(newAddress);
   geocodeStopAddress(stop, newAddress);
   ```

   - если это первая точка (`stopNumber == 1`) — синхронизируем и `order.deliveryAddress`.

   - пересчитываем **всю мультиадресную доставку**:

   ```java
   recalcMultiStopDelivery(order, stops);
   ```

   - внутри `recalcMultiStopDelivery` мы:
     - считаем расстояния между точками через `deliveryPriceService.calculate/calculateAdditionalStop`,
     - обновляем `deliveryPrice` по каждой точке,
     - и общую сумму в `order.deliveryPrice`.

---

## 9. Обновление телефона и комментария точки

### `updateStopPhone`

```java
@Transactional
public boolean updateStopPhone(UUID orderId, int stopNumber, String newPhone) {
    Order order = orderRepository.findById(orderId).orElse(null);
    if (order == null || order.getStatus() != OrderStatus.NEW) return false;
    List<OrderStop> stops = orderStopRepository.findByOrderIdOrderByStopNumberAsc(orderId);
    if (stops.isEmpty()) {
        if (stopNumber != 1) return false;
        order.setRecipientPhone(newPhone);
        orderRepository.save(order);
        log.info("Заказ {}: обновлён телефон (основной)", orderId);
        return true;
    }
    Optional<Stop> stopOpt = orderStopRepository.findByOrderIdAndStopNumber(orderId, stopNumber);
    if (stopOpt.isEmpty()) return false;
    OrderStop stop = stopOpt.get();
    stop.setRecipientPhone(newPhone);
    orderStopRepository.save(stop);
    if (stopNumber == 1) {
        order.setRecipientPhone(newPhone);
        orderRepository.save(order);
    }
    log.info("Заказ {}: обновлён телефон точки {}", orderId, stopNumber);
    return true;
}
```

Логика такая же, как с адресом:

- если нет точек (`stops.isEmpty()`) → работаем с полем в `Order`,
- если есть точки → меняем `OrderStop.recipientPhone` (и, при необходимости, `Order.recipientPhone` для точки 1).

### `updateStopComment`

Полностью аналогично `updateStopPhone`, только для `comment`.

---

## 10. Обновление даты доставки заказа

```java
@Transactional
public boolean updateOrderDeliveryDate(UUID orderId, LocalDate newDate) {
    Order order = orderRepository.findById(orderId).orElse(null);
    if (order == null || order.getStatus() != OrderStatus.NEW) return false;
    order.setDeliveryDate(newDate);
    orderRepository.save(order);
    log.info("Заказ {}: обновлена дата доставки {}", orderId, newDate);
    return true;
}
```

- дата общая на весь заказ,
- менять можно только пока заказ `NEW`,
- просто обновляем поле `deliveryDate`.

---

## 11. Геокодинг адресов: `geocodeOrderAddress` и `geocodeStopAddress`

### `geocodeOrderAddress`

```java
private void geocodeOrderAddress(Order order, String address) {
    try {
        var geoOpt = geocodingService.geocode(address);
        if (geoOpt.isEmpty()) {
            log.warn("Не удалось геокодировать адрес заказа {}: {}", order.getId(), address);
            return;
        }
        GeocodingService.GeocodingResult geo = geoOpt.get();
        if (!geocodingService.isInAllowedRegion(geo)) {
            log.warn("Адрес {} вне разрешённого региона, координаты не обновляем", geo.fullAddress());
            return;
        }
        order.setDeliveryLatitude(BigDecimal.valueOf(geo.latitude()));
        order.setDeliveryLongitude(BigDecimal.valueOf(geo.longitude()));
    } catch (Exception e) {
        log.warn("Ошибка геокодирования адреса заказа {}: {}", order.getId(), e.getMessage());
    }
}
```

Смысл:

- `geocodingService.geocode(address)`:
  - ходим в DaData/геокодер,
  - получаем `Optional<GeocodingResult>`:
    - внутри: широта, долгота, полный текст адреса, регион.
- `isInAllowedRegion(geo)`:
  - проверяем, что адрес внутри нашего города/области.
- Если всё ок:
  - кладём `order.deliveryLatitude` / `.deliveryLongitude`.

### `geocodeStopAddress`

То же самое, но для `OrderStop`:

- в логах указываем `stop.getStopNumber()` и `stop.getOrder().getId()`,
- обновляем `stop.deliveryLatitude / deliveryLongitude`,
- сохраняем через `orderStopRepository.save(stop)`.

---

## 12. Пересчёт стоимости: `recalcSingleOrderDelivery` и `recalcMultiStopDelivery`

### `recalcSingleOrderDelivery`

```java
private void recalcSingleOrderDelivery(Order order) {
    if (order.getShop() == null ||
            order.getShop().getLatitude() == null ||
            order.getShop().getLongitude() == null ||
            order.getDeliveryLatitude() == null ||
            order.getDeliveryLongitude() == null) {
        log.warn("Невозможно пересчитать доставку для заказа {}: нет координат", order.getId());
        return;
    }
    var result = deliveryPriceService.calculate(
            order.getShop().getLatitude().doubleValue(),
            order.getShop().getLongitude().doubleValue(),
            order.getDeliveryLatitude().doubleValue(),
            order.getDeliveryLongitude().doubleValue()
    );
    order.setDeliveryPrice(result.price());
    log.info("Пересчитана цена доставки для заказа {}: {} ({} км)", 
            order.getId(), result.price(), result.distanceKm());
}
```

- Проверяем, что есть координаты:
  - у магазина (`shop.latitude/longitude`),
  - у заказа (`deliveryLatitude/Longitude`).
- Вызываем:

```java
deliveryPriceService.calculate(shopLat, shopLon, destLat, destLon)
```

- `calculate` возвращает `DeliveryCalculation`:
  - `distanceKm` — расстояние,
  - `price` — предложенная цена,
  - `priceDescription` — текст для человека.

- Мы берём `price()` и кладём в `order.deliveryPrice`.

### `recalcMultiStopDelivery`

Длинный метод (ниже в файле), по сути:

- строит массив координат всех точек маршрута:
  - `магазин → стоп1 → стоп2 → ...`,
- вызывает:

```java
BigDecimal[] prices = deliveryPriceService.calculateMultiStopDelivery(
    shopLat, shopLon, double[][] stopsCoords
);
```

- пробегается по `stops`, проставляет:
  - `stop.deliveryPrice` для каждой точки,
  - суммирует их в общий `order.deliveryPrice`.

Логика тарификации (**цены за км/минималки**) спрятана в `DeliveryPriceService`,  
здесь только "склеивание" координат и раздача результатов по точкам.

---

## 13. Итоговая карта `OrderService`

```text
Создание обычного заказа:
  createOrder(shop, name, phone, address, price, comment, date[, lat, lon])
    → Order.builder(...)
    → orderRepository.save(order)

Создание мультиадресного:
  createMultiStopOrder(shop, date, comment, stopsData)
    → считаем totalPrice
    → строим Order (isMultiStop=true, totalStops = N)
    → сохраняем Order
    → для каждой StopData создаём OrderStop + save

Отмена:
  cancelOrder(orderId)
    → findById
    → if status == NEW → CANCELLED

Выборки:
  findById(orderId)
  getOrdersByShop(shop)
  getAvailableOrders()
  getOrderStops(orderId)

Изменения:
  updateStopAddress(orderId, stopNumber, newAddress)
    → обычный заказ: меняем Order.deliveryAddress, геокодим, пересчитываем цену
    → мультистоп: меняем OrderStop.deliveryAddress, геокодим, пересчитываем все точки

  updateStopPhone / updateStopComment
    → аналогично, с синхронизацией первой точки в Order.*

  updateOrderDeliveryDate(orderId, newDate)

Маркировка доставленного:
  markStopDelivered(orderId, stopNumber)
    → stop.markDelivered()
    → если все точки доставлены → Order.status = DELIVERED
```

---

## Что дальше разбирать

По линии заказов у тебя уже есть:

- `10_Order_model.md` — структура заказа,
- `11_OrderStop_model.md` — структура точки,
- `12_OrderService.md` — логика создания/отмены/редактирования.

Дальше логичный шаг:

- **`OrderCreationHandler.java`** — как бот собирает `OrderCreationData` и вызывает `OrderService.createOrder` / `createMultiStopOrder`.
- Либо:
  - `OrderRepository.java` — все методы поиска/фильтрации,
  - `OrderStatus` / `StopStatus` — статусы как часть бизнес‑логики.

Напиши, с какого файла по заказам/курьерам/магазинам продолжать, и я разложу его в том же формате "каждое слово объяснить, откуда взялось и что будет, если налажать". 
