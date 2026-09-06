package io.github.samuel426.lodginghub.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.samuel426.lodginghub.supplier.a.client.SupplierACatalogClient;
import io.github.samuel426.lodginghub.supplier.model.SupplierCallException;
import io.github.samuel426.lodginghub.supplier.model.SupplierFailureCategory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.reactive.function.client.WebClient;

class SupplierHttpBoundaryTest {
  @ParameterizedTest
  @CsvSource({"401,AUTHENTICATION_ERROR", "429,RATE_LIMITED", "503,UPSTREAM_ERROR"})
  void statusSurvivesTruncatedBody(int status, SupplierFailureCategory expected) throws Exception {
    verifyFailure(status, false, expected);
  }

  @Test
  void successfulStatusDoesNotHideInterruptedConnection() throws Exception {
    verifyFailure(200, false, SupplierFailureCategory.CONNECTION_ERROR);
  }

  @Test
  void responseBodyReadTimeoutIsClassifiedAsTimeout() throws Exception {
    verifyFailure(200, true, SupplierFailureCategory.TIMEOUT);
  }

  @Test
  void errorStatusSurvivesBodyReadTimeout() throws Exception {
    verifyFailure(401, true, SupplierFailureCategory.AUTHENTICATION_ERROR);
  }

  private void verifyFailure(int status, boolean delayed, SupplierFailureCategory expected)
      throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      server.setExecutor(executor);
      server.createContext(
          "/a/v1/hotels",
          exchange -> {
            try (exchange) {
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(status, 1000);
              exchange.getResponseBody().write("{\"items\":".getBytes(StandardCharsets.UTF_8));
              exchange.getResponseBody().flush();
              if (delayed) {
                try {
                  Thread.sleep(600);
                } catch (InterruptedException error) {
                  Thread.currentThread().interrupt();
                }
              }
            }
          });
      server.start();
      try {
        var endpoint =
            new SupplierClientProperties.Endpoint(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "mock-boundary");
        var properties =
            new SupplierClientProperties(
                endpoint,
                endpoint,
                Duration.ofMillis(500),
                Duration.ofMillis(delayed ? 150 : 2000),
                Duration.ofSeconds(2),
                4096);
        var client =
            new SupplierACatalogClient(
                new SupplierWebClientConfiguration()
                    .supplierAWebClient(WebClient.builder(), properties),
                properties);
        assertThatThrownBy(() -> client.fetchCatalog().block())
            .isInstanceOfSatisfying(
                SupplierCallException.class,
                error -> assertThat(error.category()).isEqualTo(expected));
      } finally {
        server.stop(0);
      }
    }
  }
}
