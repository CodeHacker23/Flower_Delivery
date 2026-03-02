package org.example.flower_delivery.dispatcher;

import lombok.RequiredArgsConstructor;
import org.example.flower_delivery.handler.CourierRegistrationHandler;
import org.example.flower_delivery.handler.ShopRegistrationHandler;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Обработчик «поделился номером телефона» (кнопка в Telegram).
 *
 * И магазин, и курьер при регистрации жмут «Отправить номер» — прилетает контакт.
 * Сначала пробуем отдать в регистрацию магазина; если не приняли — в регистрацию курьера.
 */
@RequiredArgsConstructor
public class ContactUpdateHandler implements UpdateHandler {

    private final ShopRegistrationHandler shopRegistrationHandler;
    private final CourierRegistrationHandler courierRegistrationHandler;

    @Override
    /** «Моё» только если в сообщении есть контакт (hasContact) — т.е. юзер нажал «Поделиться номером». */
    public boolean canHandle(Update update) {
        return update.hasMessage() && update.getMessage().hasContact();
    }

    @Override
    /**
     * Сначала суём контакт в регистрацию магазина. Если магазин сказал «принял» (true) — выходим.
     * Иначе отдаём в регистрацию курьера — он уже сам решит, его ли это (например, по шагу регистрации).
     */
    public void handle(Update update) {
        if (shopRegistrationHandler.handleContact(update)) {
            return;
        }
        courierRegistrationHandler.handleContact(update);
    }
}
