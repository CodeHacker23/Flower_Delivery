# План тотального рефакторинга (ветка refactor)

Цель: читаемый, безопасный код, легко добавлять фичи. Много маленьких классов, интерфейсов, сервисов вместо одного гигантского Bot и цепочек if.

**Полный анализ всего проекта** (все папки, файлы, классы, объёмы кода, модульная разбивка shop/courier/admin/order/payment, маппинг классов и порядок рефакторинга по всему коду) — в документе **[REFACTORING_FULL_PROJECT_ANALYSIS.md](REFACTORING_FULL_PROJECT_ANALYSIS.md)**. Ниже — фокус на Bot и диспетчере; в полном анализе учтены CallbackQueryHandler, OrderService, OrderCreationHandler и остальные крупные классы.

---

## 1. Текущие проблемы

| Проблема | Где | Последствие |
|----------|-----|-------------|
| **Bot.java ~1680 строк** | Вся логика роутинга, меню, тексты, клавиатуры, уведомления | Невозможно быстро понять, куда добавить фичу; легко сломать при правке |
| **Длинная цепочка if в processUpdate()** | Bot.processUpdate | Добавление нового типа апдейта = новый if; порядок важен и неочевиден |
| **Смешение ответственностей** | Bot | Роутинг, построение текста списка заказов, построение клавиатур, отправка в Telegram, уведомления админам — всё в одном классе |
| **Дублирование проверок** | Разные handle* | courierOpt.isEmpty(), !courier.getIsActive() повторяются; нет единой точки входа «курьер активен» |
| **Статичные хелперы внутри Bot** | truncateForButton, streetAndHouseOnly, shortAddressForButton, escapeMarkdown, buildYandexRouteUrl, build2GisRouteUrl | Не тестируются отдельно, засоряют класс |
| **Состояние отмены/возврата в Bot** | awaitingCancelSelection, awaitingCancelReason | Должно жить в отдельном хендлере (CourierCancelFlowHandler) |
| **Большие методы построения контента** | buildCourierMyOrdersContent, buildCourierStatsContent, buildAvailableOrdersContentWithLocation | 50–150 строк каждый; логику удобнее вынести в билдеры/сервисы |

---

## 2. Целевая архитектура (кратко)

- **Bot** — только: получение Update → вызов диспетчера; отправка сообщений в Telegram (тонкая обёртка над execute); getBotToken/getBotUsername.
- **UpdateDispatcher** (или цепочка обработчиков) — решает, какой обработчик примет Update; без длинных if, через список/цепочку `UpdateHandler`.
- **Обработчики по типу контента**: уже есть (StartCommandHandler, CallbackQueryHandler, …); вынести из Bot оставшуюся логику кнопок меню (Мои заказы магазина, Мои заказы курьера, Статистика, Доступные заказы, Отмена/возврат).
- **Сервисы «контента»** (билдеры текста и клавиатур): ShopOrderListBuilder, CourierMyOrdersContentBuilder, CourierStatsContentBuilder, AvailableOrdersContentBuilder — возвращают DTO (текст + markup), Bot или хендлеры только отправляют.
- **Сервис отправки сообщений** (TelegramSender / BotApiWrapper): обёртка над execute(SendMessage), execute(EditMessageText), чтобы не таскать Bot везде; тестируемо через интерфейс.
- **Утилиты в отдельных классах**: TextFormattingUtil (truncate, shortAddress, streetAndHouseOnly, escapeMarkdown), RouteUrlBuilder (Яндекс/2ГИС) — без привязки к Bot.
- **Единые точки проверки ролей**: например CourierAuthService.canActAsCourier(telegramId) → Optional<Courier>; ShopAuthService.canActAsShop(telegramId) → Optional<Shop>; вызывать в хендлерах, а не дублировать if.

---

## 2.1. Принципы ООП (SOLID), на которые опираемся

Рефакторинг делаем по **SOLID**; в первую очередь используем **S**, **O** и **D**.

