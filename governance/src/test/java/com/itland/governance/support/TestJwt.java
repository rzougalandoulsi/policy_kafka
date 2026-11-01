package com.itland.governance.support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.List;

/** Mints RS256 tokens for tests; the matching decoder is injected as a bean so no issuer is contacted. */
public final class TestJwt {

  private static final KeyPair KEYS;

  static {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
      g.initialize(2048);
      KEYS = g.generateKeyPair();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private TestJwt() {}

  public static ReactiveJwtDecoder decoder() {
    return NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
  }

  public static String token(String subject, List<String> teams, List<String> environments, List<String> roles) {
    try {
      JWTClaimsSet claims = new JWTClaimsSet.Builder()
          .subject(subject).issuer("https://test-issuer").audience("governance")
          .issueTime(new Date()).expirationTime(new Date(System.currentTimeMillis() + 3_600_000))
          .claim("teams", teams).claim("environments", environments).claim("roles", roles)
          .build();
      SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), claims);
      jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
