# 🗄️ РЕПОЗИТОРИЙ USERREPOSITORY — ПОЛНЫЙ ГАЙД (КАК РАБОТАТЬ С БД БЕЗ SQL)

**Версия:** 1.0  
**Для кого:** Для тех, кто хочет понять Spring Data JPA и репозитории  
**Стиль:** Максимально подробно, с матом, юмором и объяснением каждой строчки

---

## 🎯 ЧТО МЫ ДЕЛАЕМ

Создаём интерфейс `UserRepository`, который:
- **Позволяет** работать с БД без написания SQL
- **Автоматически** генерирует SQL запросы из названий методов
- **Даёт** готовые методы: `save()`, `findById()`, `findAll()`, `delete()`

**Простыми словами:**  
Вместо того чтобы писать SQL вручную (`SELECT * FROM users WHERE telegram_id = 123`), ты пишешь Java код (`userRepository.findByTelegramId(123L)`), а Spring Data JPA сам переводит это в SQL.

---

## 📊 ЧТО ТАКОЕ РЕПОЗИТОРИЙ (REPOSITORY)

### Аналогия из жизни:

**Репозиторий** — это как **переводчик** между тобой и БД.

- **Ты говоришь на Java:** "Дай мне пользователя с telegramId = 123"
- **Репозиторий переводит на SQL:** `SELECT * FROM users WHERE telegram_id = 123`
- **БД выполняет SQL и возвращает результат**
- **Репозиторий переводит результат обратно в Java объект**

**Без репозитория:**
```java
// Придётся писать SQL вручную (хуйня!)
String sql = "SELECT * FROM users WHERE telegram_id = ?";
PreparedStatement stmt = connection.prepareStatement(sql);
stmt.setLong(1, 123L);
ResultSet rs = stmt.executeQuery();
// ... и ещё 50 строк кода для обработки результата
```

**С репозиторием:**
```java
// Всё автоматически!
Optional<User> user = userRepository.findByTelegramId(123L);
```

---

## 🔧 ЧТО ТАКОЕ SPRING DATA JPA

### JPA (Java Persistence API)

**Что это:**  
Стандарт Java для работы с БД. Это **интерфейс** (контракт), который говорит: "Вот как нужно работать с БД в Java".

---

### Spring Data JPA

**Что это:**  
**Надстройка** над JPA, которая:
- Автоматически создаёт SQL из названий методов
- Даёт готовые методы: `save()`, `findById()`, `findAll()`, `delete()`
- Управляет транзакциями
- Кэширует запросы

**Аналогия:**  
Spring Data JPA — это как **автопилот** для самолёта. Ты говоришь "лети на север", а он сам управляет рулями, двигателями, навигацией.

---

## 📝 КАК РАБОТАЕТ SPRING DATA JPA

### 1. Ты создаёшь интерфейс:

```java
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByTelegramId(Long telegramId);
}
```

### 2. Spring Data JPA автоматически создаёт реализацию:

```java
// Spring сам создаёт этот класс (ты его не видишь, но он есть!)
@Repository
public class UserRepositoryImpl implements UserRepository {
    
    @Autowired
    private EntityManager entityManager;
    
    @Override
    public Optional<User> findByTelegramId(Long telegramId) {
        // Spring сам генерирует SQL:
        String sql = "SELECT u FROM User u WHERE u.telegramId = :telegramId";
        TypedQuery<User> query = entityManager.createQuery(sql, User.class);
        query.setParameter("telegramId", telegramId);
        return Optional.ofNullable(query.getSingleResult());
    }
}
```

### 3. Ты используешь репозиторий:

```java
@Autowired
private UserRepository userRepository;

public void someMethod() {
    Optional<User> user = userRepository.findByTelegramId(123L);
    // Всё работает автоматически!
}
```

**Всё автоматически!** Ты не пишешь SQL, Spring Data JPA делает это за тебя.

---

## 🎯 JPA REPOSITORY — БАЗОВЫЙ ИНТЕРФЕЙС

### `JpaRepository<User, UUID>`

**Что это:**  
Базовый интерфейс Spring Data JPA, который даёт готовые методы для работы с БД.

**Параметры:**
- `User` — тип сущности (модель, с которой работаем)
- `UUID` — тип первичного ключа (ID)

**Что даёт:**
- `save(User user)` — сохранить/обновить пользователя
- `findById(UUID id)` — найти по ID
- `findAll()` — найти всех
- `delete(User user)` — удалить пользователя
- `existsById(UUID id)` — проверить существует ли
- `count()` — посчитать количество записей

**Пример:**
```java
// Сохранить пользователя
User user = User.builder()
    .telegramId(123L)
    .fullName("Иван Иванов")
    .build();
userRepository.save(user); // INSERT INTO users ...

// Найти по ID
Optional<User> userOpt = userRepository.findById(user.getId()); // SELECT * FROM users WHERE id = ...

// Удалить
userRepository.delete(user); // DELETE FROM users WHERE id = ...
```

---

## 🔍 КАК SPRING DATA JPA ПЕРЕВОДИТ МЕТОДЫ В SQL

### Правила именования методов:

Spring Data JPA **читает название метода** и автоматически генерирует SQL.

