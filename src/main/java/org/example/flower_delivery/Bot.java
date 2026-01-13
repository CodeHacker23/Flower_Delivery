package org.example.flower_delivery;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Главный класс бота - это как "мозг" который слушает сообщения от Telegram
 * 
 * TelegramLongPollingBot - это способ работы бота:
 * - Бот постоянно спрашивает у Telegram: "Есть новые сообщения?"
 * - Если есть - получает их и обрабатывает
 * - Это как постоянно проверять почтовый ящик
 * 
 * Есть еще WebhookBot (более продвинутый, но сложнее настраивать)
 * Для начала LongPolling - проще и надежнее
 */
@Component
@RequiredArgsConstructor
public class Bot extends TelegramLongPollingBot {
    
    // @Value - говорит Spring: "Возьми значение из application.properties"
    // ${telegram.bot.token} - имя свойства из properties файла
    // Если свойства нет - упадет с ошибкой (и правильно, блять!)
    @Value("${telegram.bot.token}")
    private String botToken;
    
    @Value("${telegram.bot.username}")
    private String botUsername;
    
    /**
     * Метод который вызывается КАЖДЫЙ РАЗ когда приходит новое сообщение/команда/кнопка
     * 
     * Update - это объект который содержит ВСЮ информацию о событии:
     * - Сообщение (текст, кто отправил, когда)
     * - Команда (/start, /help и т.д.)
     * - Нажатие на кнопку (callback)
     * - Геолокация, фото, документ - всё что угодно!
     * 
     * Сейчас метод пустой - тут будет логика обработки (пока не реализована)
     */
    @Override
    public void onUpdateReceived(Update update) {
        // TODO: Здесь будет обработка сообщений
        // Пока ничего не делаем, просто принимаем обновления
    }

    /**
     * Возвращает имя бота (username без @)
     * 
     * Telegram использует это для идентификации бота
     * Должно совпадать с тем, что в application.properties
     * 
     * @return username бота (например: "FlowerDelivery74bot")
     */
    @Override
    public String getBotUsername() {
        return botUsername;
    }

    /**
     * Возвращает токен бота для авторизации в Telegram API
     * 
     * Токен - это как пароль от бота. Получаешь у @BotFather в Telegram
     * БЕЗ ТОКЕНА бот не сможет подключиться к Telegram!
     * 
     * ВАЖНО: Токен теперь берется из application.properties
     * Это безопаснее чем хардкодить в коде (можно вынести в переменные окружения на проде)
     * 
     * @return токен бота (длинная строка типа: "123456:ABC-DEF...")
     */
    @Override
    public String getBotToken() {
        return botToken;
    }
}
