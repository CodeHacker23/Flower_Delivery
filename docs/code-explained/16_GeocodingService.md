## GeocodingService.java — "где, чёрт возьми, этот адрес?"

> Курьер хочет знать, **куда** ехать.  
> `GeocodingService` — это сервис, который берёт грязный человеческий текст `"ул. Ленина 44, подъезд 2, кв. 15"`  
> и превращает его в:
> - нормальный, очищенный адрес для DaData,
> - координаты \(lat, lon\),
> - город/регион, чтобы мы не поехали в соседнюю область.

Импорты, `@Service`, `@Slf4j` пропускаем — ты их уже наизусть знаешь.

---

## 1. Конфиг, ObjectMapper и API‑ключ

```java
private static final String DADATA_URL = "https://suggestions.dadata.ru/suggestions/api/4_1/rs/suggest/address";

private final RegionConfig regionConfig;
private final ObjectMapper objectMapper = new ObjectMapper();
private final RestTemplate restTemplate;

@Value("${dadata.api-key}")
private String apiKey;
```

- **`DADATA_URL`**:
  - конечная точка DaData **подсказок по адресам**,
  - не полный геокодер Яндекса, а сервис "додумай за пользователя".

- **`RegionConfig regionConfig`**:
  - отдельный конфиг‑класс (мы его уже упоминали),
  - знает:
    - город (типа `"Челябинск"`),
    - область/регион (типа `"Челябинская область"`),
  - умеет:
    - "обогащать" адрес — добавлять город к строке:

      ```java
      regionConfig.enrichAddress("ул. Ленина 44")
      // → "Челябинск, ул. Ленина 44"
      ```

- **`ObjectMapper objectMapper`**:
  - Jackson для разбора JSON‑ответа DaData.

- **`RestTemplate restTemplate`**:
  - формально есть,
  - но реальный запрос к DaData в этом классе сделан руками через `HttpURLConnection` (чуть ниже).
  - `restTemplate` здесь больше как запасной вариант/исторический артефакт.

- **`apiKey`**:
  - подтягивается из `application.properties`:
    - `dadata.api-key=...`,
  - используется в HTTP‑заголовке `Authorization: Token ...`.

Аналогия:

- `GeocodingService` — чувак, который звонит "девочке из справочной" (DaData):
  - `RegionConfig` — это "в каком вообще городе мы живём",
  - `apiKey` — это "кодовое слово, что нас пустили в чат с ней".

---

## 2. DTO результата: `GeocodingResult`

```java
public record GeocodingResult(
        double latitude,
        double longitude,
        String fullAddress,  // Полный адрес от DaData
        String city,         // Город
        String region        // Область
) {}
```

Когда всё получилось, мы хотим знать:

- **`latitude` / `longitude`**:
  - координаты точки на карте в десятичных градусах,
  - будут потом отданы в `DeliveryPriceService` для расчёта расстояния.

- **`fullAddress`**:
  - это `value` из ответа DaData:
    - уже нормализованный, красиво оформленный адрес,
    - без кривых сокращений.

- **`city` / `region`**:
  - город и область из `data.city` / `data.region`:
  - нужны, чтобы:
    - понять, попали ли мы в **нужный регион** (`isInAllowedRegion`),
    - и не поехать случайно в "Челябинск, Московская область".

---

## 3. `geocode` — главный метод: из каши текста в координаты

```java
public Optional<GeocodingResult> geocode(String address) {
    try {
        String cleanAddress = cleanAddressForGeocoding(address);
        
        String fullAddress = regionConfig.enrichAddress(cleanAddress);
        log.info("=== DADATA GEOCODING ===");
        log.info("Адрес для геокодирования: {}", fullAddress);
        log.info("API Key (первые 10 символов): {}...", apiKey.substring(0, Math.min(10, apiKey.length())));

        // ... подготовка HTTP-запроса ...
```

