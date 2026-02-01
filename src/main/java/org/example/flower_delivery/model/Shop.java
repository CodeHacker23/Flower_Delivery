package org.example.flower_delivery.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Модель магазина (Shop) — Java-представление таблицы shops.
 * <p>
 * Связана с таблицей users (1:1):
 * - Один пользователь (user) может быть максимум ОДНИМ магазином.
 * - Магазин всегда принадлежит какому-то пользователю.
 * <p>
 * Таблица: shops
 * См. миграцию: V2__create_shops_table.sql
 */

@Entity
@Table(name = "shops")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shop {
    /**
     * ID магазина (UUID).
     * <p>
     * PRIMARY KEY в таблице shops.
     * Генерируется в БД (DEFAULT gen_random_uuid()).
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;


    /**
     * Пользователь, которому принадлежит магазин.
     * <p>
     * Связь 1:1 с сущностью User.
     * В БД: колонка user_id (FOREIGN KEY → users.id, UNIQUE).
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "user_id",           // имя колонки в таблице shops
            nullable = false,           // NOT NULL
            unique = true               // один user = один shop (1:1)
    )
    private User user;

    /**
     * Название магазина.
     * <p>
     * Примеры:
     * - "Цветы на Ленина"
     * - "Роза и Лилии"
     */
    @Column(name = "shop_name", nullable = false, length = 255)
    private String shopName;

    /**
            * Адрес забора товаров (где курьер забирает заказы).
            *
            * Примеры:
            * - "ул. Ленина, д. 10, офис 5"
            * - "пр. Мира, д. 25, вход со двора"
            */
    @Column(name = "pickup_address", nullable = false, length = 500)
    private String pickupAddress;

    /**
     * Широта (latitude) адреса забора.
     *
     * DECIMAL(10, 8) в БД → BigDecimal в Java.
     * Может быть NULL, если мы ещё не геокодировали адрес.
     */
    @Column(name = "latitude", precision = 10, scale = 8)
    private BigDecimal latitude;

    /**
     * Долгота (longitude) адреса забора.
     *
     * DECIMAL(11, 8) в БД → BigDecimal в Java.
     * Может быть NULL.
     */
    @Column(name = "longitude", precision = 11, scale = 8)
    private BigDecimal longitude;

    /**
     * Телефон магазина (может отличаться от телефона пользователя).
     *
     * Может быть NULL.
     */
    @Column(name = "phone", length = 50)
    private String phone;

    /**
     * Флаг активности магазина.
     *
     * false — магазин зарегистрирован, но админ ещё не активировал (не может создавать заказы).
     * true  — магазин активен (может работать).
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = false;

    /**
     * Дата создания записи о магазине.
     *
     * Заполняется автоматически при создании (CURRENT_TIMESTAMP).
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Дата последнего обновления записи.
     *
     * Обновляется автоматически при изменении.
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}



