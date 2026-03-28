## DeliveryPriceService.java — сколько стоит выгулять курьера

> Представь: курьер — это такси без кондиционера.  
> `DeliveryPriceService` — тот самый калькулятор, который решает,  
> сколько денег с магазина содрать за его катания по городу.

Импорты/аннотации (`@Service`, `@Slf4j`) ты уже знаешь, их не расписываю.

---

## 1. Поля и тарифная сетка

```java
private final Environment env;
```

- `Environment env`:
  - доступ к конфигу `application.properties`,
  - через него достаём `app.tariffs.*`.

```java
private static final double EARTH_RADIUS_KM = 6371.0;
```

- радиус Земли в км — нужен для формулы Haversine (расстояние "по шару").

```java
private static final double ROAD_DISTANCE_COEFFICIENT = 1.6;
```

- когда OSRM (он же "гугл без гугла") лёг жопой вверх:
  - считаем "по прямой" (по геометрии),
  - умножаем на 1.6, чтобы приблизить к дороге с пробками/поворотами.

```java
private static final double OSRM_CORRECTION_COEFFICIENT = 1.2;
```

- просто константа‑подсказка в комментариях:
  - говорит, что OSRM обычно занижает пути,
  - но на самом деле коэффициент считается динамически функцией ниже.

```java
private final TreeMap<Integer, BigDecimal> tariffs = new TreeMap<>();
```

- `TreeMap<км, цена>`:
  - ключ: "до скольки км",
  - значение: "столько рублей".
  - `TreeMap` сам сортирует по ключу:
    - `3 → 300`,
    - `5 → 400`,
    - `7 → 500`,
    - и т.д.

Аналогия:

- это как прайс‑лист такси:
  - до 3 км — 300₽,
  - до 5 км — 400₽,
  - дальше дороже.

---

## 2. DTO результата: `DeliveryCalculation`

```java
public record DeliveryCalculation(
        double distanceKm,        // Расстояние в км
        BigDecimal price,         // Рекомендуемая цена
        String priceDescription   // Описание для юзера
) {}
```

- `record` — компактный класс‑контейнер:
  - `distanceKm` — итоговое расстояние (с поправками),
  - `price` — сколько просим денег,
  - `priceDescription` — строка вида `"3.5 км — 400₽"` или `"➕ +2.1 км — 300₽"`.

Этим рекордом обмениваются:

- `OrderCreationHandler`,
- `OrderService`,
- UI‑слой (для текста в боте).

---

## 3. Загрузка тарифов: `loadTariffs`

```java
@PostConstruct
public void loadTariffs() {
    int[] distances = {3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23, 25, 27, 30};
    
    for (int km : distances) {
        String key = "app.tariffs." + km;
        String value = env.getProperty(key);
        if (value != null) {
            tariffs.put(km, new BigDecimal(value));
        }
    }
    
    log.info("Загружено {} тарифов: {}", tariffs.size(), tariffs);
    
    if (tariffs.isEmpty()) {
        // Дефолтные тарифы если конфиг пустой
        tariffs.put(3, new BigDecimal("300"));
        tariffs.put(5, new BigDecimal("400"));
        ...
        tariffs.put(30, new BigDecimal("2000"));
        log.warn("Использованы дефолтные тарифы!");
    }
}
```

Разбор:

- `@PostConstruct`:
  - метод вызовется **сразу после создания бина**,
  - до того, как кто‑то начнёт вызывать `calculate`.

- `distances = {3,5,...,30}`:
  - список возможных ключей в конфиге.

- `env.getProperty("app.tariffs." + km)`:
  - ищем строки вида:
    - `app.tariffs.3=300`,
    - `app.tariffs.5=400`,
    - etc.
  - если значение есть:
    - кладём его в `tariffs` как `BigDecimal`.

- Если ничего не нашли (`tariffs.isEmpty()`):
  - поднимаем **дефолтную тарифную сетку**,
  - логируем варнинг, чтобы потом не удивляться.

Аналогия:

- это как загрузить прайс из БД/файла,
  - а если файла нет — повесить стандартный "с 2010 года" листок на двери.

---

## 4. Физика: `calculateDistance` — расстояние по Земле

```java
public double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
    double lat1Rad = Math.toRadians(lat1);
    double lat2Rad = Math.toRadians(lat2);
    double deltaLat = Math.toRadians(lat2 - lat1);
    double deltaLon = Math.toRadians(lon2 - lon1);

    double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
               Math.cos(lat1Rad) * Math.cos(lat2Rad) *
               Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
    
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

    double distance = EARTH_RADIUS_KM * c;
    
    log.debug("Расстояние между [{},{}] и [{},{}] = {} км", 
            lat1, lon1, lat2, lon2, distance);
    
    return distance;
}
```

