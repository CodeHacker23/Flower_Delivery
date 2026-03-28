## OrderCreationData.java — блокнот психотерапевта для заказов

> Представь, что `OrderCreationHandler` — это ведущий шоу "Давай создадим заказ и не сойдём с ума".  
> А `OrderCreationData` — это его **грязный блокнот**, куда он записывает все твои ответы,  
> чтобы в конце собрать из этого один нормальный `Order`.

Импорт/`package` скипаем — ты уже в курсе, что это за звери.

---

## 1. Аннотация, класс и общая идея

```java
@Data
public class OrderCreationData {
```

- `@Data` (Lombok):
  - генерит:
    - геттеры/сеттеры,
    - `toString`,
    - `equals/hashCode`.
  - Чтобы не писать руками 100500 `getDeliveryDate()` и не страдать хернёй.

- `public class OrderCreationData`:
  - обычный Java‑класс,
  - **НЕ** сущность БД,
  - живёт **только в памяти**,
  - используется, пока идёт диалог в боте.

Аналогия:  
бот‑ведущий таскает этот класс как блокнот "что уже спросили у магазина, что он ответил, сколько точек, какая цена и т.д.".

---

## 2. Состояние (`state`) — на каком мы шаге ада

```java
/** Текущий шаг создания заказа */
private OrderCreationState state = OrderCreationState.NONE;
```

- `OrderCreationState` — enum, который ты уже видел в `OrderCreationHandler`:
  - `WAITING_RECIPIENT_NAME`,
  - `WAITING_DELIVERY_ADDRESS`,
  - `WAITING_ADDITIONAL_ADDRESS`,
  - и прочие радости жизни.

- `state = OrderCreationState.NONE`:
  - по умолчанию "мы вообще ни при чём",
  - юзер ещё не начал создавать заказ,
  - `OrderCreationHandler.handleMessage` в таком случае просто говорит:
    - "я не знаю этого человека" и возвращает `false`.

Аналогия:  
`state` — это "в каком круге ада мы сейчас" по Данте:

- круг "спросили имя",
- круг "спросили адрес",
- круг "давай ещё одну точку, пожалуйста",
- круг "завершаем и создаём заказ".

---

## 3. Общие данные заказа: дата и общий комментарий

```java
/** Дата доставки (сегодня или завтра) */
private LocalDate deliveryDate;

/** Комментарий к заказу (общий) */
private String comment;
```

- `deliveryDate`:
  - заполняется на самом первом шаге (`handleDateSelection`),
  - обычно либо `LocalDate.now()`, либо `+1 день`,
  - потом эта дата:
    - уходит в `OrderService.createOrder / createMultiStopOrder`,
    - участвует в тексте подтверждения (`сегодня/завтра`).

- `comment`:
  - общий комментарий на **весь заказ**,
  - заполняется либо:
    - через `handleComment` (старый путь),
    - либо через `handleStopComment` для первой точки (он ещё и в первый `StopData` его может переписать).

Важно:  
кроме общего `comment`, у **каждой точки** ещё может быть **свой** `comment` (см. `StopData.comment`).  
Например:

- общий: "курьеру можно позвонить, если что",
- точка 1: "домофон 123",
- точка 2: "подъезд с заднего двора".

---

## 4. Список точек (`stops`) и `currentStop`

```java
/** Список точек доставки (для мультиадресных заказов) */
private List<StopData> stops = new ArrayList<>();

/** Текущая точка, которую заполняем */
private StopData currentStop;
```

- `stops`:
  - список всех точек доставки,
  - каждая — это `StopData` (вложенный класс ниже),
  - для обычного заказа:
    - либо пустой,
    - либо содержит 1 элемент (первую точку).
  - для мультиадресного:
    - 2, 3 и т.д. точек.

- `currentStop`:
  - временный объект для **дополнительной точки**,
  - заполняется по шагам:
    - `handleAdditionalRecipientName`,
    - `handleAdditionalRecipientPhone`,
    - `handleAdditionalAddress`,
    - цена, коммент,
  - потом методом `saveCurrentStop()` добавляется в `stops`,
  - и `currentStop` снова становится `null`.

Аналогия:  
`stops` — это "список всех жертв" (адресов), по которым курьер должен проехать.  
`currentStop` — "жертва, которую мы сейчас подробно описываем".

---

## 5. Поля первой точки — зачем эта шизофрения?

```java
/** Имя получателя (первая точка) */
private String recipientName;

/** Телефон получателя (первая точка) */
private String recipientPhone;

/** Адрес доставки (первая точка) */
private String deliveryAddress;

/** Широта (первая точка) */
private Double deliveryLatitude;

/** Долгота (первая точка) */
private Double deliveryLongitude;

/** Расстояние в км (первая точка) */
private Double distanceKm;

/** Рекомендуемая цена (первая точка) */
private BigDecimal suggestedPrice;

/** Финальная цена (первая точка) */
private BigDecimal deliveryPrice;
```

Почему не сразу в `stops[0]`?

