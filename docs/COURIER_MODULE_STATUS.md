# Статус курьерского модуля

> **Для AI/разработчиков:** этот файл — актуальная сводка по курьерскому модулю. Опирайся на него при доработках.

**Дата обновления:** 2025-03-01

---

## 1. Реализовано

| Фича | Файлы | Примечание |
|------|-------|------------|
| **Регистрация курьера** | CourierRegistrationHandler, CourierService | ФИО → телефон (контакт) → селфи с паспортом. Статус PENDING. |
| **Активация** | Bot.java (`/k`) | Временно через команду /k. TODO: заменить на админку. |
| **Доступные заказы** | Bot, CourierAvailableOrdersHandler, OrderService | Сортировка по расстоянию, честное распределение (10 ближайших + 10 от других магазинов). Лимит 80 из БД. |
| **Пагинация списка** | CourierAvailableOrdersHandler, Bot, CallbackQueryHandler | 10 заказов на страницу. Кнопки «← Назад» / «Дальше →». EditMessageText. |
| **Выбор заказа** | CourierAvailableOrdersHandler | Ввод номера (1–10 на текущей странице). Проверка баланса. |
| **Связки заказов** | OrderBundleService, CallbackQueryHandler | «Взять связку», «Альтернативная связка». OSRM Trip API (свой инстанс по ЧЛБ) или Haversine. Порядок в сообщении = порядок по маршруту. |
| **Маршрут связки** | OrderBundleService | 2ГИС: первая точка = гео курьера → забор1 → доставка1 → … Яндекс: от текущего местоположения. Кнопки после «Связка взята!». |
| **Мои заказы** | Bot, CallbackQueryHandler | Последние 6 заказов. Кнопки смены статуса. |
| **Смена статусов** | CallbackQueryHandler, CourierGeoHandler | ACCEPTED → IN_SHOP → ON_WAY → DELIVERED. Мультиадрес по точкам. |
| **Геолокация** | CourierGeoHandler, CourierGeoService | Подтверждение «В магазине» и «Вручил» через гео. Снимки в order_status_geo_snapshots. |
| **Маршруты** | Bot, CallbackQueryHandler | Яндекс.Карты и 2ГИС (одиночный заказ и связка). |
| **Депозит** | CourierService, CourierTransactionService | Баланс в couriers. Проверка при взятии заказа. |
| **Пополнение** | CourierDepositHandler, YooKassaPaymentService | ЮKassa, минимум 300 ₽, webhook. |
| **Ручное пополнение** | scripts/deposit_topup.sql | Простой SQL без DO-блока (работает везде). |
| **Отмена заказа** | Bot, CallbackQueryHandler, OrderService | Ввод номера → причина → cancelOrderByCourier. |
| **Возврат в магазин** | CallbackQueryHandler, OrderService | Аналогичный флоу. Маршрут обратно в магазин. |
| **Штрафы** | CourierPenaltyService | 1000 ₽ при ≥3 отменах за 30 дней. app.penalty.skip-geo-check для тестов. |
| **Комиссия** | OrderService, CourierService | 20% (настраиваемо). Списание при взятии, возврат при отмене. |
| **Статистика** | Bot | Всего/сегодня/7 дней/месяц. Баланс, комиссия. |
| **Пагинация транзакций** | Bot, CallbackQueryHandler | 6 операций на страницу. «⬆️ Новее» / «⬇️ Раньше». |
| **Подтверждение магазином** | CallbackQueryHandler, OrderService | «Курьер забрал заказ?» ДА/Нет при переходе в «В путь». |
| **Сброс курьера** | scripts/reset_courier.sql | Удаление courier_transactions перед couriers (FK). |

---

## 2. Ключевые решения

### Пагинация «Доступные заказы»
- **ORDERS_PER_PAGE = 10** в CourierAvailableOrdersHandler
- Храним: lastAvailableOrderIdsByUser, lastAvailableOrdersPageByUser, lastAvailableCourierLocation
- Callback `courier_orders_page:N` → showAvailableOrdersPage() → EditMessageText
- Номера заказов при выборе — локальные на странице (1–10)

### deposit_topup.sql
- Используем **простой SQL** (UPDATE + INSERT с подзапросом), без DO-блока
- Причина: DO с DECLARE/UUID вызывал синтаксические ошибки в некоторых клиентах

### reset_courier.sql
- Порядок: order_status_geo_snapshots → order_stops → orders → courier_transactions → couriers → users
- orders.courier_id ссылается на users.id (не couriers.id)

---

## 3. TODO перед продакшеном

| # | Задача | Где |
|---|--------|-----|
| 1 | ~~Убрать автопополнение 1000 ₽ при активации~~ | ✅ Сделано: баланс через deposit_topup.sql |
| 2 | Включить проверку гео 200 м и 3 попытки | CourierGeoHandler ~172 |
| 3 | app.penalty.skip-geo-check: false | application.properties |
| 4 | Заменить /k на админский интерфейс активации | — |
| 5 | **Откат тестовых override адресов забора** | Выполнить `scripts/revert_shop_pickup_override.sql` (обнулить shop_pickup_* у заказов). V9 колонки оставить, в проде они NULL. |
| 6 | Не запускать на прод-БД тестовые скрипты подстановки адресов | run_replace_shop_pickup.sql, replace_shop_pickup_*.sql — только для теста связок. |

---

## 4. Доработки (не критично)

- Пагинация «Мои заказы» (сейчас только 6 последних)
- getLastTransactions — параметр limit не используется (всегда 20)
- Смена адреса доставки во время выполнения (по ТЗ)

---

## 5. Геокодирование и тестовые данные

- **GeocodingService:** DaData Clean (при наличии secret-key) + Suggest с ограничением по городу (`app.region.city`). Результаты Clean из другого города (напр. Копейск) отбрасываются.
- **Миграция V9:** колонки `shop_pickup_address_override`, `shop_pickup_latitude`, `shop_pickup_longitude` в `orders` — **только для теста** (симуляция разных точек забора у одного магазина). Перед продом: выполнить `scripts/revert_shop_pickup_override.sql`.
- Подробный чеклист перед продакшеном и описание коммита: `docs/COMMIT_MSG_GEO_OSRM_BUNDLES.md`.

---

## 6. Связанные файлы

```
Bot.java                          — меню, доступные заказы, пагинация, статистика
CourierAvailableOrdersHandler.java — список, выбор, пагинация
CallbackQueryHandler.java         — callback-кнопки (страницы, статусы, связки, отмена)
CourierRegistrationHandler.java   — регистрация
CourierDepositHandler.java        — пополнение через ЮKassa
CourierGeoHandler.java            — гео, подтверждение «В магазине»/«Вручил»
CourierService.java              — баланс, активация, геолокация
OrderBundleService.java           — связки, OSRM Trip/Haversine, маршруты 2ГИС/Яндекс
GeocodingService.java             — DaData Clean + Suggest, ограничение по городу
OrderService.java                — назначение, отмена, комиссия
CourierPenaltyService.java       — штрафы
scripts/deposit_topup.sql         — ручное пополнение
scripts/revert_shop_pickup_override.sql — ОБЯЗАТЕЛЬНО перед продом: обнулить тестовые override
scripts/reset_courier.sql        — сброс профиля курьера
```