Это тупо учебник:

- переводим градусы в радианы,
- считаем формулу Haversine,
- перемножаем на радиус Земли.

Важно:

- это **расстояние "по прямой"** (как птица/снаряд),
- по городу так курьер не поедет (если только не на ракете),
- поэтому дальше мы корректируем через:
  - OSRM (реальные дороги),
  - либо `ROAD_DISTANCE_COEFFICIENT` (если OSRM сдох).

---

## 5. `getPriceByDistance` — получить цену по расстоянию

```java
public BigDecimal getPriceByDistance(double distanceKm) {
    // Находим ближайший тариф >= расстояния
    for (Map.Entry<Integer, BigDecimal> entry : tariffs.entrySet()) {
        if (distanceKm <= entry.getKey()) {
            return entry.getValue();
        }
    }
    
    // Если расстояние больше максимального тарифа — берём максимальную цену
    // + добавляем по 100₽ за каждые 3 км сверх
    Map.Entry<Integer, BigDecimal> lastEntry = tariffs.lastEntry();
    int extraKm = (int) Math.ceil(distanceKm - lastEntry.getKey());
    int extraBlocks = (extraKm + 2) / 3; // Округляем вверх до блоков по 3 км
    BigDecimal extraPrice = new BigDecimal(extraBlocks * 100);
    
    return lastEntry.getValue().add(extraPrice);
}
```

Логика:

1. **Если расстояние в пределах наших тарифов**:
   - идём по `tariffs` по возрастанию,
   - как только `distanceKm <= ключ` — берём эту цену.
   - Пример:
     - расстояние 4.2 км → попадает в "до 5 км" → цена = тариф для 5 км.

2. **Если расстояние больше самого большого тарифа**:
   - берём максимум (скажем, "до 30 км = 2000₽"),
   - считаем, сколько **лишних** км сверху,
   - делим их на блоки по 3 км,
   - за каждый блок докидываем по 100₽.

Это чтобы:

- магазин не повёз букет в соседний город за те же деньги, что по району,
- и при этом не городить бесконечный список тарифов в конфиге.

---

## 6. Главный метод: `calculate` — нормальная доставка магазин → клиент

```java
public DeliveryCalculation calculate(double shopLat, double shopLon, 
                                     double deliveryLat, double deliveryLon) {
    // Пробуем получить расстояние по дорогам через OSRM (бесплатно)
    Double roadDistance = getOsrmDistance(shopLat, shopLon, deliveryLat, deliveryLon);
    
    // Если OSRM не сработал — используем коэффициент
    if (roadDistance == null) {
        double straightDistance = calculateDistance(shopLat, shopLon, deliveryLat, deliveryLon);
        roadDistance = straightDistance * ROAD_DISTANCE_COEFFICIENT;
        log.warn("OSRM недоступен, используем коэффициент: {} × {} = {} км", 
                String.format("%.1f", straightDistance), ROAD_DISTANCE_COEFFICIENT, 
                String.format("%.1f", roadDistance));
    }
    
    BigDecimal price = getPriceByDistance(roadDistance);
    
    // Округляем расстояние до 1 знака после запятой
    double roundedDistance = Math.round(roadDistance * 10.0) / 10.0;
    
    String description = String.format("%.1f км — %s₽", roundedDistance, price);
    
    log.info("Расчёт доставки: {} км = {}₽", roundedDistance, price);
    
    return new DeliveryCalculation(roundedDistance, price, description);
}
```

Пошагово:

1. **Пробуем OSRM** (`getOsrmDistance`):
   - это публичный API, который строит маршрут по дорогам,
   - даёт расстояние в метрах.
2. Если OSRM **упал/вернул ошибку**:
   - считаем по формуле Haversine,
   - домножаем на 1.6 (приблизительная разница "по прямой" vs "по дорогам").
3. По итоговому `roadDistance`:
   - считаем цену через `getPriceByDistance`,
   - округляем расстояние до одного знака,
   - собираем `description`.

И возвращаем `DeliveryCalculation`, которым уже пользуется `OrderCreationHandler`.

---

## 7. Магия OSRM: `getOsrmDistance`

