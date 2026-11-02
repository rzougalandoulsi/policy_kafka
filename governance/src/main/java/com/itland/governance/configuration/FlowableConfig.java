package com.itland.governance.configuration;

import org.flowable.spring.SpringProcessEngineConfiguration;
import org.flowable.spring.boot.EngineConfigurationConfigurer;
import org.flowable.spring.boot.process.Process;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Embedded Flowable process engine for human approvals (ADR 0011). The engine shares the Governance datasource and
 * Spring transaction manager: a task completion, the request transition, the quota reservation and the outbox row it
 * produces commit or roll back together. Only the BPMN engine is configured (no IDM, no event registry, no REST); the
 * remaining knobs are {@code flowable.*} properties in application.yml.
 */
@Configuration
public class FlowableConfig {

  /**
   * Threads the engine's async executor runs jobs on: approval deadline timers and the nightly history cleaning. Never
   * Netty threads. Small on purpose: each job does JDBC and may call OPA. Shutdown waits for running jobs (within the
   * pod's graceful termination window) so an expiry that is half-way through is not torn in two.
   */
  @Bean
  @Process
  public AsyncTaskExecutor flowableJobExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("gov-workflow-");
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(64);
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(20);
    return executor;
  }

  @Bean
  public EngineConfigurationConfigurer<SpringProcessEngineConfiguration> governanceProcessEngineConfigurer(DataSource dataSource) {
    // The engine's schema lock lives in ACT_GE_PROPERTY, so it can only guard upgrades (every start after the first).
    // The very first creation runs unlocked: should two pods race it on an empty database, PostgreSQL rejects the
    // second DDL, that pod restarts and finds the schema in place.
    boolean engineSchemaPresent = engineSchemaPresent(dataSource);
    return configuration -> {
      configuration.setUseLockForDatabaseSchemaUpdate(engineSchemaPresent);
      // Below the 25 s shutdown phase of the openshift profile and the 40 s pod grace period.
      configuration.setAsyncExecutorSecondsToWaitOnShutdown(20);
    };
  }

  static boolean engineSchemaPresent(DataSource dataSource) {
    try (Connection connection = dataSource.getConnection();
         PreparedStatement statement = connection.prepareStatement("SELECT to_regclass('act_ge_property')");
         ResultSet rs = statement.executeQuery()) {
      return rs.next() && rs.getString(1) != null;
    } catch (SQLException e) {
      return false;
    }
  }
}
