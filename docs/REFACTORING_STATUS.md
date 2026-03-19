# Статус рефакторинга (актуально)

Кратко: что уже сделано, что осталось. Детальный план — в [REFACTORING_PLAN.md](REFACTORING_PLAN.md).

---

## Реализовано

| Фаза / шаг | Что сделано | Где лежит |
|------------|-------------|-----------|
| **Фаза 1** | Утилиты вынесены из Bot | `util/TextFormattingUtil`, `util/RouteUrlBuilder` |
| **Фаза 2** | TelegramSender + BotTelegramSender; все отправки из Bot через `telegramSender` | `telegram/TelegramSender`, `telegram/BotTelegramSender`; в Bot нет `execute()` |
| **Фаза 3** | Диспетчер апдейтов: callback → contact → photo → location → text | `dispatcher/UpdateDispatcher`, `UpdateHandler`, `*UpdateHandler`, `DispatcherConfig` |
| **Фаза 4** | Меню магазина: «Мой магазин», «Мои заказы» | `handler/ShopMenuHandler` |
| **Фаза 5 (частично)** | «Моя статистика» курьера | `handler/CourierStatsHandler` |
| **Фаза 5 (частично)** | «Доступные заказы» и «Мои заказы» курьера, построение контента, редактирование | `handler/CourierMenuHandler`; `CourierAvailableOrdersHandler` переведён на `CourierMenuHandler` + `TelegramSender` |
| **Доп.** | Подробные комментарии в пакете `dispatcher` | Все классы в `dispatcher/` |
| **Доп.** | Убраны полные квалификаторы `java.math`/`java.time`/`java.util` в теле кода | По проекту |
| **Фаза 6** | Отмена/возврат заказа курьером: выбор номера, ввод причины, уведомления магазину и админам, маршрут обратно в магазин | `handler/CourierCancelFlowHandler` |
| **Фаза 7** | Уведомления админам о проблеме с геолокацией курьера | `service/AdminNotificationService`; вызов из `CourierGeoHandler` (пока в закомментированном блоке) |
| **Фаза 8** | Меню-клавиатуры (Reply): магазин и курьер | `service/MenuKeyboardService`; вызовы из Bot, StartCommandHandler, CallbackQueryHandler, CourierGeoHandler, CourierCancelFlowHandler |
| **Фаза 8** | Тестовые команды /r, /k (активация магазина и курьера) | `handler/AdminTestCommandHandler` |
| **Фаза 8** | Уведомления магазину: запрос «Курьер забрал заказ?» | `service/ShopNotificationService`; вызов из CallbackQueryHandler и CourierGeoHandler |
| **Фаза 8** | CallbackQueryHandler разбит на под-хендлеры | `handler/callback/RoleCallbackHandler`, `ShopOrderCallbackHandler`, `CourierCallbackHandler`; роутер ~130 строк |

**Bot сейчас:** ~275 строк (было ~1680). В Bot остались: роутинг текста (processTextUpdate), sendSimpleMessage, getBotUsername/getBotToken, конфиг.

---

## Осталось сделать

| Задача | Описание | План (из REFACTORING_PLAN) |
|--------|----------|----------------------------|
| ~~**CourierCancelFlowHandler**~~ | ~~Отмена/возврат заказа курьером~~ | ✅ **Сделано** |
| ~~**AdminNotificationService**~~ | ~~notifyAdminsAboutCourierGeoIssue~~ | ✅ **Сделано** |
| ~~**Уведомления магазину**~~ | ~~sendShopPickupConfirmationRequest~~ | ✅ **Сделано** (ShopNotificationService); sendReturnToShopRoute уже в CourierCancelFlowHandler |
| ~~**Меню-клавиатуры**~~ | ~~sendShopMenu, sendCourierMenu, sendCourierMenuPlain~~ | ✅ **Сделано** (MenuKeyboardService) |
| ~~**Тестовые команды /r, /k**~~ | ~~handleActivateCommand, handleActivateCourierCommand~~ | ✅ **Сделано** (AdminTestCommandHandler) |
| **Кнопка «ℹ️ Информация»** | Сейчас одна строка в Bot; можно оставить или вынести в общий «инфо»-хендлер | По желанию |
| **Финальная зачистка Bot** | Удалить всё перенесённое, оставить только точка входа + конфиг; Bot < 200 строк | Фаза 8 |
| ~~**CallbackQueryHandler**~~ | ~~Разбить по типам callback~~ | ✅ **Сделано**: RoleCallbackHandler, ShopOrderCallbackHandler, CourierCallbackHandler в handler/callback/ |

---

## Где что искать в коде

- **Роутинг апдейтов:** `Bot.processUpdate()` → `UpdateDispatcher.dispatch()` → список `UpdateHandler` в `DispatcherConfig`.
- **Текст (команды, кнопки меню):** `Bot.processTextUpdate()` — порядок веток важен (сначала команды и «жду ввода», потом кнопки меню).
- **Меню магазина:** `ShopMenuHandler.handleShopInfo()`, `handleMyOrders()`.
- **Меню курьера (списки):** `CourierMenuHandler` (Доступные заказы, Мои заказы); статистика — `CourierStatsHandler`.
- **Отправка сообщений:** везде через `TelegramSender` (в Bot — поле `telegramSender`; в хендлерах — внедрённый бин).
- **Callback по типам:** `CallbackQueryHandler.handle()` → `RoleCallbackHandler` (role_*, create_order, shop_info), `ShopOrderCallbackHandler` (заказы магазина, shop_pickup_confirm), `CourierCallbackHandler` (courier_*).

---

## Критерии готовности (из плана)

- [ ] Bot.java < 200 строк (сейчас ~275)
- [x] processUpdate без цепочек if (диспетчер + обработчики)
- [x] Нет статичных хелперов в Bot (вынесены в util)
- [x] Новый тип апдейта = новый UpdateHandler, без правки гигантского метода
- [x] Отмена/возврат вынесена в CourierCancelFlowHandler
- [x] notifyAdminsAboutCourierGeoIssue вынесено в AdminNotificationService

Документ обновлять по мере рефакторинга.