- Историческая причина:
  - сначала был простой сценарий "1 заказ = 1 адрес",
  - всё хранилось просто в этих полях.
  - потом добавили мультиадрес, но старый код/миграции трогать не хотелось.

- Текущий подход:
  - первая точка **сначала** живёт в этих полях,
  - когда мы доходим до комментария/мультиадреса — вызывается:

    ```java
    data.saveFirstStopFromFields();
    ```

  - и эти поля аккуратно переносятся в `stops[0]` (см. ниже).

Можно думать так:

- пока заказ "одиночный", мы живём в этих полях,
- как только включается мультистоп/точки — мы "поднимаем" первую точку в общий список `stops`.

---

## 6. Логика: мультистоп? общая цена? координаты?

### 6.1. `isMultiStop` — считается ли заказ "мультиадресным"

```java
public boolean isMultiStop() {
    return stops.size() > 1;
}
```

- `stops.size() > 1`:
  - если точек 2 и больше → мультиадрес,
  - одна или ноль → обычный заказ.

Используется в:

- `handleComment` / `finalizeOrder`:
  - чтобы решить, звать `createOrder` или `createMultiStopOrder`,
- `buildOrderConfirmation`:
  - чтобы решить, рендерить один блок или список точек.

---

### 6.2. `getTotalPrice` — сколько в сумме стоит выгулять курьера

```java
public BigDecimal getTotalPrice() {
    if (stops.isEmpty()) {
        return deliveryPrice != null ? deliveryPrice : BigDecimal.ZERO;
    }
    return stops.stream()
            .map(StopData::getDeliveryPrice)
            .filter(p -> p != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
}
```

Разбор:

- Если `stops.isEmpty()`:
  - значит, живём по старой схеме — без списка точек,
  - возвращаем `deliveryPrice` (первая/единственная точка),
  - если её тоже нет → `0`.

- Если стопы есть:
  - берём `stops.stream()` — то есть идём по всем точкам,
  - `.map(StopData::getDeliveryPrice)` — достаём для каждой цену,
  - `.filter(p -> p != null)` — выкидываем мусор/незаполненные,
  - `.reduce(BigDecimal.ZERO, BigDecimal::add)` — суммируем всё в одну циферку.

Аналогия:

- это как считать общий чек в баре:
  - если ты один — просто смотришь на свой счёт,
  - если вас трое — складываешь чеки всех.

---

## 7. Управление точками: `startNewStop`, `saveCurrentStop`, `saveFirstStopFromFields`

### 7.1. `startNewStop` — начинаем новую точку

```java
public void startNewStop() {
    currentStop = new StopData();
    currentStop.setStopNumber(stops.size() + 1);
}
```

- создаём `new StopData()` — чистый лист для новой точки,
- `stopNumber = stops.size() + 1`:
  - если уже было 2 точки в списке, эта получит номер 3,
  - чтобы в UI показывать "Точка 3", "Точка 4" и т.д.

Используется в:

- `OrderCreationHandler.handleAddStopDecision(addMore = true)`:
  - когда юзер нажимает "➕ Добавить адрес".

---

### 7.2. `saveCurrentStop` — записываем текущую точку в список

```java
public void saveCurrentStop() {
    if (currentStop != null) {
        stops.add(currentStop);
        currentStop = null;
    }
}
```

- Если `currentStop` не `null`:
  - кидаем её в `stops`,
  - обнуляем `currentStop` (типа "закончили описывать эту жертву").

Используется в:

- `handleAdditionalStopComment`:
  - после комментария к дополнительной точке,
  - как только юзер всё сказал — точка считается оформленной.

---

### 7.3. `saveFirstStopFromFields` — миграция "первой точки" в нормальный список

```java
public void saveFirstStopFromFields() {
    StopData firstStop = new StopData();
    firstStop.setStopNumber(1);
    firstStop.setRecipientName(recipientName);
    firstStop.setRecipientPhone(recipientPhone);
    firstStop.setDeliveryAddress(deliveryAddress);
    firstStop.setDeliveryLatitude(deliveryLatitude);
    firstStop.setDeliveryLongitude(deliveryLongitude);
    firstStop.setDistanceKm(distanceKm);
    firstStop.setDeliveryPrice(deliveryPrice);
    
    if (stops.isEmpty()) {
        stops.add(firstStop);
    } else {
        stops.set(0, firstStop);
    }
}
```

Это как "переписать заметки с салфетки в нормальный ежедневник":

- создаём `firstStop`,
- копируем в неё:
  - имя, телефон, адрес,
  - координаты,
  - расстояние,
  - цену,
- если список `stops` пуст:
  - просто добавляем,
- если что‑то уже лежит:
  - заменяем нулевой элемент.

Используется в:

- `OrderCreationHandler.handleStopComment`:
  - как только закончен комментарий к первой точке,
  - всё, что было в полях, мигрирует в `stops[0]`.

---

## 8. Последняя точка и её координаты

### 8.1. `getLastStop`

```java
public StopData getLastStop() {
    if (stops.isEmpty()) {
        return null;
    }
    return stops.get(stops.size() - 1);
}
```