```java
private Double getOsrmDistance(double lat1, double lon1, double lat2, double lon2) {
    try {
        String url = String.format(
                java.util.Locale.US,
                "https://router.project-osrm.org/route/v1/driving/%f,%f;%f,%f?overview=false",
                lon1, lat1, lon2, lat2  // OSRM принимает: lon,lat (не lat,lon!)
        );
        
        java.net.URL osrmUrl = new java.net.URL(url);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) osrmUrl.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            log.warn("OSRM вернул код: {}", responseCode);
            return null;
        }
        
        StringBuilder response = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
        }
        
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(response.toString());
        
        String code = root.get("code").asText();
        if (!"Ok".equals(code)) {
            log.warn("OSRM статус: {}", code);
            return null;
        }
        
        double distanceMeters = root.get("routes").get(0).get("distance").asDouble();
        double distanceKm = distanceMeters / 1000.0;
        
        double coefficient = calculateOsrmCoefficient(distanceKm);
        double correctedDistance = distanceKm * coefficient;
        
        log.info("OSRM расстояние: {} км × {} = {} км", 
                String.format("%.1f", distanceKm), 
                String.format("%.2f", coefficient),
                String.format("%.1f", correctedDistance));
        return correctedDistance;
        
    } catch (Exception e) {
        log.warn("OSRM ошибка: {}", e.getMessage());
        return null;
    }
}
```

Что тут важно:

- URL:
  - `route/v1/driving/ЛОНГ1,ЛАТ1;ЛОНГ2,ЛАТ2`,
  - порядок **lon,lat**, а не наоборот — классическая ловушка.

- Таймауты по 3 секунды:
  - чтобы бот не висел вечность, если OSRM умер.

- Разбор JSON:
  - проверяем, что `"code": "Ok"`,
  - берём `routes[0].distance` — это метры,
  - переводим в км.

- `calculateOsrmCoefficient`:
  - OSRM оптимистично считает, как будто дороги пустые,
  - реальный путь в городе часто длиннее,
  - поэтому домножаем на "калиброванный" коэффициент.

---

## 8. `calculateOsrmCoefficient` — динамическая поправка к OSRM

```java
private double calculateOsrmCoefficient(double distanceKm) {
    if (distanceKm <= 5) {
        return 1.0;
    } else if (distanceKm <= 12) {
        double progress = (distanceKm - 5) / 7.0;
        return 1.0 + (0.24 * progress);
    } else {
        return 1.24;
    }
}
```

Из комментария:

- до 5 км:
  - OSRM почти точен → коэффициент 1.0,
- от 5 до 12 км:
  - плавно растём от 1.0 до 1.24,
- дальше 12 км:
  - считаем, что OSRM стабильно занижает километраж → фикс 1.24.

Это "эмпирическая магия" из реальных замеров (комменты про Челябинск в коде).

---

## 9. Минимальная цена и описание тарифов

```java
public BigDecimal getMinPrice() {
    return tariffs.isEmpty() ? new BigDecimal("300") : tariffs.firstEntry().getValue();
}
```

- нужно для валидации в `OrderCreationHandler`:
  - если юзер вводит "100", а минималка "300",
  - мы его культурно посылаем: "введи от 300".

```java
public String getTariffDescription() {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<Integer, BigDecimal> entry : tariffs.entrySet()) {
        sb.append("• до ").append(entry.getKey()).append(" км — ")
          .append(entry.getValue()).append("₽\n");
    }
    return sb.toString();
}
```

- возвращает красивый текст‑прайс, который бот может показать магазину,
- честный answer на "а почему так дорого, вы что, офигели?".

---

## 10. Мультиадрес: доп. точки и общая стоимость

### 10.1. Константа и геттер

```java
private static final BigDecimal MIN_ADDITIONAL_STOP_PRICE = new BigDecimal("300");

public BigDecimal getMinAdditionalStopPrice() {
    return MIN_ADDITIONAL_STOP_PRICE;
}
```

- это минималка **за дополнительную точку**:
  - даже если дом "через дорогу" от предыдущего,
  - курьеру всё равно надо:
    - выйти,
    - донести,
    - позвонить,
    - подождать.
  - Поэтому "минимум 300₽, даже если 200 метров".

---

### 10.2. `calculateAdditionalStop` — доп. точка от предыдущей

