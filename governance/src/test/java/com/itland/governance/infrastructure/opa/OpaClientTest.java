package com.itland.governance.infrastructure.opa;

import com.itland.governance.configuration.GovernanceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpaClientTest {

  @Test
  void unreachableOpaFailsClosedWithTransientError() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
    GovernanceProperties props = new GovernanceProperties(null,
        new GovernanceProperties.Opa("http://127.0.0.1:" + port, "/v1/data/governance/decision", "governance",
            Duration.ofMillis(300), Duration.ofMillis(500), 1, Duration.ofMillis(10), true, "t"), null, null, null, null);
    OpaClient client = new OpaClient(WebClient.builder(), props);
    StepVerifier.create(client.evaluate(Map.of("stage", "REQUEST")))
        .expectErrorSatisfies(e -> {
          assertThat(e).isInstanceOf(OpaException.class);
          assertThat(((OpaException) e).transientFailure()).isTrue();
        })
        .verify(Duration.ofSeconds(10));
    StepVerifier.create(client.healthy()).expectNext(false).verifyComplete();
  }
}