- Возвращает **последнюю** точку в списке,
- если списка нет — `null`.

---

### 8.2. `getLastStopCoordinates` — откуда ехать до следующей точки

```java
public double[] getLastStopCoordinates() {
    StopData last = getLastStop();
    if (last != null && last.getDeliveryLatitude() != null && last.getDeliveryLongitude() != null) {
        return new double[] { last.getDeliveryLatitude(), last.getDeliveryLongitude() };
    }
    // Если нет точек — возвращаем координаты первой точки из полей
    if (deliveryLatitude != null && deliveryLongitude != null) {
        return new double[] { deliveryLatitude, deliveryLongitude };
    }
    return null;
}
```

Очень важный метод для мультистопа:

- 1) Если есть хотя бы один `StopData`:
  - и у него есть координаты:
    - возвращаем `[lat, lon]` последней точки.
  - это нужно, чтобы рассчитать расстояние **от предыдущей точки до новой**.

- 2) Если стопов нет, но есть координаты первой точки в "старых" полях:
  - возвращаем их.

- 3) Если ничего нет:
  - `null` → `OrderCreationHandler` поймёт, что нужно **ручной ввод цены**.

Аналогия:

- "Откуда мы сейчас едем?"
  - если уже где‑то были — от последнего адреса,
  - если ещё не выезжали — от первой точки (или от магазина, в зависимости от логики `DeliveryPriceService`).

---

## 9. Вложенный класс `StopData` — паспорт одной точки

```java
@Data
public static class StopData {
    /** Номер точки (1, 2, 3...) */
    private Integer stopNumber;
    
    /** Имя получателя */
    private String recipientName;
    
    /** Телефон получателя */
    private String recipientPhone;
    
    /** Адрес доставки */
    private String deliveryAddress;
    
    /** Широта */
    private Double deliveryLatitude;
    
    /** Долгота */
    private Double deliveryLongitude;
    
    /** Расстояние до этой точки (от предыдущей) */
    private Double distanceKm;
    
    /** Рекомендуемая цена */
    private BigDecimal suggestedPrice;
    
    /** Финальная цена */
    private BigDecimal deliveryPrice;
    
    /** Комментарий к этой точке */
    private String comment;
}
```

Это мини‑DTO для одной точки, где:

- `stopNumber` — порядковый номер (1, 2, 3),
- `recipientName/Phone` — кому везём,
- `deliveryAddress` — куда,
- `deliveryLatitude/Longitude` — где это на карте,
- `distanceKm` — сколько километров от предыдущей точки,
- `suggestedPrice` — сколько **мы** рекомендуем за этот кусок пути,
- `deliveryPrice` — сколько **реально** будет в заказе,
- `comment` — "домофон 666, код от ворот, не будить соседей, они и так несчастны".

Используется:

- в `OrderService.createMultiStopOrder`:
  - чтобы на основе списка `StopData` создать `OrderStop` сущности в БД,
- в `buildOrderConfirmation`:
  - чтобы красиво показать все точки в чатике.

---

## 10. Как всё это живёт вместе с `OrderCreationHandler`

Большая схема (упрощённо):

```text
OrderCreationHandler.startOrderCreation
    → создаёт новый OrderCreationData
    → state = WAITING_DELIVERY_DATE

... по шагам:
    state меняется (WAITING_*),
    в поля OrderCreationData пишутся:
        дата, имя, телефон, адрес, координаты, цена, комментарии

Первая точка:
    хранится в полях:
        recipientName, deliveryAddress, deliveryPrice, ...
    потом:
        saveFirstStopFromFields() → становится stops[0]

Доп. точки:
    startNewStop() → currentStop
    заполняем currentStop.*
    saveCurrentStop() → кладём в stops

Мультистоп:
    isMultiStop() → true, если stops.size() > 1
    getTotalPrice() → суммирует все deliveryPrice по stops
    getLastStopCoordinates() → даёт координаты для расчёта следующей цены

Финал:
    OrderCreationHandler.finalizeOrder / handleComment
        → createSingleStopOrder / createMultiStopOrder
        → OrderService
        → Order + OrderStop в БД
```

Если упоротым языком:

- `OrderCreationHandler` — ведущий, который орёт "Шаг 3 из 6, введите телефон",
- `OrderCreationData` — его блокнот, где всё уже записано:
  - сколько точек,
  - кто где живёт,
  - сколько денег с него брать,
  - откуда до куда ехать.

---

## 11. Что дальше?

Сейчас у тебя есть:

- полное понимание **что и где хранится**, пока бот мучает пользователя вопросами,
- как первая точка мигрирует в `stops`,
- как считаются общая цена и координаты для мультистопа.

Дальше можем:

- либо разобрать **какой‑нибудь следующий обработчик** (например, меню магазина, список заказов, или уже начать курьерские фичи),
- либо нырнуть в какую‑нибудь сервисную часть (например, `DeliveryPriceService`, чтобы разжевать, как именно он считает цену).

Куда двигаемся: **цены (`DeliveryPriceService`)** или уже **курьерское меню/функционал**?  
