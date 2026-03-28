# Полный анализ проекта и модульная структура рефакторинга

Документ дополняет REFACTORING_PLAN.md: здесь учтён **весь** код проекта (не только Bot), подсчитаны объёмы, предложена разбивка по модулям (папкам) и план рефакторинга по каждому крупному классу.

---

## 1. Инвентаризация: весь код проекта

### 1.1 Java (src/main/java) — 52 уникальных файла, ~10 500+ строк

| Строк | Класс | Пакет | Назначение |
|-------|--------|--------|------------|
| 1544 | Bot | (root) | Точка входа, роутинг Update, меню, списки, отмена, уведомления |
| 1021 | OrderCreationHandler | handler | Создание заказа магазином (адрес, цена, дата, точки) |
| 954 | OrderService | service | CRUD заказов, назначение курьеру, отмена, возврат, комиссия, геокод |
| 876 | CallbackQueryHandler | handler | Все callback (роль, заказ, курьер, магазин, депозит, связки) |
| 549 | OrderBundleService | service | Связки заказов, OSRM/Haversine, маршруты Яндекс/2ГИС |
| 402 | CourierAvailableOrdersHandler | handler | Список доступных заказов, пагинация, гео, связки |
| 381 | DeliveryPriceService | service | Расчёт цены доставки, OSRM, тарифы |
| 363 | Order | model | Модель заказа |
| 349 | CourierGeoHandler | handler | Геолокация: «В магазине»/«Вручил», кнопка «В путь» |
| 320 | ShopRegistrationHandler | handler | Регистрация магазина (шаги, контакт) |
| 285 | OrderEditHandler | handler | Редактирование заказа (адрес, телефон, комментарий, дата) |
| 259 | GeocodingService | service | DaData Clean/Suggest, геокод адресов |
| 239 | CourierRegistrationHandler | handler | Регистрация курьера (ФИО, контакт, селфи) |
| 209 | StartCommandHandler | handler | /start, выбор роли, меню |
| 187 | OrderCreationData | model | Данные пошагового создания заказа + StopData |
| 169 | MyOrdersSelectionHandler | handler | Выбор заказа по номеру (магазин) |
| 160 | OrderStop | model | Точка мультиадресного заказа |
| 156 | CourierPenaltyService | service | Штрафы (отмены, гео, сговор) |
| 149 | UserService | service | Пользователи, роли, админы |
| 141 | CourierService | service | Курьеры, баланс, гео, активация |
| 129 | Courier | model | Модель курьера |
| 123 | UserRepository | repository | Запросы к users (в т.ч. кастомные) |
| 116 | Shop | model | Модель магазина |
| 112 | CourierDepositHandler | handler | Пополнение депозита (сумма, ЮKassa) |
| 99 | BundleCacheService | service | Кэш связок заказов |
| 99 | User | model | Модель пользователя |
| 93 | ShopService | service | Магазины, поиск по telegram |
| 91 | OrderStopRepository | repository | order_stops |
| 89 | YooKassaPaymentService | service | Создание платежа ЮKassa |
| 73 | CourierTransactionService | service | Транзакции депозита |
| 67 | OrderCreationState | model | Enum состояния создания заказа |
| 59 | RegionConfig | config | Город, область, 2ГИС/Яндекс |
| 56 | OrderRepository | repository | Запросы к orders |
| 52 | DeliveryInterval | model | Интервал доставки |
| 46 | GeoUtil | util | Haversine, радиус 200 м |
| 44 | CourierTransaction | model | Транзакция депозита |
| 40 | Role | model | Роль пользователя |
| 39 | ShopRepository | repository | shops |
| 39 | OrderStatusGeoSnapshot | model | Снимок гео при смене статуса |
| 35 | CourierGeoService | service | Сохранение снимков гео |
| 30 | CourierRegistrationData | model | Данные регистрации курьера |
| 30 | Config | (root) | Конфиг Spring (RestTemplate и т.д.) |
| 27 | StopStatus | model | Статус точки доставки |
| 21 | ShopRegistrationData | model | Данные регистрации магазина |
| 21 | OrderStatus | model | Статус заказа |
| 19 | CourierRegistrationState | model | Enum состояния регистрации курьера |
| 19 | OrderEditState | model | Состояние редактирования заказа |
| 18 | CourierRepository | repository | couriers |
| 17 | RegistrationState | model | Enum регистрации магазина |
| 13 | CourierStatus | model | Статус курьера |
| 13 | OrderStatusGeoSnapshotRepository | repository | order_status_geo_snapshots |
| 11 | CourierTransactionRepository | repository | courier_transactions |
| 11 | FlowerDeliveryApplication | (root) | Точка входа приложения |

