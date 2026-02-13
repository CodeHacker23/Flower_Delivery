package org.example.flower_delivery.service;


import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.model.User;
import org.example.flower_delivery.repository.ShopRepository;
import org.example.flower_delivery.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;


/**
 * Сервис для работы с магазинами (Shop).
 *
 * Здесь живёт бизнес-логика:
 * - поиск магазина по пользователю / telegramId
 * - создание нового магазина
 * - активация магазина и т.д.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ShopService {

        private final ShopRepository shopRepository;
        private final UserRepository userRepository;

    /**
     * Найти магазин по пользователю.
     */
    public Optional<Shop> findByUser(User user) {
        log.debug("Поиск магазина по пользователю: userId={}", user.getId());
        return shopRepository.findByUser(user);
    }


    /**
     * Найти магазин по ID пользователя (users.id).
     */
    public Optional<Shop>  findByUserId(UUID userId){
        log.debug("Поиск магазина по userId={}", userId);
        return shopRepository.findByUserId(userId);
    }

    /**
     * Найти магазин по Telegram ID пользователя.
     */
    public Optional<Shop> findByUserTelegramId(Long telegramId){
        log.debug("Поиск магазина по telegramId={}", telegramId);
        return shopRepository.findByUserTelegramId(telegramId);
    }

    /**
     * Создать новый магазин для пользователя.
     *
     * Пока без координат — только базовые поля.
     */
    public Shop createShopForUser(Long telegramId,
                                  String shopName,
                                  String pickupAddress,
                                  String phone) {

        log.info("Создание магазина: telegramId={}, shopName={}", telegramId, shopName);

        // 1. Находим пользователя по telegramId
        User user = userRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new IllegalStateException("Пользователь не найден для telegramId=" + telegramId));

        // 2. Проверяем, что у него ещё нет магазина
        shopRepository.findByUser(user).ifPresent(existing -> {
            throw new IllegalStateException("У пользователя уже есть магазин");
        });

        // 3. Собираем сущность Shop
        Shop shop = Shop.builder()
                .user(user)
                .shopName(shopName)
                .pickupAddress(pickupAddress)
                .phone(phone)
                .isActive(false) // по умолчанию магазин не активен
                .build();

        // 4. Сохраняем в БД
        Shop saved = shopRepository.save(shop);
        log.info("Магазин создан: shopId={}, userId={}", saved.getId(), user.getId());

        return saved;
    }

    /**
     * Сохранить магазин (обновить существующий).
     */
    public Shop save(Shop shop) {
        log.debug("Сохранение магазина: shopId={}", shop.getId());
        return shopRepository.save(shop);
    }
}