### 3.1. Чистим адрес: убираем подъезды/квартиры

```java
String cleanAddress = cleanAddressForGeocoding(address);
```

- Пользователь пишет полную простыню:
  - `"ул. Ленина 44, подъезд 2, кв. 15"`,
  - `"Российская 59а п2 кв 25"`.
- DaData подсказки **не любят** подъезды/квартиры:
  - им нужен адрес до уровня дома:
    - "город, улица, дом".
- Поэтому у нас есть отдельный метод `cleanAddressForGeocoding` (разберём ниже):
  - он выкидывает `"подъезд 2"`, `"п2"`, `"кв. 15"`,
  - оставляя ядро адреса.

### 3.2. Добавляем город/регион

```java
String fullAddress = regionConfig.enrichAddress(cleanAddress);
```

- Если юзер ввёл просто `"Ленина 44"`:
  - без города, без области,
  - DaData может догадаться, а может и улететь в другой регион.
- `enrichAddress` добавляет "контекст":
  - `"Челябинск, Ленина 44"`,
  - `"Челябинская область, Челябинск, Ленина 44"`.

Это сильно повышает шанс, что геокод попадёт туда, куда надо.

---

### 3.3. Собираем HTTP‑запрос к DaData

Дальше код идёт не через `RestTemplate`, а через низкоуровневый `HttpURLConnection` — чтобы не париться с кодировками и видеть всё под микроскопом.

```java
java.net.URL url = new java.net.URL(DADATA_URL);
java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
conn.setRequestMethod("POST");
conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
conn.setRequestProperty("Accept", "application/json");
conn.setRequestProperty("Authorization", "Token " + apiKey);
conn.setDoOutput(true);
```

Критичные вещи:

- `POST` на `DADATA_URL`,
- `Content-Type: application/json; charset=utf-8`:
  - чтобы русские буквы в адресе не поехали в ад,
- `Authorization: Token <apiKey>`:
  - DaData подсказки запускаются только с токеном,
  - **секретный ключ не нужен**, только публичный API‑ключ.

Формируем JSON‑тело:

```java
String jsonQuery = "{\"query\":\"" + fullAddress.replace("\"", "\\\"") + "\",\"count\":1}";
byte[] jsonBytes = jsonQuery.getBytes(StandardCharsets.UTF_8);

try (java.io.OutputStream os = conn.getOutputStream()) {
    os.write(jsonBytes);
    os.flush();
}
```

- JSON в стиле:

```json
{"query":"Челябинск, ул. Ленина 44","count":1}
```

- `count: 1` — просим только **одну лучшую подсказку**,
- экранируем кавычки, кодируем в UTF‑8, шлём в тело запроса.

---

### 3.4. Читаем ответ

```java
int responseCode = conn.getResponseCode();
log.info("DaData response code: {}", responseCode);

StringBuilder responseBody = new StringBuilder();
try (java.io.BufferedReader br = new java.io.BufferedReader(
        new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
    String line;
    while ((line = br.readLine()) != null) {
        responseBody.append(line);
    }
}

String responseStr = responseBody.toString();
log.info("DaData response body: {}", responseStr);

if (responseCode != 200 || responseStr.isEmpty()) {
    log.error("DaData вернул ошибку: status={}", responseCode);
    return Optional.empty();
}
```

- Если код не 200 или тело пустое:
  - логируем ошибку,
  - возвращаем `Optional.empty()`:
    - наверху `OrderCreationHandler` это увидит и скажет:
      - "геокод не удался, введи цену вручную".

---

### 3.5. Парсим JSON и вытаскиваем координаты

```java
JsonNode root = objectMapper.readTree(responseStr);
JsonNode suggestions = root.get("suggestions");

if (suggestions == null || !suggestions.isArray() || suggestions.size() == 0) {
    log.warn("DaData не нашёл адрес: {}", fullAddress);
    return Optional.empty();
}

JsonNode firstSuggestion = suggestions.get(0);
JsonNode data = firstSuggestion.get("data");
```

