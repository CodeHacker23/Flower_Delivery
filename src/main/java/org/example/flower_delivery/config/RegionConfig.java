package org.example.flower_delivery.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Конфигурация региона работы бота.
 * <p>
 * Позволяет развернуть один и тот же код в разных городах:
 * - Челябинск
 * - Екатеринбург
 * - Москва
 * и т.д.
 * <p>
 * Просто меняешь значения в application.properties — и бот работает в другом городе!
 */
@Slf4j
@Getter
@Configuration
public class RegionConfig {

    /**
     * Город работы бота (добавляется к адресу при геокодировании).
     * Например: "Челябинск"
     */
    @Value("${app.region.city}")
    private String city;

    /**
     * Область (для валидации что адрес в нужном регионе).
     * Например: "Челябинская область"
     */
    @Value("${app.region.area}")
    private String area;

    @PostConstruct
    public void init() {
        log.info("===========================================");
        log.info("РЕГИОН РАБОТЫ БОТА: {} ({})", city, area);
        log.info("===========================================");
    }

    /**
     * Дополнить адрес городом.
     * <p>
     * Юзер вводит: "ул. Ленина 44"
     * Получаем: "Челябинск, ул. Ленина 44"
     */
    public String enrichAddress(String address) {
        // Если адрес уже содержит город — не добавляем
        if (address.toLowerCase().contains(city.toLowerCase())) {
            return address;
        }
        return city + ", " + address;
    }
}
