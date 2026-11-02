package com.itland.governance.infrastructure.observability;

import org.slf4j.MDC;

/** Scoped MDC for worker threads (Kafka consumer, schedulers, workflow jobs). */
public final class Mdc implements AutoCloseable {

  private final String[] keys;

  private Mdc(String... keys) {
    this.keys = keys;
  }

  public static Mdc of(String requestId, String planId) {
    if (requestId != null) MDC.put("requestId", requestId);
    if (planId != null) MDC.put("planId", planId);
    return new Mdc("requestId", "planId");
  }

  public static Mdc of(String requestId, String planId, String approvalId, String processInstanceId) {
    if (requestId != null) MDC.put("requestId", requestId);
    if (planId != null) MDC.put("planId", planId);
    if (approvalId != null) MDC.put("approvalId", approvalId);
    if (processInstanceId != null) MDC.put("processInstanceId", processInstanceId);
    return new Mdc("requestId", "planId", "approvalId", "processInstanceId");
  }

  @Override
  public void close() {
    for (String k : keys) MDC.remove(k);
  }
}
