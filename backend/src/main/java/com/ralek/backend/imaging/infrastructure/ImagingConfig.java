package com.ralek.backend.imaging.infrastructure;

import com.ralek.backend.imaging.analysis.ImagingAnalysisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({
        ImagingProperties.class,
        GoogleHealthcareProperties.class,
        ImagingAnalysisProperties.class
})
public class ImagingConfig {

    @Bean("imagingTaskExecutor")
    TaskExecutor imagingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("imaging-import-");
        executor.initialize();
        return executor;
    }

    @Bean("analysisTaskExecutor")
    TaskExecutor analysisTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("imaging-analysis-");
        executor.initialize();
        return executor;
    }
}
