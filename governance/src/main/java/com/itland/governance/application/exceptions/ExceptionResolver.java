package com.itland.governance.application.exceptions;

import com.itland.governance.domain.exception.PolicyException;
import com.itland.governance.infrastructure.persistence.PolicyExceptionRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Resolves the exceptions Governance (never the client) hands to OPA for one evaluation. */
@Component
public class ExceptionResolver {

  private final PolicyExceptionRepository repository;

  public ExceptionResolver(PolicyExceptionRepository repository) {
    this.repository = repository;
  }

  public List<PolicyException> resolve(String principalId, String teamId, String environment, Instant now) {
    return repository.findActive(teamId, environment, principalId, now).stream()
        .filter(e -> e.activeAt(now) && e.appliesTo(principalId, teamId, environment))
        .toList();
  }
}
