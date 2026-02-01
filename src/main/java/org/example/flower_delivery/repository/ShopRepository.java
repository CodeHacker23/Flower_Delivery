package org.example.flower_delivery.repository;

import org.example.flower_delivery.model.Shop;
import org.example.flower_delivery.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий для работы с магазинами (Shop).
 *
 * Это как «прослойка» между кодом и таблицей shops:
 * вместо SQL мы пишем методы, а Spring Data JPA сам генерирует запросы.
 */
@Repository
public interface ShopRepository extends JpaRepository<Shop, UUID> {


    /**
     * Найти магазин по пользователю.
     *
     * Один пользователь → один магазин (1:1),
     * поэтому возвращаем Optional<Shop>.
     */
    Optional<Shop> findByUser(User user);

    /**
     * Найти магазин по ID пользователя (users.id).
     */
    Optional<Shop> findByUserId(UUID userId);

    /**
     * Найти магазин по Telegram ID пользователя.
     *
     * Магия Spring Data:
     * user.telegramId → user_TelegramId → findByUserTelegramId(...)
     */
    Optional<Shop> findByUserTelegramId(Long telegramId);

    /**
     * Найти все активные магазины.
     */
    List<Shop> findByIsActiveTrue();
}
