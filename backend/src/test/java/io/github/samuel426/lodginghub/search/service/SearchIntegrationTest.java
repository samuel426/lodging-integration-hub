package io.github.samuel426.lodginghub.search.service;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.samuel426.lodginghub.supplier.model.AvailabilityCondition;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "suppliers.request-timeout=3s",
      "suppliers.response-timeout=3s",
      "suppliers.circuit-breaker.sliding-window-size=4",
      "suppliers.circuit-breaker.minimum-number-of-calls=4",
      "suppliers.circuit-breaker.wait-duration-in-open-state=3s"
    })
class SearchIntegrationTest {
  private static final String OFFERS_PATH = "/data/offers";
  private static final String TRACE_HEADER = "X-Correlation-Id";
  private static final String QUERY =
      "/api/v1/stays/search?checkIn=2026-10-10&checkOut=2026-10-12&adults=2&children=0";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

  @Container
  static final GenericContainer<?> WIREMOCK =
      new GenericContainer<>(DockerImageName.parse("wiremock/wiremock:3.13.2"))
          .withExposedPorts(8080)
          .withCopyFileToContainer(
              MountableFile.forHostPath(Path.of("../mock/wiremock").toAbsolutePath()),
              "/home/wiremock")
          .waitingFor(Wait.forHttp("/__admin/mappings"));

  @DynamicPropertySource
  static void endpoints(DynamicPropertyRegistry registry) {
    registry.add("suppliers.a.base-url", SearchIntegrationTest::mockUrl);
    registry.add("suppliers.b.base-url", SearchIntegrationTest::mockUrl);
  }

  @LocalServerPort int port;
  @Autowired StaySearchService service;
  @Autowired PlatformTransactionManager transactions;
  @Autowired CircuitBreakerRegistry circuits;

  @BeforeEach
  void reset() throws Exception {
    circuits.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    admin("POST", "/__admin/scenarios/reset", "{}");
    admin("DELETE", "/__admin/requests", "{}");
  }

  @Test
  void persistentSupplierFailureStopsHttpCallsAndRecoversThroughRealProbes() throws Exception {
    state("b", "error");
    for (int i = 0; i < 4; i++) {
      var failed = get(QUERY);
      assertThat(failed.statusCode()).isEqualTo(200);
      assertThat(JSON.readTree(failed.body()).at("/meta/supplierFailures/0/category").asString())
          .isEqualTo("UPSTREAM_ERROR");
    }
    var circuit = circuits.circuitBreaker("SUPPLIER_B");
    assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    state("b", "Started");
    var blocked = get(QUERY);
    assertThat(blocked.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(blocked.body()).at(OFFERS_PATH).size()).isEqualTo(1);
    assertThat(JSON.readTree(blocked.body()).at("/meta/supplierFailures/0/category").asString())
        .isEqualTo("CIRCUIT_OPEN");
    assertThat(availabilityRequests("/b/api/search")).isEqualTo(4);
    await()
        .atMost(Duration.ofSeconds(6))
        .pollInterval(Duration.ofMillis(100))
        .untilAsserted(
            () -> {
              var recovered = get(QUERY);
              assertThat(JSON.readTree(recovered.body()).at(OFFERS_PATH).size()).isEqualTo(2);
            });
    assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    assertThat(get(QUERY).statusCode()).isEqualTo(200);
    assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(availabilityRequests("/b/api/search")).isEqualTo(6);
  }

  @Test
  void bothCircuitsBlockedReturn503AndNewCategoryIsInOpenApi() throws Exception {
    circuits.getAllCircuitBreakers().forEach(CircuitBreaker::transitionToOpenState);
    var response = get(QUERY);
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(JSON.readTree(response.body()).at("/error/code").asString())
        .isEqualTo("ALL_SUPPLIERS_UNAVAILABLE");
    assertThat(availabilityRequests("/a/v1/availability")).isZero();
    assertThat(availabilityRequests("/b/api/search")).isZero();
    assertThat(get("/v3/api-docs").body()).contains("CIRCUIT_OPEN");
  }

  private int availabilityRequests(String path) throws Exception {
    var response =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(mockUrl() + "/__admin/requests")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    int count = 0;
    for (var request : JSON.readTree(response.body()).path("requests")) {
      if (request.at("/request/url").asString().startsWith(path + "?")) {
        count++;
      }
    }
    return count;
  }