```java
public DeliveryCalculation calculateAdditionalStop(double prevLat, double prevLon,
                                                   double deliveryLat, double deliveryLon) {
    Double roadDistance = getOsrmDistance(prevLat, prevLon, deliveryLat, deliveryLon);
    
    if (roadDistance == null) {
        double straightDistance = calculateDistance(prevLat, prevLon, deliveryLat, deliveryLon);
        roadDistance = straightDistance * ROAD_DISTANCE_COEFFICIENT;
        log.warn("OSRM недоступен для доп. точки, используем коэффициент: {} км", 
                String.format("%.1f", roadDistance));
    }
    
    BigDecimal price = getPriceByDistance(roadDistance);
    if (price.compareTo(MIN_ADDITIONAL_STOP_PRICE) < 0) {
        price = MIN_ADDITIONAL_STOP_PRICE;
    }
    
    double roundedDistance = Math.round(roadDistance * 10.0) / 10.0;
    
    String description = String.format("➕ +%.1f км — %s₽", roundedDistance, price);
    
    log.info("Расчёт доп. точки: {} км = {}₽", roundedDistance, price);
    
    return new DeliveryCalculation(roundedDistance, price, description);
}
```

Важные отличия от обычного `calculate`:

- считаем расстояние не **от магазина**, а **от предыдущей точки**:
  - потому что маршрут идёт: магазин → точка1 → точка2 → ...
  - и каждая доп. точка — это **дополнительный кусок пути**, а не новый заказ от нуля.

- цена считается по той же сетке, но:
  - если получилось меньше 300₽, всё равно ставим 300₽.

- `description` начинается с `➕` и `+X км`:
  - чтобы визуально было понятно — это надбавка за новый хвост маршрута.

---

### 10.3. `calculateMultiStopDelivery` — сразу цены для всех точек

```java
public BigDecimal[] calculateMultiStopDelivery(double shopLat, double shopLon, 
                                               double[][] stops) {
    if (stops == null || stops.length == 0) {
        return new BigDecimal[0];
    }
    
    BigDecimal[] prices = new BigDecimal[stops.length];
    
    // Первая точка — от магазина, полная цена
    DeliveryCalculation first = calculate(shopLat, shopLon, stops[0][0], stops[0][1]);
    prices[0] = first.price();
    
    // Остальные точки — от предыдущей точки, цена дополнительной точки
    for (int i = 1; i < stops.length; i++) {
        DeliveryCalculation additional = calculateAdditionalStop(
                stops[i-1][0], stops[i-1][1],
                stops[i][0], stops[i][1]
        );
        prices[i] = additional.price();
    }
    
    log.info("Мультиадресная доставка: {} точек, цены: {}", 
            stops.length, java.util.Arrays.toString(prices));
    
    return prices;
}
```

Аргументы:

- `shopLat/shopLon` — координаты магазина,
- `stops` — массив `[ [lat1, lon1], [lat2, lon2], ... ]`.

Логика:

- первая точка:
  - цена = обычный `calculate(магазин → точка1)`,
- каждая следующая:
  - цена = `calculateAdditionalStop(предыдущая → текущая)`,
  - с учётом минималки и тарифов.

Получаем массив:

- `[цена1, цена2, цена3, ...]`,
- суммой которого можно проверить/показать `ИТОГО` в мультистоп‑заказе.

---

## 11. Как всё это связано с OrderCreationHandler / OrderService

Схема:

```text
OrderCreationHandler:
    → получает адрес магазина и адрес клиента
    → через GeocodingService → lat/lon
    → вызывает DeliveryPriceService.calculate(...)
    → получает (distance, price, description)
    → пишет в OrderCreationData

Мультистоп:
    → для каждой доп. точки:
        getLastStopCoordinates() из OrderCreationData
        calculateAdditionalStop(...)
    → цены складываются в OrderCreationData / OrderStop

OrderService:
    → при создании Order / OrderStop
        использует уже ПОСЧИТАННЫЕ цены из OrderCreationData
        (сам ничего не считает)
```

То есть:

- `DeliveryPriceService` — **единственное место**, где живёт логика "сколько это стоит",
- всё остальное — просто:
  - собирает координаты,
  - вызывает нужный метод,
  - сохраняет результат.

---

## 12. Куда двигаемся дальше?

Теперь ты понимаешь:

- как считается расстояние,
- как строятся тарифы,
- чем отличается обычная доставка от доп. точки,
- как мультистоп разбивается на куски.

Можем дальше:

- либо разобрать **`GeocodingService`** (как мы получаем координаты и проверяем регион),
- либо уже идти в **курьерский функционал** (меню курьера, список доступных заказов, маршрут из 3 заказов и т.д.) и начать писать железо для Stage 3.  
Что интереснее прямо сейчас?  
