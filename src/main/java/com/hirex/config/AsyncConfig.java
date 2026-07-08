package com.hirex.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Enables @Async so that AI interview evaluation (InterviewEvaluationService)
 * runs on a background thread instead of blocking the candidate-facing
 * submit-answer / complete-interview HTTP requests while Ollama responds.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "interviewEvaluationExecutor")
    public Executor interviewEvaluationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("interview-eval-");
        executor.initialize();
        return executor;
    }
}
