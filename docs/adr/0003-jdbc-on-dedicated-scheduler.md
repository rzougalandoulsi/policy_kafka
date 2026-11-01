# ADR 0003: JDBC + Flyway on a dedicated bounded scheduler (not R2DBC)
Flyway needs JDBC anyway and the workload is low-volume control plane traffic. All blocking work (JDBC, Kafka producer waits) is bridged with `Blocking` onto a dedicated executor, so Reactor event-loop threads never block. R2DBC would add a second driver and complicate transactional outbox semantics.
