package com.metricol.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class CreativeDirectorExecutorConfig {
    @Bean(name = "creativeDirectorExecutor")
    ThreadPoolTaskExecutor creativeDirectorExecutor(CreativeDirectorProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkers());
        executor.setMaxPoolSize(properties.getWorkers());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("creative-director-");
        executor.initialize();
        return executor;
    }
}
