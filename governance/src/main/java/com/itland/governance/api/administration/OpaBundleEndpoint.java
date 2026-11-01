package com.itland.governance.api.administration;

import com.itland.governance.application.policy.PolicyBundleService;
import com.itland.governance.configuration.Blocking;
import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Bundle service endpoint polled by OPA (bearer token guarded by BundleTokenFilter; internal network only).
 * Supports ETag / If-None-Match so OPA long-polls cheaply and only reloads on activation.
 */
@RestController
@Hidden
public class OpaBundleEndpoint {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(OpaBundleEndpoint.class);

  private final PolicyBundleService service;
  private final Blocking blocking;

  public OpaBundleEndpoint(PolicyBundleService service, Blocking blocking) {
    this.service = service;
    this.blocking = blocking;
  }

  @GetMapping("/internal/opa/bundles/{name}")
  public Mono<ResponseEntity<byte[]>> bundle(@PathVariable String name, @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
    if (!service.bundles().contains(name)) {
      return Mono.just(ResponseEntity.notFound().build());
    }
    return blocking.call(() -> service.serveActive(name)).map(opt -> opt.map(served -> {
      String etag = "\"" + served.sha256() + "\"";
      if (etag.equals(ifNoneMatch)) {
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).<byte[]>build();
      }
      log.info("Serving policy bundle {} revision {} to OPA", name, served.revision());
      return ResponseEntity.ok().eTag(etag).header("X-Bundle-Revision", served.revision())
          .contentType(MediaType.APPLICATION_OCTET_STREAM).body(served.bytes());
    }).orElseGet(() -> ResponseEntity.notFound().build()));
  }
}
