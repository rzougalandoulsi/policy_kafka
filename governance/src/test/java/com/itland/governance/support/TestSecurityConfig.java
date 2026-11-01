package com.itland.governance.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

@TestConfiguration
public class TestSecurityConfig {
  @Bean
  @Primary
  public ReactiveJwtDecoder testJwtDecoder() {
    return TestJwt.decoder();
  }
}
