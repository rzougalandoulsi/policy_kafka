package com.itland.governance.infrastructure.observability;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Propagates a correlation id (X-Correlation-Id) into logs and the response. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationWebFilter implements WebFilter {

  public static final String HEADER = "X-Correlation-Id";
  public static final String MDC_KEY = "correlationId";

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String id = exchange.getRequest().getHeaders().getFirst(HEADER);
    String correlationId = (id == null || id.isBlank() || id.length() > 128) ? UUID.randomUUID().toString() : id;
    exchange.getResponse().getHeaders().set(HEADER, correlationId);
    return chain.filter(exchange)
        .contextWrite(ctx -> ctx.put(MDC_KEY, correlationId))
        .doOnEach(signal -> {
          if (signal.isOnComplete() || signal.isOnError()) MDC.remove(MDC_KEY);
        });
  }
}
