package org.example.flower_delivery.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Courier;
import org.example.flower_delivery.model.CourierStatus;
import org.example.flower_delivery.model.User;
import org.example.flower_delivery.repository.CourierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
     * Найти курьера по сущности пользователя.
     */
    public Optional<Courier> findByUser(User user) {
        return courierRepository.findByUser(user);
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

    /**
     * Обновить последнюю известную геолокацию курьера.
     */
    public void updateLastLocation(Long telegramId, double latitude, double longitude) {
        findByTelegramId(telegramId).ifPresent(c -> {
            c.setLastLatitude(BigDecimal.valueOf(latitude));
            c.setLastLongitude(BigDecimal.valueOf(longitude));
            c.setLastLocationAt(LocalDateTime.now());
            courierRepository.save(c);
            log.debug("Геолокация курьера обновлена: telegramId={}", telegramId);
        });
    }

    /**
     * Списать сумму с баланса курьера (например, комиссия или штраф).
     *
     * @return true если баланс был достаточен и списание выполнено, false если курьера нет или денег не хватает
     */
    public boolean chargeFromBalance(User courierUser, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return true;
        }
        Optional<Courier> opt = courierRepository.findByUser(courierUser);
        if (opt.isEmpty()) {
            log.warn("Не найден курьер для списания с баланса: userId={}", courierUser.getId());
            return false;
        }
        Courier courier = opt.get();
        BigDecimal current = courier.getBalance() != null ? courier.getBalance() : BigDecimal.ZERO;
        if (current.compareTo(amount) < 0) {
            log.info("Недостаточно средств на балансе курьера: courierId={}, balance={}, required={}",
                    courier.getId(), current, amount);
            return false;
        }
        courier.setBalance(current.subtract(amount));
        courierRepository.save(courier);
        log.info("Списано {} с баланса курьера {} (новый баланс = {})",
                amount, courier.getId(), courier.getBalance());
        return true;
    }

    /**
     * Пополнить баланс курьера (возврат комиссии, пополнение, отмена штрафа и т.п.).
     */
    public void addToBalance(User courierUser, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Optional<Courier> opt = courierRepository.findByUser(courierUser);
        if (opt.isEmpty()) {
            log.warn("Не найден курьер для пополнения баланса: userId={}", courierUser.getId());
            return;
        }
        Courier courier = opt.get();
        BigDecimal current = courier.getBalance() != null ? courier.getBalance() : BigDecimal.ZERO;
        courier.setBalance(current.add(amount));
        courierRepository.save(courier);
        log.info("Баланс курьера {} пополнен на {} (новый баланс = {})",
                courier.getId(), amount, courier.getBalance());
    }
}
