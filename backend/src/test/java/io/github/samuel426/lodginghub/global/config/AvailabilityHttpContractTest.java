package io.github.samuel426.lodginghub.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.samuel426.lodginghub.supplier.a.client.SupplierAAvailabilityClient;
import io.github.samuel426.lodginghub.supplier.b.client.SupplierBAvailabilityClient;
import io.github.samuel426.lodginghub.supplier.model.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.reactive.function.client.WebClient;

class AvailabilityHttpContractTest {
  private static final String NO_FAILURE = "NONE";
  private static final SupplierBatchRequest REQUEST =
      new SupplierBatchRequest(
          List.of("stay&1", "stay-2"),
          new AvailabilityCondition(
              LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-12"), 2, 1));

  @ParameterizedTest
  @CsvSource({
    "true,200,EMPTY,NONE",
    "false,200,EMPTY,NONE",
    "true,401,EMPTY,AUTHENTICATION_ERROR",
    "true,429,EMPTY,RATE_LIMITED",
    "false,200,E401,AUTHENTICATION_ERROR",
    "false,200,E503,UPSTREAM_ERROR",
    "true,200,NULL,INVALID_RESPONSE",
    "true,200,DUPLICATE,INVALID_RESPONSE",
    "false,200,NULL,INVALID_RESPONSE"
  })
  void verifiesWireContractAndFailureClassification(
      boolean isA, int status, String bodyType, String category) throws Exception {
    var calls = new AtomicInteger();
    var query = new AtomicReference<String>();
    var apiKey = new AtomicReference<String>();
    var correlation = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        isA ? "/a/v1/availability" : "/b/api/search",
        exchange -> {
          calls.incrementAndGet();
          query.set(exchange.getRequestURI().getRawQuery());
          apiKey.set(exchange.getRequestHeaders().getFirst("X-Api-Key"));
          correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
          String body =
              switch (bodyType) {
                case "EMPTY" ->
                    isA ? "{\"items\":[]}" : "{\"resultCode\":\"0000\",\"data\":{\"items\":[]}}";
                case "NULL" -> isA ? "{\"items\":null}" : "{\"resultCode\":\"0000\",\"data\":null}";
                case "DUPLICATE" -> "{\"items\":null,\"items\":[]}";
                default -> "{\"resultCode\":\"" + bodyType + "\",\"data\":null}";
              };
          try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
        });
    server.start();
    try {
      var endpoint =
          new SupplierClientProperties.Endpoint(
              URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "mock-contract");
      var properties =
          new SupplierClientProperties(
              endpoint,
              endpoint,
              Duration.ofSeconds(1),
              Duration.ofSeconds(5),
              Duration.ofSeconds(5),
              4096);
      var configuration = new SupplierWebClientConfiguration();
      var result =
          isA
              ? new SupplierAAvailabilityClient(
                      configuration.supplierAWebClient(WebClient.builder(), properties), properties)
                  .fetchAvailability(REQUEST)
                  .block()
              : new SupplierBAvailabilityClient(
                      configuration.supplierBWebClient(WebClient.builder(), properties), properties)
                  .fetchAvailability(REQUEST)
                  .block();
      assertThat(result).isNotNull();
      if (NO_FAILURE.equals(category)) {
        assertThat(result.isValidatedEmptyBatch()).isTrue();
        assertThat(result.failure()).isNull();
      } else {
        assertThat(result.failure()).isEqualTo(SupplierFailureCategory.valueOf(category));
        assertThat(result.isValidatedEmptyBatch()).isFalse();
      }
      assertThat(calls.get()).isEqualTo(1);
      assertThat(apiKey.get()).isEqualTo("mock-contract");
      assertThat(correlation.get()).isNotBlank();
      assertThat(query.get())
          .contains(
              (isA ? "hotelCodes" : "propertyIds") + "=stay%261%2Cstay-2",
              "checkIn=2026-10-10",
              "checkOut=2026-10-12",
              "adults=2",
              "children=1");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void csvSeparatorsAreRejectedBeforeNetworkAccess() {
    var endpoint =
        new SupplierClientProperties.Endpoint(URI.create("http://127.0.0.1:1"), "mock-contract");
    var properties =
        new SupplierClientProperties(
            endpoint,
            endpoint,
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
            4096);
    var client =
        new SupplierAAvailabilityClient(
            new SupplierWebClientConfiguration()
                .supplierAWebClient(WebClient.builder(), properties),
            properties);
    var result =
        client
            .fetchAvailability(
                new SupplierBatchRequest(List.of("ambiguous,code"), REQUEST.condition()))
            .block();
    assertThat(result.failure()).isEqualTo(SupplierFailureCategory.INVALID_REQUEST);
  }
}
