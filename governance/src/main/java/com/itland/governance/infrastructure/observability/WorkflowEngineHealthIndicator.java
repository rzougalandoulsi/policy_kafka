package com.itland.governance.infrastructure.observability;

import com.itland.governance.application.approvals.workflow.ApprovalProcess;
import com.itland.governance.configuration.Blocking;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Readiness of the embedded workflow engine: UP only when every approval process definition is deployed (the engine
 * answers queries, so its schema is in place). Dead-letter jobs are reported as a detail and a metric, not as DOWN: a
 * replica with a stuck expiry can still serve approvals.
 */
@Component("workflowEngine")
public class WorkflowEngineHealthIndicator implements ReactiveHealthIndicator {

  private final RepositoryService repository;
  private final ManagementService management;
  private final Blocking blocking;

  public WorkflowEngineHealthIndicator(RepositoryService repository, ManagementService management, Blocking blocking) {
    this.repository = repository;
    this.management = management;
    this.blocking = blocking;
  }

  @Override
  public Mono<Health> health() {
    return blocking.call(() -> {
          Map<String, Object> definitions = new LinkedHashMap<>();
          boolean complete = true;
          for (String key : ApprovalProcess.DEFINITION_KEYS) {
            ProcessDefinition latest = repository.createProcessDefinitionQuery().processDefinitionKey(key).latestVersion().singleResult();
            if (latest == null) {
              complete = false;
              definitions.put(key, "missing");
            } else {
              definitions.put(key, "v" + latest.getVersion());
            }
          }
          long deadLetters = management.createDeadLetterJobQuery().count();
          return (complete ? Health.up() : Health.down())
              .withDetail("definitions", definitions).withDetail("deadLetterJobs", deadLetters).build();
        })
        .onErrorResume(e -> Mono.just(Health.down().withDetail("reason", e.toString()).build()));
  }
}
