package com.itland.governance.configuration;

import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

/** Bridges blocking JDBC work into Reactor pipelines on the dedicated scheduler. */
public final class Blocking {

  private final Scheduler scheduler;
  private final TransactionTemplate tx;

  public Blocking(Scheduler scheduler, TransactionTemplate tx) {
    this.scheduler = scheduler;
    this.tx = tx;
  }

  /** Runs a non-transactional blocking call off the event loop. */
  public <T> Mono<T> call(Callable<T> callable) {
    return Mono.fromCallable(callable).subscribeOn(scheduler);
  }

  /** Runs the supplier inside one database transaction, off the event loop. */
  public <T> Mono<T> inTransaction(Supplier<T> work) {
    return Mono.fromCallable(() -> tx.execute(status -> work.get())).subscribeOn(scheduler);
  }

  public Mono<Void> inTransactionVoid(Runnable work) {
    return Mono.<Void>fromRunnable(() -> tx.executeWithoutResult(status -> work.run())).subscribeOn(scheduler);
  }

  /** For callers already on a worker thread (Kafka consumer, schedulers): synchronous transaction. */
  public <T> T transactional(Supplier<T> work) {
    return tx.execute(status -> work.get());
  }

  public void transactionalVoid(Runnable work) {
    tx.executeWithoutResult(status -> work.run());
  }

  public Scheduler scheduler() {
    return scheduler;
  }
}
