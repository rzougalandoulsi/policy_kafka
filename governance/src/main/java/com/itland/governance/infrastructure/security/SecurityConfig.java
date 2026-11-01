package com.itland.governance.infrastructure.security;

import com.itland.governance.configuration.GovernanceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Two chains: (1) the OPA bundle endpoint, guarded only by the shared-secret {@link BundleTokenFilter} (OPA presents a
 * static bearer token, not a JWT); (2) everything else as a JWT resource server. Roles claim -> ROLE_* authorities
 * (e.g. governance-admin -> ROLE_GOVERNANCE_ADMIN).
 */
@Configuration
public class SecurityConfig {

  @Bean
  @Order(1)
  public SecurityWebFilterChain bundleEndpointChain(ServerHttpSecurity http, BundleTokenFilter bundleTokenFilter) {
    http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/internal/opa/**"))
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .addFilterAt(bundleTokenFilter, SecurityWebFiltersOrder.AUTHENTICATION)
        .authorizeExchange(ex -> ex.anyExchange().permitAll());
    return http.build();
  }

  @Bean
  @Order(2)
  public SecurityWebFilterChain apiChain(ServerHttpSecurity http, GovernanceProperties properties) {
    List<String> permit = new ArrayList<>(List.of(
        "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
        "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**", "/webjars/**"));
    permit.addAll(properties.security().extraPermitPaths());

    http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .authorizeExchange(ex -> ex
            .pathMatchers(permit.toArray(String[]::new)).permitAll()
            .anyExchange().authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter(properties.security()))));
    return http.build();
  }

  private static Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtConverter(GovernanceProperties.Security security) {
    return jwt -> {
      Collection<GrantedAuthority> authorities = CallerContextResolver.stringSet(jwt.getClaim(security.rolesClaim())).stream()
          .map(r -> "ROLE_" + r.toUpperCase(Locale.ROOT).replace('-', '_'))
          .<GrantedAuthority>map(SimpleGrantedAuthority::new)
          .toList();
      return Mono.just(new JwtAuthenticationToken(jwt, authorities, jwt.getSubject()));
    };
  }
}
