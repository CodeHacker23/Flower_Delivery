package org.example.flower_delivery;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Конфигурация асинхронного выполнения.
 * Отдельный пул потоков для отправки сообщений в Telegram, чтобы не блокировать обработку апдейтов.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "telegramExecutor")
    public Executor telegramExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Нам важно не копить очередь: если пользователь/сервер шлёт много запросов,
        // лучше быстро выполнить последнее и отбрасывать лишнее.
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("telegram-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}

