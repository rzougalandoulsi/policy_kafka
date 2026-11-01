package com.itland.governance.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JsonConfig {

  /** Shared JSON codec for jsonb columns and OPA payloads (Jackson 3, as used by WebFlux in Spring Boot 4). */
  @Bean
  public Json json(JsonMapper jsonMapper) {
    return new Json(jsonMapper);
  }
}
