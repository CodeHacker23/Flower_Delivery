package org.example.flower_delivery.dispatcher;

import org.example.flower_delivery.Bot;
import org.example.flower_delivery.handler.CallbackQueryHandler;
import org.example.flower_delivery.handler.CourierAvailableOrdersHandler;
import org.example.flower_delivery.handler.CourierGeoHandler;
import org.example.flower_delivery.handler.CourierRegistrationHandler;
import org.example.flower_delivery.handler.ShopRegistrationHandler;
import org.example.flower_delivery.service.CourierService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

import java.util.List;

/**
 * Собираем диспетчер и все обработчики апдейтов. Порядок хендлеров = приоритет при dispatch.
 *
 * @Configuration — Spring при старте прочитает этот класс и вызовет все @Bean-методы,
 * положив результаты в контекст. Кто-то (Bot) запросит UpdateDispatcher — Spring передаст сюда список из пяти хендлеров.
 */
@Configuration
public class DispatcherConfig {

    /**
     * Бин для обработки нажатий на инлайн-кнопки. Spring подставит CallbackQueryHandler (он уже есть в контексте).
     * Имя метода = имя бина по умолчанию (callbackQueryUpdateHandler).
     */
    @Bean
    CallbackQueryUpdateHandler callbackQueryUpdateHandler(CallbackQueryHandler callbackQueryHandler) {
        return new CallbackQueryUpdateHandler(callbackQueryHandler);
    }

    /**
     * Бин для обработки «поделиться номером». Нужны оба хендлера — сначала пробуем магазин, потом курьер.
     */
    @Bean
    ContactUpdateHandler contactUpdateHandler(ShopRegistrationHandler shopRegistrationHandler,
                                              CourierRegistrationHandler courierRegistrationHandler) {
        return new ContactUpdateHandler(shopRegistrationHandler, courierRegistrationHandler);
    }

    /**
     * Бин для обработки фото. Сейчас только курьерская регистрация их ждёт.
     */
    @Bean
    PhotoUpdateHandler photoUpdateHandler(CourierRegistrationHandler courierRegistrationHandler) {
        return new PhotoUpdateHandler(courierRegistrationHandler);
    }

    /**
     * Бин для обработки геолокации. Нужны: список доступных заказов (гео для сортировки), гео для статусов, сервис курьеров (обновить точку).
     */
    @Bean
    LocationUpdateHandler locationUpdateHandler(CourierAvailableOrdersHandler courierAvailableOrdersHandler,
                                                CourierGeoHandler courierGeoHandler,
                                                CourierService courierService) {
        return new LocationUpdateHandler(courierAvailableOrdersHandler, courierGeoHandler, courierService);
    }

    /**
     * Бин для обработки текста. @Lazy Bot — чтобы не было цикла зависимостей (Bot нужен Dispatcher, TextUpdateHandler нужен Bot).
     */
    @Bean
    TextUpdateHandler textUpdateHandler(@Lazy Bot bot) {
        return new TextUpdateHandler(bot);
    }

    /**
     * Сам диспетчер. Принимает все пять хендлеров и собирает их в список в нужном порядке:
     * callback → contact → photo → location → text. Этим порядком диспетчер будет идти при dispatch(update).
     */
    @Bean
    UpdateDispatcher updateDispatcher(CallbackQueryUpdateHandler callbackQueryUpdateHandler,
                                      ContactUpdateHandler contactUpdateHandler,
                                      PhotoUpdateHandler photoUpdateHandler,
                                      LocationUpdateHandler locationUpdateHandler,
                                      TextUpdateHandler textUpdateHandler) {
        return new UpdateDispatcher(List.of(
                callbackQueryUpdateHandler,
                contactUpdateHandler,
                photoUpdateHandler,
                locationUpdateHandler,
                textUpdateHandler
        ));
    }
}
