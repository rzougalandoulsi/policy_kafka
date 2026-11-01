package com.itland.governance.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JDBC and Kafka calls are blocking. They run on this dedicated, bounded pool so Reactor's event-loop
 * threads (Netty) never block. Everything that touches the database goes through {@link Blocking}.
 */
@Configuration
public class BlockingExecutionConfig {

  @Bean(destroyMethod = "shutdown")
  public ExecutorService blockingExecutor() {
    return Executors.newFixedThreadPool(Math.max(8, Runtime.getRuntime().availableProcessors() * 2),
        Thread.ofPlatform().name("gov-blocking-", 0).factory());
  }

  @Bean
  public Scheduler blockingScheduler(ExecutorService blockingExecutor) {
    return Schedulers.fromExecutorService(blockingExecutor, "gov-blocking");
  }

  @Bean
  public TransactionTemplate transactionTemplate(PlatformTransactionManager tm) {
    return new TransactionTemplate(tm);
  }

  @Bean
  public Blocking blocking(Scheduler blockingScheduler, TransactionTemplate transactionTemplate) {
    return new Blocking(blockingScheduler, transactionTemplate);
  }

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
