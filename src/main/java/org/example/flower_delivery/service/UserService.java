package org.example.flower_delivery.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.flower_delivery.model.Role;
import org.example.flower_delivery.model.User;
import org.example.flower_delivery.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service  // Говорит Spring: "Это сервис, создай для него бин!"
@RequiredArgsConstructor  // Lombok: создаст конструктор со всеми final полями
@Slf4j  // Lombok: добавит поле log для логирования
@Transactional  // Все методы выполняются в транзакции БД

public class UserService {

    // Spring автоматически найдет UserRepository и подставит сюда (Dependency Injection)
    private final UserRepository userRepository;

    /**
     * Найти пользователя по Telegram ID
     *
     * @param telegramId - Telegram ID пользователя
     * @return Optional<User> - пользователь, если найден, иначе пусто
     */
    public Optional<User> findByTelegramId(Long telegramId) {
        log.debug("Поиск пользователя по Telegram ID: {}", telegramId);
        return userRepository.findByTelegramId(telegramId);
    }

    /**
     * Проверить существует ли пользователь с таким Telegram ID
     *
     * @param telegramId - Telegram ID пользователя
     * @return true если существует, false если нет
     */
    public boolean existsByTelegramId(Long telegramId) {
        return userRepository.existsByTelegramId(telegramId);
    }

    /**
     * Регистрация нового пользователя
     *
     * @param telegramId - Telegram ID пользователя
     * @param fullName - ФИО пользователя
     * @return созданный пользователь
     * @throws IllegalArgumentException если пользователь уже зарегистрирован
     */
    public User registerUser(Long telegramId, String fullName) {
        log.info("Регистрация нового пользователя: telegramId={}, fullName={}", telegramId, fullName);

        // Проверяем, может пользователь уже зарегистрирован?
        if (userRepository.existsByTelegramId(telegramId)) {
            log.warn("Пользователь с telegramId={} уже зарегистрирован", telegramId);
            throw new IllegalArgumentException("Пользователь уже зарегистрирован!");
        }

        // Создаем нового пользователя
        User user = User.builder()
                .telegramId(telegramId)
                .fullName(fullName)
                .isActive(false)  // По умолчанию неактивен, админ активирует
                .role(null)  // Роль назначит админ
                .build();

        // Сохраняем в БД
        User savedUser = userRepository.save(user);
        log.info("Пользователь успешно зарегистрирован: id={}", savedUser.getId());

        return savedUser;
    }

    /**
     * Получить роль пользователя по Telegram ID
     *
     * @param telegramId - Telegram ID пользователя
     * @return роль пользователя, если найден и активен, иначе Optional.empty()
     */
    public Optional<Role> getRoleByTelegramId(Long telegramId) {
        Optional<User> userOpt = userRepository.findByTelegramId(telegramId);

        if (userOpt.isEmpty()) {
            log.debug("Пользователь с telegramId={} не найден", telegramId);
            return Optional.empty();
        }

        User user = userOpt.get();

        // Проверяем активен ли пользователь
        if (!user.getIsActive()) {
            log.debug("Пользователь с telegramId={} неактивен", telegramId);
            return Optional.empty();
        }

        // Проверяем назначена ли роль
        if (user.getRole() == null) {
            log.debug("Пользователю с telegramId={} не назначена роль", telegramId);
            return Optional.empty();
        }

        return Optional.of(user.getRole());
    }

    /**
     * Проверить является ли пользователь администратором
     *
     * @param telegramId - Telegram ID пользователя
     * @return true если пользователь найден, активен и является админом
     */
    public boolean isAdmin(Long telegramId) {
        Optional<Role> roleOpt = getRoleByTelegramId(telegramId);
        return roleOpt.isPresent() && roleOpt.get() == Role.ADMIN;
    }

    /**
     * Проверить является ли пользователь курьером
     *
     * @param telegramId - Telegram ID пользователя
     * @return true если пользователь найден, активен и является курьером
     */
    public boolean isCourier(Long telegramId) {
        Optional<Role> roleOpt = getRoleByTelegramId(telegramId);
        return roleOpt.isPresent() && roleOpt.get() == Role.COURIER;
    }

    /**
     * Проверить является ли пользователь магазином
     *
     * @param telegramId - Telegram ID пользователя
     * @return true если пользователь найден, активен и является магазином
     */
    public boolean isShop(Long telegramId) {
        Optional<Role> roleOpt = getRoleByTelegramId(telegramId);
        return roleOpt.isPresent() && roleOpt.get() == Role.SHOP;
    }

    /**
     * Обновить роль пользователя
     *
     * @param telegramId - Telegram ID пользователя
     * @param role - новая роль (COURIER, SHOP, ADMIN)
     * @return обновленный пользователь
     * @throws IllegalArgumentException если пользователь не найден
     */
    public User updateUserRole(Long telegramId, Role role) {
        log.info("Обновление роли пользователя: telegramId={}, role={}", telegramId, role);

        // Находим пользователя
        Optional<User> userOpt = userRepository.findByTelegramId(telegramId);
        if (userOpt.isEmpty()) {
            log.warn("Пользователь с telegramId={} не найден", telegramId);
            throw new IllegalArgumentException("Пользователь не найден!");
        }

        User user = userOpt.get();

        // Обновляем роль
        user.setRole(role);

        // Сохраняем в БД (updatedAt автоматически обновится через @UpdateTimestamp)
        User savedUser = userRepository.save(user);
        log.info("Роль пользователя успешно обновлена: telegramId={}, role={}", telegramId, role);

        return savedUser;
    }
}

