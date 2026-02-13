package org.example.flower_delivery.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Courier;
import org.example.flower_delivery.model.CourierStatus;
import org.example.flower_delivery.model.User;
import org.example.flower_delivery.repository.CourierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
     * Сервис для работы с курьерами.
     *
     * Здесь бизнес-логика:
     * - регистрация курьера,
     * - поиск по Telegram ID,
     * - активация/блокировка.
     */
    @Service
    @RequiredArgsConstructor
    @Slf4j
    @Transactional
    public class CourierService {

    private final CourierRepository courierRepository;
    private final UserService userService;

    /**
     * Найти курьера по Telegram ID пользователя.
     */
    public Optional<Courier> findByTelegramId(Long telegramId) {
        Optional<User> userOpt = userService.findByTelegramId(telegramId);
        if (userOpt.isEmpty()) {
            return Optional.empty();
        }
        return courierRepository.findByUser(userOpt.get());
    }

    /**
     * Зарегистрировать курьера для уже существующего User.
     * (вызывается из сценария регистрации роли "Курьер").
     */
    public Courier registerCourier(Long telegramId, String fullName, String phone, String passportPhotoFileId) {
        log.info("Регистрация курьера: telegramId={}, fullName={}, phone={}, passportFileId={}",
                telegramId, fullName, phone, passportPhotoFileId);

        User user = userService.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));

        // Проверяем, не существует ли уже курьер для этого пользователя
        courierRepository.findByUser(user).ifPresent(c -> {
            throw new IllegalStateException("Курьер для этого пользователя уже существует");
        });

        Courier courier = Courier.builder()
                .user(user)
                .fullName(fullName)
                .phone(phone)
                .passportPhotoFileId(passportPhotoFileId)
                .status(CourierStatus.PENDING) // ждёт активации админом
                .isActive(false)
                .build();

        return courierRepository.save(courier);
    }

    /**
     * Активировать курьера (админская операция).
     */
    public Courier activateCourier(Courier courier) {
        courier.setStatus(CourierStatus.ACTIVE);
        courier.setIsActive(true);
        return courierRepository.save(courier);
    }

    /**
     * Заблокировать курьера.
     */
    public Courier blockCourier(Courier courier) {
        courier.setStatus(CourierStatus.BLOCKED);
        courier.setIsActive(false);
        return courierRepository.save(courier);
    }
}