| Принцип | Как применяем в проекте |
|--------|---------------------------|
| **S — Single Responsibility** | Один класс — одна зона ответственности: Bot только точка входа и диспетчер; хендлер — один тип апдейта/кнопки; билдер — один вид контента (список заказов, статистика); утилита — форматирование или URL. Никакого «Bot делает всё». |
| **O — Open/Closed** | Расширяем без правки ядра: новый тип апдейта = новый `UpdateHandler` и регистрация в диспетчере; новый callback = новый обработчик или стратегия. Bot и диспетчер не меняем. |
| **L — Liskov Substitution** | Все реализации `UpdateHandler` и `TelegramSender` подставляем друг вместо друга без поломки контракта (в тестах — моки). |
| **I — Interface Segregation** | Узкие интерфейсы: `TelegramSender` (отправка), `CourierMyOrdersContentBuilder` (построение одного экрана), а не один «God»-интерфейс. |
| **D — Dependency Inversion** | Зависим от абстракций: хендлеры получают `TelegramSender`, а не `Bot`; диспетчер работает со списком `UpdateHandler`. Тесты подставляют заглушки. |

Практически важнее всего: **SRP** (разнести гигантский Bot и жирный CallbackQueryHandler) и **DIP** (TelegramSender, UpdateHandler), затем **OCP** (добавление фич новыми классами, а не правкой старых).

---

## 3. Что куда выносить (справочник)

| Сейчас в Bot | Куда вынести | Новый класс/интерфейс |
|--------------|--------------|------------------------|
| processUpdate (цепочка if) | Диспетчер + обработчики | UpdateDispatcher, UpdateHandler (interface) |
| handleMyOrdersButton, handleShopInfoButton, sendShopMenu | Один хендлер/сервис для меню магазина | ShopMenuHandler или ShopMenuService + ShopOrderListBuilder |
| handleCourierAvailableOrdersButton, buildAvailableOrdersContentWithLocation, sendLocationRequestForAvailableOrders, editAvailableOrdersMessage | Уже частично в CourierAvailableOrdersHandler | Достроить: AvailableOrdersContentBuilder (или в Handler), отправку оставить в Bot/Sender |
| handleCourierMyOrdersButton, buildCourierMyOrdersContent, editCourierMyOrdersMessage | Сервис + билдер | CourierMyOrdersHandler (или оставить вызов в Bot), CourierMyOrdersContentBuilder |
| handleCourierStatsButton, buildCourierStatsContent, editCourierStatsMessage | Аналогично | CourierStatsHandler / CourierStatsContentBuilder |
| startCourierCancelSelection, handleCourierCancelSelectionText, handleCourierCancelReasonText, awaitingCancelSelection, awaitingCancelReason, startAwaitingCancelReason | Отдельный хендлер состояния | CourierCancelFlowHandler (со своим состоянием) |
| sendCourierMenu, sendCourierMenuPlain, sendShopMenu | Общий сервис клавиатур + отправка | MenuKeyboardService + TelegramSender |
| sendShopPickupConfirmationRequest, sendReturnToShopRoute, sendSimpleMessage | Отправка сообщений | TelegramSender (или оставить в Bot как фасад execute) |
| notifyAdminsAboutCourierGeoIssue, notifyAdminsAboutSuspiciousCancel | Сервис уведомлений | AdminNotificationService (использует TelegramSender) |
| handleActivateCommand, handleActivateCourierCommand | Временные команды | Оставить в Bot или вынести в AdminTestCommandHandler |
| truncateForButton, streetAndHouseOnly, shortTimeForButton, shortAddressForButton, escapeMarkdown | Утилиты | TextFormattingUtil (или разбить на MessageFormattingUtil + AddressFormattingUtil) |
| buildYandexRouteUrl, build2GisRouteUrl | Уже есть в OrderBundleService для связок; для одиночного маршрута | RouteUrlBuilder (общий) или оставить в одном месте |
| nextStatusForCourier | Модель/enum | OrderStatus.nextForCourier() или OrderStatusTransitionService |
| PendingCancelReason, CourierMyOrdersContent, CourierStatsContent, AvailableOrdersContent | DTO остаются | Вынести в model/dto или оставить рядом с билдерами |

---

## 4. Интерфейсы (ввести по мере рефакторинга)

