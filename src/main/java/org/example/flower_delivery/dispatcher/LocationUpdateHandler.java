package org.example.flower_delivery.dispatcher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.handler.CourierAvailableOrdersHandler;
import org.example.flower_delivery.handler.CourierGeoHandler;
import org.example.flower_delivery.service.CourierService;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Обработчик геолокации: курьер (или кто угодно) отправил точку на карте.
 *
 * Три сценария: 1) ждал гео для списка «Доступные заказы»; 2) подтверждает «В магазине»/«Вручил»;
 * 3) просто обновляем последнюю точку курьера в базе. Всё тут, Bot не раздуваем.
 */
@Slf4j
@RequiredArgsConstructor
public class LocationUpdateHandler implements UpdateHandler {

    private final CourierAvailableOrdersHandler courierAvailableOrdersHandler;
    private final CourierGeoHandler courierGeoHandler;
    private final CourierService courierService;

    @Override
    /** «Моё» только если в сообщении есть локация (hasLocation) — юзер отправил гео через кнопку или вложением. */
    public boolean canHandle(Update update) {
        return update.hasMessage() && update.getMessage().hasLocation();
    }

    @Override
    public void handle(Update update) {
        // Кто отправил и в какой чат — нужны для ответов и проверок.
        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();
        // Сама точка: широта и долгота из сообщения с гео.
        var location = update.getMessage().getLocation();
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        log.debug("Получена геолокация: telegramId={}, chatId={}", telegramId, chatId);

        // Сценарий 1: курьер нажал «Доступные заказы» и бот ждёт гео, чтобы показать список рядом с ним.
        if (courierAvailableOrdersHandler.isAwaitingLocationForList(telegramId)) {
            courierAvailableOrdersHandler.handleLocationForAvailableList(telegramId, chatId, lat, lon);
            return;
        }
        // Сценарий 2: курьер подтверждает «В магазине» или «Вручил» — проверяем расстояние, меняем статус или говорим «подойди ближе».
        if (courierGeoHandler.handleLocation(telegramId, chatId, lat, lon)) {
            return;
        }
        // Сценарий 3: просто обновляем «последняя известная точка курьера» в базе — для маршрутов и т.д.
        courierService.updateLastLocation(telegramId, lat, lon);
    }
}