  @Test
  void concurrentRequestsKeepTheirCorrelationIdsAcrossSupplierCalls() throws Exception {
    var first =
        HTTP.sendAsync(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + QUERY))
                .header(TRACE_HEADER, "parallel-one")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    var second =
        HTTP.sendAsync(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + QUERY))
                .header(TRACE_HEADER, "parallel-two")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(first.get().headers().firstValue(TRACE_HEADER)).contains("parallel-one");
    assertThat(second.get().headers().firstValue(TRACE_HEADER)).contains("parallel-two");
    var journal =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(mockUrl() + "/__admin/requests")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    var requests = JSON.readTree(journal.body()).path("requests");
    int one = 0;
    int two = 0;
    for (var request : requests) {
      String trace = request.at("/request/headers/X-Correlation-Id").asString();
      if (trace.equals("parallel-one")) {
        one++;
      }
      if (trace.equals("parallel-two")) {
        two++;
      }
    }
    assertThat(one).isEqualTo(2);
    assertThat(two).isEqualTo(2);
  }

  @Test
  void normalSearchUsesStableInternalIdsAndRealGrossPrices() throws Exception {
    var response = get(QUERY);
    assertThat(response.statusCode()).isEqualTo(200);
    var body = JSON.readTree(response.body());
    assertThat(body.at(OFFERS_PATH).size()).isEqualTo(2);
    assertThat(body.at("/data/offers/0/price/totalAmount").asLong()).isEqualTo(220000);
    assertThat(body.at("/data/offers/1/price/totalAmount").asLong()).isEqualTo(236000);
    assertThat(body.at("/data/offers/1/price/taxAmount").isNull()).isTrue();
    assertThat(body.at("/data/offers/1/price/nightlyBreakdown").isNull()).isTrue();
    assertThat(body.at("/meta/partial").asBoolean()).isFalse();
    assertThat(response.body())
        .doesNotContain("canal-101", "meadow-201", "hotelCode", "propertyId", "externalCode");
    assertThat(JSON.readTree(get(QUERY).body()).at("/data/offers/0/stayId"))
        .isEqualTo(body.at("/data/offers/0/stayId"));
  }

  @Test
  void timeoutReturnsOtherSupplierWithinDeadline() throws Exception {
    state("b", "timeout");
    long start = System.nanoTime();
    var response = get(QUERY);
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(6));
    assertThat(response.statusCode()).isEqualTo(200);
    var body = JSON.readTree(response.body());
    assertThat(body.at(OFFERS_PATH).size()).isEqualTo(1);
    assertThat(body.at("/meta/partial").asBoolean()).isTrue();
    assertThat(body.at("/meta/supplierFailures/0/category").asString()).isEqualTo("TIMEOUT");
  }

  @Test
  void httpAndBodyFailureTogetherReturn503WithoutLeakingSupplierPayload() throws Exception {
    state("a", "error");
    state("b", "error");
    var response = get(QUERY);
    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(JSON.readTree(response.body()).at("/error/code").asString())
        .isEqualTo("ALL_SUPPLIERS_UNAVAILABLE");
    assertThat(response.body()).doesNotContain("E503", "local-mock", "http://");
  }

  @Test
  void openApiContainsSearchParametersAndAllStatusContracts() throws Exception {
    var response = get("/v3/api-docs");
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode operation = JSON.readTree(response.body()).at("/paths/~1api~1v1~1stays~1search/get");
    assertThat(operation.isMissingNode()).isFalse();
    assertThat(operation.path("parameters").size()).isEqualTo(4);
    assertThat(operation.at("/parameters/2/schema/type").asString()).isEqualTo("integer");
    assertThat(operation.at("/parameters/2/schema/minimum").asInt()).isEqualTo(1);
    assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asString())
        .endsWith("/SearchResponse");
    var price = JSON.readTree(response.body()).at("/components/schemas/PriceSummary/properties");
    assertThat(price.at("/taxAmount/type").toString()).contains("integer", "null");
    assertThat(price.at("/nightlyBreakdown/type").toString()).contains("array", "null");
    for (String code : new String[] {"200", "400", "500", "502", "503"}) {
      assertThat(operation.path("responses").has(code)).isTrue();
    }
  }

  @Test
  void transactionCannotEncloseSupplierCalls() {
    var condition =
        new AvailabilityCondition(
            LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-12"), 2, 0);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactions).execute(status -> service.search(condition)))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void state(String supplier, String state) throws Exception {
    admin(
        "PUT",
        "/__admin/scenarios/availability-" + supplier + "/state",
        "{\"state\":\"" + state + "\"}");
  }

  private void admin(String method, String path, String body) throws Exception {
    var response =
        HTTP.send(
            HttpRequest.newBuilder(URI.create(mockUrl() + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
  }

  private static String mockUrl() {
    return "http://" + WIREMOCK.getHost() + ":" + WIREMOCK.getMappedPort(8080);
  }
}
