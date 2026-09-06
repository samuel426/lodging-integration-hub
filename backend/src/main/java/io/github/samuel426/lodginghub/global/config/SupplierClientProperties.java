package io.github.samuel426.lodginghub.global.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("suppliers")
public record SupplierClientProperties(
    Endpoint a,
    Endpoint b,
    Duration connectTimeout,
    Duration responseTimeout,
    Duration requestTimeout,
    int maxInMemoryBytes) {

  public SupplierClientProperties {
    if (a == null
        || b == null
        || !isPositive(connectTimeout)
        || !isPositive(responseTimeout)
        || !isPositive(requestTimeout)
        || maxInMemoryBytes < 1
        || connectTimeout.toMillis() > Integer.MAX_VALUE
        || connectTimeout.toMillis() < 1) {
      throw new IllegalArgumentException("Invalid supplier client configuration");
    }
  }

  private static boolean isPositive(Duration value) {
    return value != null && !value.isNegative() && !value.isZero();
  }

  public record Endpoint(URI baseUrl, String apiKey) {
    public Endpoint {
      if (baseUrl == null
          || baseUrl.getHost() == null
          || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
          || baseUrl.getUserInfo() != null
          || baseUrl.getQuery() != null
          || baseUrl.getFragment() != null
          || apiKey == null
          || apiKey.isBlank()
          || apiKey.chars().anyMatch(Character::isISOControl)) {
        throw new IllegalArgumentException("Invalid supplier endpoint configuration");
      }
    }

    @Override
    public String toString() {
      return "Endpoint[redacted]";
    }
  }
}