- В ответе DaData есть массив `suggestions`:
  - мы берём **первую** (самую релевантную),
  - внутри у неё есть:
    - поле `value` — полный адрес,
    - поле `data` — структурные детали: `geo_lat`, `geo_lon`, `city`, `region`, и т.п.

Проверяем координаты:

```java
JsonNode geoLat = data.get("geo_lat");
JsonNode geoLon = data.get("geo_lon");

if (geoLat == null || geoLon == null || 
    geoLat.isNull() || geoLon.isNull() ||
    geoLat.asText().isEmpty() || geoLon.asText().isEmpty()) {
    log.warn("DaData не нашёл координаты для адреса: {}", fullAddress);
    return Optional.empty();
}

double lat = Double.parseDouble(geoLat.asText());
double lon = Double.parseDouble(geoLon.asText());
```

- Если `geo_lat`/`geo_lon` пустые:
  - смысла дальше нет,
  - возвращаем `empty`.

- Иначе:
  - парсим в `double`,
  - вот те самые координаты, что полетят в `DeliveryPriceService`.

---

### 3.6. Достаём город/регион/человеческий адрес и собираем результат

```java
String city = getTextOrEmpty(data, "city");
String region = getTextOrEmpty(data, "region");
String resultAddress = getTextOrEmpty(firstSuggestion, "value");

log.info("Геокодирование успешно: lat={}, lon={}, city={}, region={}", 
        lat, lon, city, region);

return Optional.of(new GeocodingResult(lat, lon, resultAddress, city, region));
```

- `city` и `region`:
  - нужны дальше для `isInAllowedRegion`,
  - и просто для логов/отладки.

- `resultAddress` (`value`):
  - это нормализованная строка,
  - именно её мы показываем в боте:
    - `"Челябинск, улица Ленина, дом 44"` вместо "ленина 44 подъезд 2 кв 15".

Если где‑то выше что‑то пошло по жопе — ловим исключения:

```java
} catch (HttpClientErrorException e) { ... } 
catch (Exception e) { ... }
```

- логируем статус/тело/тип ошибки,
- возвращаем `Optional.empty()`,
- **никого не роняем** — бот просто предложит ручной режим.

---

## 4. `cleanAddressForGeocoding` — резня подъездов и квартир

```java
private String cleanAddressForGeocoding(String address) {
    String clean = address;
    
    // Убираем подъезд в разных форматах
    clean = clean.replaceAll("(?i)[,\\s]*(подъезд|подьезд|п\\.?|под\\.?)\\s*\\d+", "");
    
    // Убираем квартиру в разных форматах  
    clean = clean.replaceAll("(?i)[,\\s]*(квартира|кв\\.?|к\\.)\\s*\\d+", "");
    
    // Убираем лишние пробелы и запятые в конце
    clean = clean.replaceAll("[,\\s]+$", "").trim();
    
    log.info("Адрес очищен: '{}' → '{}'", address, clean);
    return clean;
}
```

Разбор:

- `(?i)` — регистронезависимый режим:
  - ловит `"подъезд"`, `"ПОДЪЕЗД"`, `"Подъезд"`.

- `[,\\s]*(подъезд|подьезд|п\\.?|под\\.?)\\s*\\d+`:
  - `[,\s]*` — возможная запятая/пробелы перед словом,
  - `(подъезд|подьезд|п\.?|под\.?)` — все популярные варианты:
    - `"подъезд"`,
    - `"подьезд"` (опечатка),
    - `"п"`, `"п."`,
    - `"под."`,
  - `\s*\d+` — потом номер `"2"`, `" 3"` и т.п.

- Аналогично с `"квартира|кв.|к."`:
  - режем хвост `"кв 15"`, `"к.25"`, `"квартира 1488"`.