**Крупные точки роста:** Bot (1544), OrderCreationHandler (1021), OrderService (954), CallbackQueryHandler (876), OrderBundleService (549), CourierAvailableOrdersHandler (402), DeliveryPriceService (381), Order (363), CourierGeoHandler (349), ShopRegistrationHandler (320), OrderEditHandler (285).

### 1.2 Ресурсы (src/main/resources)

| Строк | Файл | Назначение |
|-------|------|------------|
| 159 | application.properties | Конфиг (БД, Telegram, DaData, тарифы, регион, OSRM, штрафы) |
| 176 | V2__create_shops_table.sql | Таблица shops |
| 91 | V4__create_order_stops_table.sql | Таблица order_stops |
| 73 | V3__create_orders_table.sql | Таблица orders |
| 41 | V1__create_users_table.sql | Таблица users |
| 17 | V7__create_courier_transactions.sql | Таблица courier_transactions |
| 17 | V9__add_shop_pickup_override_to_orders.sql | Тестовые колонки override |
| 8 | V5__add_shop_pickup_confirmation_to_orders.sql | Подтверждение «Курьер забрал?» |
| 8 | V6__add_courier_balance_and_commission.sql | Баланс и комиссия курьера |
| 4 | V8__add_cancel_reason_to_orders.sql | Причина отмены |

### 1.3 Скрипты (scripts/)

Тестовые и утилитарные SQL: revert_shop_pickup_override, replace_shop_pickup_*, fix_delivery_coords_for_test, deposit_topup, reset_courier и др. — см. REFACTORING_PLAN.md и COMMIT_MSG_GEO_OSRM_BUNDLES.md (перед продом откат override).

---

## 2. Текущая структура пакетов (плоская)

```
org.example.flower_delivery
├── Bot.java
├── Config.java
├── FlowerDeliveryApplication.java
├── config/
│   └── RegionConfig.java
├── handler/          ← 11 классов, смешаны магазин + курьер + общее
│   ├── CallbackQueryHandler.java   (876 строк — все callback в одном)
│   ├── CourierAvailableOrdersHandler.java
│   ├── CourierDepositHandler.java
│   ├── CourierGeoHandler.java
│   ├── CourierRegistrationHandler.java
│   ├── MyOrdersSelectionHandler.java
│   ├── OrderCreationHandler.java
│   ├── OrderEditHandler.java
│   ├── ShopRegistrationHandler.java
│   └── StartCommandHandler.java
├── model/            ← 22 класса, смешаны заказ/магазин/курьер/пользователь
├── repository/       ← 7 интерфейсов JPA
├── service/          ← 14 классов, смешаны заказы/курьеры/магазины/гео/оплата
└── util/
    └── GeoUtil.java
```

Проблемы: нет границ модулей; один handler/service тянет за собой всё (Bot, OrderService, Shop, Courier, Order); CallbackQueryHandler раздут под все сценарии (role, order, courier, shop, deposit).

---

## 3. Целевая модульная структура (папки = модули)

Предлагаемая разбивка по **папкам-модулям** внутри одного приложения (без мультимодульного Maven — пока один модуль, но пакеты чётко разделены):