```java
/** Обработчик одного типа Update. Первый canHandle(update) == true обрабатывает. */
public interface UpdateHandler {
    boolean canHandle(Update update);
    void handle(Update update);
}

/** Отправка сообщений в Telegram (чтобы не зависеть от Bot в тестах). */
public interface TelegramSender {
    void sendMessage(Long chatId, String text, ParseMode parseMode, InlineKeyboardMarkup markup);
    void editMessage(Long chatId, Integer messageId, String text, InlineKeyboardMarkup markup);
    void sendMessagePlain(Long chatId, String text);
}

/** Построение текста и клавиатуры для списка «Мои заказы» курьера. */
public interface CourierMyOrdersContentBuilder {
    CourierMyOrdersContent build(Courier courier, List<Order> orders);
}
```

(Аналогично для других билдеров при необходимости — или сразу конкретные классы без интерфейса, если не планируем подмену в тестах.)

---

## 5. Фазы рефакторинга (порядок, чтобы не сломать всё сразу)

### Фаза 0: Подготовка
- Создать ветку `refactor` (или уже создана).
- Зафиксировать текущие тесты/ручные сценарии; после каждого шага проверять, что бот отвечает так же.

### Фаза 1: Утилиты и DTO (низкий риск)
- Вынести в `util.TextFormattingUtil`: truncateForButton, streetAndHouseOnly, shortAddressForButton, shortTimeForButton, escapeMarkdown.
- Вынести в `util.RouteUrlBuilder` (или в config): buildYandexRouteUrl, build2GisRouteUrl — и использовать из Bot и OrderBundleService при необходимости.
- Вынести record'ы/DTO: PendingCancelReason, CourierMyOrdersContent, CourierStatsContent — в пакет `dto` или оставить рядом с билдерами.
- **Результат:** Bot перестаёт содержать статичные хелперы; остальной код пока вызывает их из новых классов.

### Фаза 2: TelegramSender и отправка
- Ввести интерфейс `TelegramSender` с методами sendMessage, editMessage, sendMessagePlain.
- Реализация `BotTelegramSender` держит ссылку на Bot и вызывает execute().
- В Bot оставить только getBotToken, getBotUsername, onUpdateReceived → processUpdate, и внедрить TelegramSender туда, где сейчас execute() (постепенно: сначала новые сервисы используют Sender, Bot по-прежнему шлёт сам там, где ещё не вынесли).
- **Результат:** Все новые хендлеры/сервисы получают TelegramSender и не зависят от Bot напрямую.

### Фаза 3: Диспетчер Update (убрать if-цепочку)
- Ввести интерфейс `UpdateHandler` (canHandle, handle).
- Реализации: CallbackQueryUpdateHandler (делегирует в CallbackQueryHandler), ContactUpdateHandler (магазин/курьер), PhotoUpdateHandler, LocationUpdateHandler, TextUpdateHandler (внутри текста — свои подобработчики или один TextUpdateHandler с внутренней цепочкой).
- `UpdateDispatcher` — список обработчиков; в processUpdate вызывать `dispatcher.dispatch(update)`.
- **Результат:** processUpdate в Bot — одна строка; добавление нового типа апдейта = новый класс UpdateHandler и регистрация в диспетчере.

### Фаза 4: Вынос «меню» магазина
- `ShopMenuHandler`: обработка кнопок «Мой магазин», «Мои заказы» (текст); использует ShopService, OrderService, MyOrdersSelectionHandler, TelegramSender.
- `ShopOrderListBuilder` (или метод в сервисе): построение текста списка заказов магазина и кнопки «Выбрать заказ»; вызывается из ShopMenuHandler.
- В Bot убрать handleMyOrdersButton, handleShopInfoButton; вместо этого в роутинге текста вызывать shopMenuHandler.handle(update) если текст — «🏪 Мой магазин» / «📋 Мои заказы».

### Фаза 5: Вынос «меню» курьера (Мои заказы, Статистика, Доступные заказы)
- `CourierMyOrdersContentBuilder`: построение текста и клавиатуры для «Мои заказы» курьера (логика из buildCourierMyOrdersContent).
- `CourierStatsContentBuilder`: построение текста и клавиатуры для «Моя статистика».
- `CourierMenuHandler` (или разбить на CourierMyOrdersHandler, CourierStatsHandler): обработка кнопок «🚚 Мои заказы», «💰 Моя статистика»; использует билдеры, TelegramSender, editCourierMyOrdersMessage → вызов Sender.editMessage.
- Доступные заказы: перенести построение контента из Bot.buildAvailableOrdersContentWithLocation в CourierAvailableOrdersHandler или в AvailableOrdersContentBuilder; вызов отправки — через TelegramSender.
- **Результат:** Bot не содержит handleCourierMyOrdersButton, handleCourierStatsButton, buildCourierMyOrdersContent, buildCourierStatsContent, buildAvailableOrdersContentWithLocation; всё в хендлерах/билдерах.

