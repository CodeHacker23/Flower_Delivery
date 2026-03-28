package org.example.flower_delivery.dispatcher;

import lombok.RequiredArgsConstructor;
import org.example.flower_delivery.handler.CourierRegistrationHandler;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Обработчик фото (селфи при регистрации курьера).
 *
 * Сейчас только курьерская регистрация ждёт фото. Прилетело фото — отдаём в CourierRegistrationHandler.
 */
@RequiredArgsConstructor
public class PhotoUpdateHandler implements UpdateHandler {

    private final CourierRegistrationHandler courierRegistrationHandler;

    @Override
    /** «Моё» только если в сообщении есть фото (hasPhoto). Текстовые сообщения сюда не попадают. */
    public boolean canHandle(Update update) {
        return update.hasMessage() && update.getMessage().hasPhoto();
    }

    @Override
    /** Вся логика «что делать с фото» — в CourierRegistrationHandler (сохранить селфи, перейти к следующему шагу и т.д.). */
    public void handle(Update update) {
        courierRegistrationHandler.handlePhoto(update);
    }
}
