package org.example.flower_delivery.model;


import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Курьер — человек, который таскает букеты и деньги.
 *
 * Это отдельная таблица couriers, связанная 1:1 с users:
 * - users  — базовый аккаунт (телеграм, роль и т.п.)
 * - couriers — курьерские данные (телефон, статус и т.д.)
 */
@Entity
@Table(name = "couriers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Courier {
    /**
     * Уникальный ID курьера (PRIMARY KEY в таблице couriers).
     * UUID, как и в других сущностях.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Пользователь, которому принадлежит этот курьер.
     *
     * Связь 1:1 с таблицей users:
     * - один user → максимум один courier
     * - один courier → ровно один user
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "user_id",   // колонка user_id в таблице couriers
            nullable = false,
            unique = true       // один user = один courier
    )
    private User user;

    /**
     * ФИО курьера.
     * Можно дублировать из User.fullName, но позволяем переписать отдельно.
     */
    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    /**
     * Телефон курьера.
     * Этот номер будет видеть магазин/получатель.
     */
    @Column(name = "phone", nullable = false, length = 50)
    private String phone;

    /**
     * file_id фотографии (селфи с паспортом), которую прислал курьер.
     *
     * Мы НЕ храним саму фотку в БД, только file_id из Telegram.
     * По этому file_id потом можно скачать файл через Telegram Bot API.
     */
    @Column(name = "passport_photo_file_id", length = 255)
    private String passportPhotoFileId;

    /**
     * Статус курьера:
     * - PENDING — ждёт активации
     * - ACTIVE  — может брать заказы
     * - BLOCKED — заблокирован
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private CourierStatus status = CourierStatus.PENDING;

    /**
     * Флаг, что курьер сейчас вообще доступен к работе.
     * (позже можно использовать как "вышел на смену / не вышел").
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = false;

    /**
     * Когда этот курьер был создан.
     */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Когда последний раз обновляли данные курьера.
     */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Здесь потом можно докинуть:
    // - тип транспорта (пеший, авто, вело),
    // - район работы,
    // - рейтинг и т.п.
}


