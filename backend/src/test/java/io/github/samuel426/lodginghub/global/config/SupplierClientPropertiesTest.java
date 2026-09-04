package io.github.samuel426.lodginghub.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SupplierClientPropertiesTest {
  @Test
  void stringRepresentationDoesNotExposeConnectionDetails() {
    var endpoint = endpoint();
    assertThat(endpoint.toString())
        .doesNotContain(endpoint.apiKey(), endpoint.baseUrl().toString());
    assertThat(properties(Duration.ofMillis(500), 4096).toString())
        .doesNotContain(endpoint.apiKey(), endpoint.baseUrl().toString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ftp://localhost",
        "http://user:pass@localhost",
        "http://localhost?key=value",
        "http://localhost#fragment",
        "/relative"
      })
  void refusesUnsupportedOrCredentialBearingUrl(String url) {
    assertThatThrownBy(() -> new SupplierClientProperties.Endpoint(URI.create(url), "mock-only"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesInvalidTimeoutMemoryLimitAndHeader() {
    assertThatThrownBy(() -> properties(Duration.ZERO, 4096))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> properties(Duration.ofMillis(-1), 4096))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> properties(Duration.ofDays(30), 4096))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> properties(Duration.ofMillis(500), 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SupplierClientProperties.Endpoint(
                    URI.create("http://localhost"), "bad\r\nheader"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private SupplierClientProperties properties(Duration connectTimeout, int memoryBytes) {
    return new SupplierClientProperties(
        endpoint(),
        endpoint(),
        connectTimeout,
        Duration.ofSeconds(2),
        Duration.ofSeconds(2),
        memoryBytes);
  }

  private SupplierClientProperties.Endpoint endpoint() {
    return new SupplierClientProperties.Endpoint(
        URI.create("http://localhost:9090"), "mock-test-key");
  }
}
