package com.itland.governance.infrastructure.opa;

import com.itland.governance.configuration.GovernanceProperties;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Thin, fail-closed client for the OPA Data API. Transient failures (connection, timeout, 5xx) are retried
 * a bounded number of times; everything else is surfaced as {@link OpaException}. An absent result means
 * the policy bundle is not loaded and is treated as an error, never as approval.
 */
@Component
public class OpaClient {

  private static final Logger log = LoggerFactory.getLogger(OpaClient.class);

  private final WebClient client;
  private final GovernanceProperties.Opa opa;

  public OpaClient(WebClient.Builder builder, GovernanceProperties properties) {
    this.opa = properties.opa();
    HttpClient http = HttpClient.create()
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) opa.connectTimeout().toMillis())
        .responseTimeout(opa.responseTimeout());
    this.client = builder.clone()
        .baseUrl(opa.url())
        .clientConnector(new ReactorClientHttpConnector(http))
        .build();
  }

  public Mono<OpaEvaluation> evaluate(Map<String, Object> input) {
    return client.post()
        .uri(b -> b.path(opa.decisionPath()).queryParam("provenance", "true").build())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("input", input))
        .retrieve()
        .onStatus(HttpStatusCode::is5xxServerError, r -> r.createException().map(e -> new OpaException("OPA server error " + r.statusCode(), e, true)))
        .onStatus(HttpStatusCode::isError, r -> r.createException().map(e -> new OpaException("OPA rejected the query: " + r.statusCode(), e, false)))
        .bodyToMono(OpaEvaluation.class)
        .onErrorMap(OpaClient::mapError)
        .retryWhen(Retry.backoff(opa.maxRetries(), opa.retryBackoff())
            .filter(e -> e instanceof OpaException oe && oe.transientFailure())
            .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
        .flatMap(evaluation -> {
          if (evaluation == null || evaluation.result() == null || evaluation.result().decision() == null) {
            return Mono.error(new OpaException("OPA returned no decision: policy bundle not loaded", false));
          }
          return Mono.just(evaluation);
        });
  }

  /** Revision per bundle name OPA currently runs, from data.system.bundles.<name>.manifest.revision. Empty when none is loaded. */
  public Mono<Map<String, String>> loadedRevisions() {
    return client.get()
        .uri("/v1/data/system/bundles")
        .retrieve()
        .bodyToMono(SystemBundles.class)
        .map(r -> {
          Map<String, String> out = new java.util.TreeMap<>();
          if (r.result() != null) r.result().forEach((name, b) -> {
            if (b != null && b.manifest() != null && b.manifest().revision() != null) out.put(name, b.manifest().revision());
          });
          return out;
        })
        .onErrorMap(OpaClient::mapError);
  }

  /** data.governance.core.ready: true only when every required bundle is loaded with a compatible contract version. */
  public Mono<Boolean> ready() {
    return client.get()
        .uri(opa.readyPath())
        .retrieve()
        .bodyToMono(BoolResult.class)
        .map(r -> Boolean.TRUE.equals(r.result()))
        .onErrorMap(OpaClient::mapError);
  }

  /**
   * Merged policy catalogue of the loaded bundles (data.governance.core.policy_catalog): id, bundle, kind, severity,
   * bypassable flag, section, stages, description. Empty when no bundle is loaded.
   */
  public Mono<List<Map<String, Object>>> catalog() {
    return client.get()
        .uri(opa.catalogPath())
        .retrieve()
        .onStatus(HttpStatusCode::is5xxServerError, r -> r.createException().map(e -> new OpaException("OPA server error " + r.statusCode(), e, true)))
        .bodyToMono(CatalogResult.class)
        .map(r -> r.result() == null ? List.<Map<String, Object>>of() : r.result())
        .onErrorMap(OpaClient::mapError);
  }

  public Mono<Boolean> healthy() {
    return client.get().uri("/health?bundles=true").retrieve().toBodilessEntity()
        .map(e -> e.getStatusCode().is2xxSuccessful())
        .onErrorResume(e -> {
          log.debug("OPA health check failed: {}", e.toString());
          return Mono.just(false);
        });
  }

  private static Throwable mapError(Throwable e) {
    if (e instanceof OpaException) return e;
    if (e instanceof WebClientRequestException || e instanceof TimeoutException
        || e instanceof io.netty.handler.timeout.ReadTimeoutException) {
      return new OpaException("OPA unreachable: " + e.getMessage(), e, true);
    }
    if (e instanceof WebClientResponseException w) {
      return new OpaException("OPA responded " + w.getStatusCode(), e, w.getStatusCode().is5xxServerError());
    }
    return new OpaException("OPA call failed: " + e.getMessage(), e, false);
  }

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  record SystemBundles(Map<String, BundleStatus> result) {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  record BundleStatus(BundleManifest manifest) {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  record BundleManifest(String revision) {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  record BoolResult(Boolean result) {}

  @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
  record CatalogResult(List<Map<String, Object>> result) {}
}
