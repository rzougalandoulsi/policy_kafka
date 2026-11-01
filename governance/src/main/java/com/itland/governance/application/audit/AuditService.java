package com.itland.governance.application.audit;

import com.itland.governance.infrastructure.persistence.AuditRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Map;

/** Every governance-relevant mutation is recorded here, inside the caller's transaction. */
@Service
public class AuditService {

  private final AuditRepository repository;
  private final Clock clock;

  public AuditService(AuditRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  public void record(String actor, String action, String targetType, String targetId, String requestId, Map<String, Object> details) {
    repository.insert(clock.instant(), actor, action, targetType, targetId, requestId, details);
  }

  public void record(String actor, String action, String targetType, String targetId, Map<String, Object> details) {
    record(actor, action, targetType, targetId, null, details);
  }
}
