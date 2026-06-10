package com.arias.email;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor dedicado de UN solo thread para el envío de mails.
 *
 * <p>Resend limita a 2 requests/segundo. Con el pool default de {@code @Async}
 * los envíos salen en paralelo y los que exceden el límite reciben 429 y se
 * pierden (el envío es best-effort, sin retry el mail muere en silencio).
 * Serializar los envíos + retry ante 429 en {@link EmailService} lo resuelve.
 */
@Configuration
public class EmailExecutorConfig {

    @Bean(name = "emailExecutor")
    public ThreadPoolTaskExecutor emailExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("email-");
        executor.initialize();
        return executor;
    }
}