- В конце:
  - срезаем запятые/пробелы в хвосте,
  - делаем `trim()`.

Пример:

- `"Российская 59а п2 кв 25"` → `"Российская 59а"`,
- `"ул. Ленина 44, подъезд 2, кв. 15"` → `"ул. Ленина 44"`.

Для DaData это идеальный формат:

- дом найдёт она,
- подъезд/квартиру мы уже храним **в комментариях к заказу**, а не в геокоде.

---

## 5. `isInAllowedRegion` — не едем ли мы в "не наш" регион

```java
public boolean isInAllowedRegion(GeocodingResult result) {
    String allowedArea = regionConfig.getArea().toLowerCase();
    String resultRegion = result.region().toLowerCase();
    
    boolean allowed = resultRegion.contains(allowedArea.replace(" область", "")) ||
                      allowedArea.contains(resultRegion.replace(" область", ""));
    
    if (!allowed) {
        log.warn("Адрес не в разрешённом регионе: {} (ожидается {})", 
                result.region(), regionConfig.getArea());
    }
    
    return allowed;
}
```

Тут мы делаем простую, но полезную проверку:

- `allowedArea`:
  - то, что сконфигурировано, например `"Челябинская область"`.

- `resultRegion`:
  - то, что вернула DaData, например `"Челябинская обл"` или чуть другой регистр.

Сравнение:

- убираем `" область"` из строки,
- сравниваем по принципу `contains` в обе стороны:
  - на случай, если формат слегка отличается.

Если регион не совпал:

- логируем варнинг,
- возвращаем `false`:
  - дальше `OrderCreationHandler`:
    - не будет автосчитать цену,
    - предложит ручной ввод,
    - и даст понять, что адрес вне зоны доставки.

Аналогия:

- "Мы возим только по **нашему** городу/области.  
  Если кто‑то внезапно спросил доставку в Сочи — это не к нам."

---

## 6. Вспомогательное: `getTextOrEmpty`

```java
private String getTextOrEmpty(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return (value != null && !value.isNull()) ? value.asText() : "";
}
```

- Мини‑утилита:
  - не хотим каждый раз писать:
    - "если поле есть и не null, то возьми текст, иначе пусто".
  - используется для `city`, `region`, `value`.

---

## 7. Как `GeocodingService` вписывается в общую схему

С точки зрения остальных классов:

- **`OrderCreationHandler`**:

  ```text
  текст адреса → geocodingService.geocode(...)
      ↓
  Optional<GeocodingResult>
      ↓ если empty → ручной режим (ввести цену самому)
      ↓ если present:
          - берём lat/lon
          - проверяем isInAllowedRegion(...)
          - передаём координаты в DeliveryPriceService
  ```

- **`DeliveryPriceService`**:
  - не знает ничего про DaData/города/регионы,
  - ему просто дают два набора координат.

- **`RegionConfig`**:
  - задаёт "игровую зону":
    - где наш город,
    - какая область считается "нашей".

Итого:

- `GeocodingService` отвечает за:
  - чистку адреса,
  - правильный контекст (город),
  - запрос к DaData,
  - координаты,
  - проверку "это наш регион или леса Амазонки".

---

## 8. Что дальше?

Сейчас у тебя есть:

- понимание, как из грязного текста `"ленина 44 п2 кв 15"` мы получаем:
  - `"Челябинск, улица Ленина, дом 44"`,
  - координаты,
  - инфу по региону,
- и как дальше этим пользуются `OrderCreationHandler` + `DeliveryPriceService`.

Дальше логичные варианты:

- либо перейти уже к **курьерскому функционалу** (меню курьера, список доступных заказов, маршруты из 3 заказов),
- либо взять ещё какой‑то сервис/хендлер, который тебе кажется тёмным лесом, и разобрать его в таком же стиле.  
Кого мучаем следующим: **курьеров** или ещё один сервис/handler?  
