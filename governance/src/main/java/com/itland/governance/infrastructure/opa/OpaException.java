package com.itland.governance.infrastructure.opa;

/** Any failure to obtain a trustworthy decision from OPA. Callers must fail closed. */
public class OpaException extends RuntimeException {
  private final boolean transientFailure;

  public OpaException(String message, Throwable cause, boolean transientFailure) {
    super(message, cause);
    this.transientFailure = transientFailure;
  }

  public OpaException(String message, boolean transientFailure) {
    this(message, null, transientFailure);
  }

  public boolean transientFailure() {
    return transientFailure;
  }
}
