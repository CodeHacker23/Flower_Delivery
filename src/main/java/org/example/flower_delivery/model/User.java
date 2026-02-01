package org.example.flower_delivery.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Модель пользователя (User) - это Java представление таблицы users в PostgreSQL
 * 
 * <h2>Что это такое:</h2>
 * Этот класс связывает Java код с таблицей users в БД через JPA/Hibernate.
 * Вместо того чтобы писать SQL вручную, ты работаешь с Java объектами,
 * а Hibernate автоматически переводит это в SQL запросы.
 *
 **/

@Entity
@Table(name = "users") // название таблицы в БД (должно совпадать с миграцией! регистр в регистр!)
@Getter  // Lombok: автоматически создаст getter методы (getId(), getTelegramId(), ...)
@Setter  // Lombok: автоматически создаст setter методы (setId(), setTelegramId(), ...)
@NoArgsConstructor  // Lombok: конструктор без параметров (нужен для Hibernate)
@AllArgsConstructor  // Lombok: конструктор со всеми параметрами
@Builder  // Lombok: паттерн Builder для удобного создания объектов
public class User {
    /**
     * ID пользователя (UUID - уникальный идентификатор)
     *
     * @Id - это поле является первичным ключом (PRIMARY KEY)
     * @GeneratedValue(strategy = GenerationType.UUID) - PostgreSQL сам генерирует UUID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * Telegram ID пользователя (уникальный ID в Telegram)
     *
     * @Column(unique = true, nullable = false) - в БД не может быть двух пользователей с одинаковым telegram_id
     */
    @Column(name = "telegram_id", unique = true, nullable = false)
    private Long telegramId;

    /**
     * ФИО пользователя
     */
    @Column(name = "full_name", nullable = false)
    private String fullName;

    /**
     * Телефон пользователя (может быть NULL)
     */
    @Column(name = "phone")
    private String phone;

    /**
     * Роль пользователя (COURIER, SHOP, ADMIN)
     *
     * @Enumerated(EnumType.STRING) - сохраняем в БД как строку ("COURIER"), а не как число (0)
     * Почему EnumType.STRING:
     *
     * Читаемо в БД (видишь 'COURIER', а не 0)
     * Хорошо к изменениям (если добавишь новую роль в середину, старые данные не сломаются)
     * Легко дебажить (смотришь в БД и сразу видишь роль)
     * Почему НЕ EnumType.ORDINAL:
     *
     * Нечитаемо в БД (видишь 0, 1, 2— непонятно что это)
     * Хрупко (если добавишь MANAGERмежду COURIERи SHOP, все старые данные поедут к чертям)
     * Без @Enumerated:
     * Hibernate будет использоваться EnumType.ORDINALпо умолчанию (плохо!).
     *
     * С @Enumerated(EnumType.STRING):
     * Hibernate сохраняет enum как символ, как мы и хотим.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private Role role;

    /**
     * Флаг активности (по умолчанию false - неактивен, админ активирует)
     *
     * @Builder.Default - говорит Lombok: используй это значение по умолчанию при создании через Builder
     */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = false;

    /**
     * Дата создания (автоматически заполняется при создании записи)
     *
     * @CreationTimestamp - Hibernate автоматически установит текущую дату при создании
     * updatable = false - нельзя изменить после создания
     */
    @CreationTimestamp // «Автоматически ставить дату создания»
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Дата обновления (автоматически обновляется при изменении записи)
     *
     * @UpdateTimestamp - Hibernate автоматически обновит при каждом изменении
     */
    @UpdateTimestamp // «Автоматически обновлять изменения»
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}







