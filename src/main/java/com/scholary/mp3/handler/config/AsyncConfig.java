package com.scholary.mp3.handler.config;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Configuration for async task execution.
 *
 * <p>Sets up a bounded thread pool for processing async transcription jobs. The pool size and queue
 * capacity are configurable to control resource usage.
 */
@Configuration
public class AsyncConfig {

  @Bean(name = "taskExecutor")
  public Executor taskExecutor(
      @Value("${transcription.asyncExecutorThreads}") int threads,
      @Value("${transcription.asyncExecutorQueueSize}") int queueSize) {

    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(threads);
    executor.setMaxPoolSize(threads);
    executor.setQueueCapacity(queueSize);
    executor.setThreadNamePrefix("transcription-");
    executor.initialize();
    return executor;
  }
}