```
org.example.flower_delivery
├── Bot.java                    ← только точка входа + диспетчер
├── Config.java
├── FlowerDeliveryApplication.java
├── core/                       ← общее ядро
│   ├── dispatcher/             ← UpdateDispatcher, UpdateHandler
│   ├── telegram/               ← TelegramSender, отправка
│   └── dto/                    ← общие DTO при необходимости
├── common/                     ← общие сущности и сервисы
│   ├── model/                  ← User, Role (если общие)
│   ├── repository/             ← UserRepository
│   ├── service/                ← UserService
│   └── util/                   ← TextFormattingUtil, GeoUtil, RouteUrlBuilder
├── shop/                       ← модуль «Магазин»
│   ├── model/                  ← Shop, ShopRegistrationData, RegistrationState
│   ├── repository/             ← ShopRepository
│   ├── service/                ← ShopService
│   ├── handler/                ← ShopRegistrationHandler, OrderCreationHandler (или order в order)
│   ├── builder/                ← ShopOrderListBuilder (текст «Мои заказы»)
│   └── callback/               ← обработка callback: role_shop, shop_info, create_order, delivery_date_*, order_creation_*, add_stop_*, order_cancel_*, order_edit_*, orders_select
├── order/                      ← модуль «Заказ» (доменная логика заказа и доставки)
│   ├── model/                  ← Order, OrderStop, OrderStatus, StopStatus, DeliveryInterval, OrderCreationData, OrderCreationState, OrderEditState
│   ├── repository/             ← OrderRepository, OrderStopRepository
│   ├── service/                ← OrderService, DeliveryPriceService, GeocodingService (или geo в courier)
│   └── handler/                ← OrderEditHandler, MyOrdersSelectionHandler (или оставить в shop — «мои заказы» магазина)
├── courier/                    ← модуль «Курьер»
│   ├── model/                  ← Courier, CourierTransaction, CourierRegistrationData, CourierRegistrationState, CourierStatus, OrderStatusGeoSnapshot
│   ├── repository/             ← CourierRepository, CourierTransactionRepository, OrderStatusGeoSnapshotRepository
│   ├── service/                ← CourierService, CourierTransactionService, CourierPenaltyService, OrderBundleService, BundleCacheService
│   ├── handler/                ← CourierRegistrationHandler, CourierAvailableOrdersHandler, CourierGeoHandler, CourierDepositHandler
│   ├── callback/               ← courier_order_view, courier_order_take, courier_orders_page, courier_order_next, courier_stop_delivered, courier_deposit_topup, courier_tx_page, courier_cancel_select, courier_order_cancel_*, courier_order_return_*, courier_bundle_take, courier_phone
│   ├── builder/                ← CourierMyOrdersContentBuilder, CourierStatsContentBuilder, AvailableOrdersContentBuilder
│   └── geo/                    ← гео и отслеживание (часть курьерского модуля)
│       ├── CourierGeoHandler   (уже в handler)
│       ├── CourierGeoService
│       └── GeoUtil (или в common/util)
├── admin/                      ← модуль «Админ»
│   ├── service/                ← AdminNotificationService (уведомления админам о гео/отменах)
│   └── handler/                ← временные команды /k, /r (или вынести в admin/command)
├── payment/                    ← модуль «Оплата» (ЮKassa)
│   ├── service/                ← YooKassaPaymentService
│   └── (webhook controller при появлении — в этом же модуле)
└── config/
    └── RegionConfig.java
```

Итого модули: **core**, **common**, **shop**, **order**, **courier**, **admin**, **payment**, **config**.

---

## 4. Маппинг: текущий класс → целевой модуль

| Текущий класс | Целевой модуль | Примечание |
|---------------|-----------------|------------|
| Bot | core (root) | Только onUpdateReceived → dispatcher, getBotToken/Username |
| StartCommandHandler | common или core | /start, выбор роли — общая точка входа |
| User, UserService, UserRepository, Role | common | Общие пользователь и роли |
| Shop, ShopService, ShopRepository, ShopRegistrationData, RegistrationState | shop | Вся модель и сервис магазина |
| ShopRegistrationHandler | shop.handler | Регистрация магазина |
| OrderCreationHandler | shop.handler или order.handler | Создание заказа инициирует магазин; логика заказа — OrderService |
| Order, OrderStop, OrderStatus, StopStatus, DeliveryInterval, OrderCreationData, OrderCreationState, OrderEditState | order.model | |
| OrderRepository, OrderStopRepository | order.repository | |
| OrderService | order.service | Ядро домена «заказ»; зависит от shop, courier, payment |
| OrderEditHandler | order.handler или shop.handler | Редактирование заказа (магазин/админ) |
| MyOrdersSelectionHandler | shop.handler | «Мои заказы» магазина |
| GeocodingService | order.service или common | Геокод для адресов заказа и магазина |
| DeliveryPriceService | order.service | Цена доставки |
| Courier, CourierService, CourierRepository, CourierTransaction, CourierTransactionRepository, CourierTransactionService, CourierPenaltyService | courier | |
| CourierRegistrationHandler, CourierAvailableOrdersHandler, CourierGeoHandler, CourierDepositHandler | courier.handler | |
| CourierRegistrationData, CourierRegistrationState, CourierStatus | courier.model | |
| OrderBundleService, BundleCacheService | courier.service | Связки и маршруты — курьерский сценарий |
| CourierGeoService, OrderStatusGeoSnapshot, OrderStatusGeoSnapshotRepository | courier (geo) | Гео и снимки |
| YooKassaPaymentService | payment | |
| CallbackQueryHandler | разбить | Роль → StartCommandHandler/common; shop_*, order_creation_*, order_cancel_*, order_edit_* → shop/order; courier_* → courier; deposit → courier/payment |
| Config, RegionConfig | config / core | |
| GeoUtil | common.util | Расстояния, радиус 200 м |

---

## 5. Проблемы по классам (не только Bot)

