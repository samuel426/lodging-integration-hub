package io.github.samuel426.lodginghub.search.service;

import static org.assertj.core.api.Assertions.*;

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
    properties = {"suppliers.request-timeout=3s", "suppliers.response-timeout=3s"})
class SearchIntegrationTest {
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

  @BeforeEach
  void reset() throws Exception {
    admin("POST", "/__admin/scenarios/reset", "{}");
  }

  @Test
  void normalSearchUsesStableInternalIdsAndRealGrossPrices() throws Exception {
    var response = get(QUERY);
    assertThat(response.statusCode()).isEqualTo(200);
    var body = JSON.readTree(response.body());
    assertThat(body.at("/data/offers").size()).isEqualTo(2);
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
    assertThat(body.at("/data/offers").size()).isEqualTo(1);
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