**Формат:** `findBy + Поле + Условие`

### Примеры:

#### 1. `findByTelegramId(Long telegramId)`

**Что делает:**  
Находит пользователя по `telegramId`.

**Как Spring переводит:**
```java
findByTelegramId(Long telegramId)
```

**В SQL:**
```sql
SELECT * FROM users WHERE telegram_id = ?
```

**Использование:**
```java
Optional<User> user = userRepository.findByTelegramId(123L);
```

---

#### 2. `existsByTelegramId(Long telegramId)`

**Что делает:**  
Проверяет существует ли пользователь с таким `telegramId`.

**Как Spring переводит:**
```java
existsByTelegramId(Long telegramId)
```

**В SQL:**
```sql
SELECT COUNT(*) > 0 FROM users WHERE telegram_id = ?
```

**Использование:**
```java
boolean exists = userRepository.existsByTelegramId(123L);
if (exists) {
    System.out.println("Пользователь уже зарегистрирован!");
}
```

---

#### 3. `findByRole(Role role)`

**Что делает:**  
Находит всех пользователей с определённой ролью.

**Как Spring переводит:**
```java
findByRole(Role role)
```

**В SQL:**
```sql
SELECT * FROM users WHERE role = ?
```

**Использование:**
```java
List<User> couriers = userRepository.findByRole(Role.COURIER);
```

---

#### 4. `findByRoleAndIsActiveTrue(Role role)`

**Что делает:**  
Находит всех активных пользователей с определённой ролью.

**Как Spring переводит:**
```java
findByRoleAndIsActiveTrue(Role role)
```

**В SQL:**
```sql
SELECT * FROM users WHERE role = ? AND is_active = true
```

**Использование:**
```java
List<User> activeCouriers = userRepository.findByRoleAndIsActiveTrue(Role.COURIER);
```

---

## 📋 ПРАВИЛА ИМЕНОВАНИЯ МЕТОДОВ

### Ключевые слова:

- `findBy` — найти (SELECT)
- `existsBy` — проверить существует ли (SELECT COUNT)
- `countBy` — посчитать количество (SELECT COUNT)
- `deleteBy` — удалить (DELETE)

### Условия:

- `And` — И (AND в SQL)
- `Or` — ИЛИ (OR в SQL)
- `Is` / `Equals` — равно (=)
- `IsNot` / `Not` — не равно (!=)
- `IsNull` — NULL
- `IsNotNull` / `NotNull` — не NULL
- `True` — true
- `False` — false
- `Like` — LIKE в SQL
- `Containing` — содержит (LIKE %...%)
- `GreaterThan` — больше (>)
- `LessThan` — меньше (<)
- `Between` — между (BETWEEN)

### Примеры сложных методов:

```java
// Найти всех активных курьеров
List<User> findByRoleAndIsActiveTrue(Role role);

// Найти всех пользователей с телефоном
List<User> findByPhoneIsNotNull();

// Найти всех пользователей созданных после определённой даты
List<User> findByCreatedAtAfter(LocalDateTime date);

// Найти всех пользователей по имени (частичное совпадение)
List<User> findByFullNameContaining(String name);
```

---

## 🎯 ПОЛНЫЙ КОД USERREPOSITORY

Вот как будет выглядеть полный интерфейс `UserRepository`:

```java
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
 * <h2>Что это такое:</h2>
 * Репозиторий — это интерфейс, который позволяет работать с БД без написания SQL.
 * Spring Data JPA автоматически создаёт реализацию этого интерфейса и генерирует SQL запросы
 * из названий методов.
 * 
 * <h2>Как это работает:</h2>
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
```

---

## 🎯 ЗАДАНИЕ ДЛЯ ТЕБЯ

**Создай файл `src/main/java/org/example/flower_delivery/repository/UserRepository.java` и напиши туда интерфейс `UserRepository`!**

Используй код выше как шаблон, но:
- **Не копируй слепо** — понимай каждую строчку
- **Читай комментарии** — они объясняют зачем каждый метод
- **Задавай вопросы** — если что-то непонятно

После того как напишешь — пришли код, и я разберу каждую строчку и проверю твоё понимание!

---

## ❓ ВОПРОСЫ ДЛЯ ПРОВЕРКИ (БУДЬ ГОТОВ!)

1. **Зачем нужна аннотация `@Repository`?** (чтобы Spring знал, что это репозиторий и создал для него реализацию)

2. **Зачем наследоваться от `JpaRepository<User, UUID>`?** (чтобы получить готовые методы: save, findById, findAll, delete)

3. **Как Spring Data JPA переводит `findByTelegramId(Long telegramId)` в SQL?** (читает название метода и генерирует SQL: SELECT * FROM users WHERE telegram_id = ?)

4. **Зачем возвращать `Optional<User>` вместо просто `User`?** (чтобы избежать NullPointerException, если пользователь не найден)

5. **Что делает метод `existsByTelegramId`?** (проверяет существует ли пользователь с таким Telegram ID, возвращает true/false)

6. **Как Spring переведёт `findByRoleAndIsActiveTrue(Role role)` в SQL?** (SELECT * FROM users WHERE role = ? AND is_active = true)

---

**Пиши код, блять! Не копируй — понимай!** 🚀