| Класс | Проблема | Что делать |
|-------|----------|------------|
| **CallbackQueryHandler** (876 строк) | Один класс на все callback; десятки веток if/else по callbackData | Разбить по префиксам: RoleCallbackHandler, ShopOrderCallbackHandler, CourierCallbackHandler, CourierDepositCallbackHandler. Либо стратегии по callbackData.startsWith("…"). |
| **OrderCreationHandler** (1021 строка) | Огромный пошаговый сценарий, много веток по state | Разбить на шаги (state → отдельный класс или метод): AddressStep, PriceStep, DateStep, IntervalStep, RecipientStep, CommentStep. Или оставить один класс, но вынести построение сообщений и клавиатур в билдеры. |
| **OrderService** (954 строки) | В одном сервисе: создание заказа, назначение курьеру, отмена, возврат, комиссия, геокод, маршруты | Разделить по ответственности: OrderCreateService, OrderAssignService, OrderCancelReturnService; или оставить фасад OrderService, но вынести куски в приватные сервисы/хелперы. |
| **CourierAvailableOrdersHandler** (402) | Список заказов + пагинация + гео + связки + построение контента | Вынести построение текста/кнопок в AvailableOrdersContentBuilder; оставить в хендлере только оркестрацию и вызов Bot/Sender. |
| **DeliveryPriceService** (381) | Расчёт цены + вызов OSRM + тарифы | Можно оставить одним классом; при росте вынести TariffService и OsrmClient. |
| **Order** (363) | Много полей и методов | Уже нормально для агрегата; при необходимости вынести getEffectivePickup* в value object или helper. |
| **CourierGeoHandler** (349) | Гео + ожидание «В путь» + проверка 200 м | Оставить в courier; при росте вынести PendingState в отдельный сервис/хранилище. |
| **ShopRegistrationHandler** (320) | Пошаговая регистрация | Аналогично OrderCreation: шаги или билдеры сообщений. |
| **OrderEditHandler** (285) | Редактирование полей заказа | Можно оставить; при росте — один обработчик на тип поля (address, phone, comment, date). |

---

## 6. Порядок рефакторинга по всему проекту

Рекомендуемый порядок с учётом зависимостей и риска.

1. **Подготовка (ветка refactor, чеклист сценариев)** — как в REFACTORING_PLAN.md.
2. **Утилиты и общее (common)** — TextFormattingUtil, RouteUrlBuilder, GeoUtil уже в util; при желании вынести форматтеры из Bot сюда. Общие константы кнопок.
3. **TelegramSender и core.dispatcher** — уменьшить Bot, ввести диспетчер и обработчики по типу Update (как в REFACTORING_PLAN.md фазы 2–3).
4. **Вынос из Bot** — меню магазина, меню курьера, отмена/возврат, уведомления админам (фазы 4–8 плана).
5. **Разбиение CallbackQueryHandler** — по префиксам callback: отдельные классы или внутренние делегаты (shop/order/courier/deposit). Регистрация в диспетчере или внутри одного фасада CallbackQueryHandler, который только роутит по callbackData.
6. **Модульные пакеты (shop, order, courier, admin, payment)** — постеенный перенос классов в новые пакеты с сохранением импортов (сначала копируем в новый пакет, обновляем импорты, удаляем старый файл). Делать по одному модулю (например сначала payment, потом shop, order, courier, admin).
7. **OrderService и OrderCreationHandler** — после стабилизации модулей: разбиение OrderService на подсервисы или выделение шагов OrderCreation в отдельные классы.
8. **Документация и чистка** — обновить README/архитектуру, убрать мёртвый код, зафиксировать границы модулей в REFACTORING_PLAN.md.

---

## 7. Зависимости между модулями (целевые)

- **core** — не зависит от shop/order/courier (только Telegram API и, при необходимости, common).
- **common** — только model/repository/user, util, config.
- **shop** — зависит от common, order (Shop → заказы магазина).
- **order** — зависит от common, shop (Order → shop_id), courier (назначение), geo (GeocodingService можно оставить в order или common).
- **courier** — зависит от common, order (заказы, связки, статусы).
- **admin** — зависит от common, courier (уведомления о курьерах).
- **payment** — зависит от common (User/Courier для метаданных платежа); courier использует payment для пополнения депозита.

Итог: зависимости идут в одну сторону (core ← common ← shop/order/courier ← admin, payment подключается к courier). Циклических зависимостей избегать (Bot → диспетчер → хендлеры; хендлеры используют сервисы, а не Bot, кроме отправки через TelegramSender).

---

В следующей итерации можно добавить в этот файл таблицу «по файлам» с точным путём после переноса (например `ShopRegistrationHandler` → `org.example.flower_delivery.shop.handler.ShopRegistrationHandler`) и список изменений импортов по пакетам.
