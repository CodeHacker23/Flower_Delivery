package org.example.flower_delivery.repository;

import org.example.flower_delivery.model.Role;
import org.example.flower_delivery.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Репозиторий для работы с таблицей users в БД
 * 
 *  Что это такое:
 * Репозиторий — это интерфейс, который позволяет работать с БД без написания SQL.
 * Spring Data JPA автоматически создаёт реализацию этого интерфейса и генерирует SQL запросы
 * из названий методов.
 * 
 *  Как это работает:</h2>
 * <ul>
 *   <li><b>Ты пишешь:</b> {@code findByTelegramId(123L)}</li>
 *   <li><b>Spring переводит в SQL:</b> {@code SELECT * FROM users WHERE telegram_id = 123}</li>
 *   <li><b>БД выполняет SQL и возвращает результат</b></li>
 *   <li><b>Spring превращает результат в Java объект</b></li>
 * </ul>
 * 
 * <h2>Примеры использования:</h2>
 * 
 * <h3>1. Найти пользователя по Telegram ID:</h3>
 * <pre>{@code
 * Optional<User> user = userRepository.findByTelegramId(123456789L);
 * if (user.isPresent()) {
 *     User u = user.get();
 *     System.out.println(u.getFullName());
 * }
 * }</pre>
 * 
 * <h3>2. Проверить существует ли пользователь:</h3>
 * <pre>{@code
 * boolean exists = userRepository.existsByTelegramId(123456789L);
 * if (exists) {
 *     System.out.println("Пользователь уже зарегистрирован!");
 * }
 * }</pre>
 * 
 * <h3>3. Найти всех курьеров:</h3>
 * <pre>{@code
 * List<User> couriers = userRepository.findByRole(Role.COURIER);
 * System.out.println("Курьеров: " + couriers.size());
 * }</pre>
 * 
 * <h3>4. Найти всех активных курьеров:</h3>
 * <pre>{@code
 * List<User> activeCouriers = userRepository.findByRoleAndIsActiveTrue(Role.COURIER);
 * }</pre>
 * 
 * <h2>Готовые методы из JpaRepository:</h2>
 * <ul>
 *   <li>{@code save(User user)} - сохранить/обновить пользователя</li>
 *   <li>{@code findById(UUID id)} - найти по ID</li>
 *   <li>{@code findAll()} - найти всех</li>
 *   <li>{@code delete(User user)} - удалить пользователя</li>
 *   <li>{@code existsById(UUID id)} - проверить существует ли</li>
 *   <li>{@code count()} - посчитать количество записей</li>
 * </ul>
 * 
 * <h2>Кастомные методы (Spring сам переведёт в SQL):</h2>
 * <ul>
 *   <li>{@code findByTelegramId(Long telegramId)} - найти по Telegram ID</li>
 *   <li>{@code existsByTelegramId(Long telegramId)} - проверить существует ли по Telegram ID</li>
 *   <li>{@code findByRole(Role role)} - найти всех с определённой ролью</li>
 *   <li>{@code findByRoleAndIsActiveTrue(Role role)} - найти всех активных с определённой ролью</li>
 * </ul>
 * 
 * @author Иларион
 * @version 1.0
 * @see org.example.flower_delivery.model.User
 * @see org.example.flower_delivery.service.UserService
 */
@Repository  // Говорит Spring: "Это репозиторий, создай для него реализацию!"
public interface UserRepository extends JpaRepository<User, UUID> {
    /**
     * Найти пользователя по Telegram ID
     *
     * Spring Data JPA автоматически переведёт это в SQL:
     * {@code SELECT * FROM users WHERE telegram_id = ?}
     *
     * @param telegramId - Telegram ID пользователя
     * @return Optional<User> - пользователь, если найден, иначе пусто
     *         Optional - это как коробка: может быть User внутри, может быть пусто
     *         Используется чтобы избежать NullPointerException
     */
    Optional<User> findByTelegramId(Long telegramId);


    /**
     * Проверить существует ли пользователь с таким Telegram ID
     *
     * Spring Data JPA автоматически переведёт это в SQL:
     * {@code SELECT COUNT(*) > 0 FROM users WHERE telegram_id = ?}
     *
     * @param telegramId - Telegram ID пользователя
     * @return true если существует, false если нет
     */
    boolean existsByTelegramId(Long telegramId);

    /**
     * Найти всех пользователей по роли
     *
     * Spring Data JPA автоматически переведёт это в SQL:
     * {@code SELECT * FROM users WHERE role = ?}
     *
     * @param role - роль (COURIER, SHOP, ADMIN)
     * @return список пользователей с этой ролью
     */
    List<User> findByRole(Role role);

    /**
     * Найти всех активных пользователей по роли
     *
     * Spring Data JPA автоматически переведёт это в SQL:
     * {@code SELECT * FROM users WHERE role = ? AND is_active = true}
     *
     * @param role - роль
     * @return список активных пользователей с этой ролью
     */
    List<User> findByRoleAndIsActiveTrue(Role role);
}

 