### Фаза 6: Отмена/возврат заказа курьером
- `CourierCancelFlowHandler`: состояние awaitingCancelSelection, awaitingCancelReason; методы startCourierCancelSelection, startAwaitingCancelReason, handleCancelSelectionText, handleCancelReasonText.
- Bot отдаёт ему ввод текста, когда определит, что курьер в режиме отмены/возврата (или диспетчер передаёт в CourierCancelFlowHandler по canHandle).
- Уведомление магазина и sendReturnToShopRoute вызываются из CourierCancelFlowHandler (через OrderService + TelegramSender).
- **Результат:** В Bot нет состояния отмены и нет handleCourierCancel*.

### Фаза 7: Уведомления админам и прочие отправки
- `AdminNotificationService`: notifyAdminsAboutCourierGeoIssue, notifyAdminsAboutSuspiciousCancel; внутри — UserService.findActiveAdmins() и TelegramSender.
- Bot или хендлеры вызывают AdminNotificationService вместо методов Bot.
- sendShopPickupConfirmationRequest, sendReturnToShopRoute перенести в сервис (например OrderNotificationService или оставить в Bot как тонкие обёртки над TelegramSender, если они используются из нескольких мест).
- **Результат:** Bot не содержит логики уведомлений админам.

### Фаза 8: Финальная зачистка Bot
- Оставить в Bot: onUpdateReceived → dispatcher.dispatch(update); getBotToken; getBotUsername; создание/внедрение TelegramSender (реализация BotTelegramSender(this)).
- Все оставшиеся вызовы execute() заменить на вызовы через TelegramSender или через хендлеры/сервисы, которым передан Sender.
- Удалить из Bot все перенесённые методы и поля.
- **Результат:** Bot — несколько десятков строк, только точка входа и конфиг бота.

---

## 6. Дополнительно (по желанию)

- **Единая проверка «курьер активен»**: метод `CourierService.getActiveCourierOrEmpty(telegramId)` и использовать везде вместо повторяющихся if.
- **Константы кнопок**: вынести строки "📋 Доступные заказы", "🚚 Мои заказы" и т.д. в константы (например BotButtonLabels или в соответствующие хендлеры).
- **OrderStatus.nextForCourier()**: метод в enum OrderStatus, чтобы не держать nextStatusForCourier в Bot.
- **CallbackQueryHandler (936 строк)** в следующей итерации разбить по типам callback (courier_*, shop_*, order_*) на под-хендлеры или стратегии.

---

## 7. Критерии готовности

- Bot.java < 200 строк (в идеале < 100).
- processUpdate без цепочек if (диспетчер + обработчики).
- Нет статичных хелперов в Bot; форматирование и URL маршрутов — в утилитах/билдерах.
- Новую фичу (новая кнопка меню, новый тип апдейта) можно добавить новым классом без правки гигантского метода.
- Ключевые сценарии (регистрация, создание заказа, взятие заказа курьером, смена статусов, отмена, статистика) проходят после рефакторинга.

---

## 8. Рефакторинг остального проекта (не только Bot)

- **CallbackQueryHandler** (~876 строк): разбить по типам callback на под-хендлеры (shop/order, courier, deposit) — см. [REFACTORING_FULL_PROJECT_ANALYSIS.md](REFACTORING_FULL_PROJECT_ANALYSIS.md), разделы 4–5.
- **OrderService** (~954), **OrderCreationHandler** (~1021): вынос подсервисов и шагов создания заказа — см. полный анализ, раздел 5–6.
- **Модульные пакеты**: целевая структура `shop/`, `courier/`, `order/`, `admin/`, `payment/`, `common/`, `core/` и маппинг каждого класса — в [REFACTORING_FULL_PROJECT_ANALYSIS.md](REFACTORING_FULL_PROJECT_ANALYSIS.md), разделы 3–4.

**Документ можно дополнять по ходу рефакторинга (например, таблица «Сейчас в Bot → куда вынести» по мере переноса).**
