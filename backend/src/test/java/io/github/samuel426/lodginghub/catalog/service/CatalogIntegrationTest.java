package io.github.samuel426.lodginghub.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.samuel426.lodginghub.catalog.dto.CatalogSnapshot;
import io.github.samuel426.lodginghub.catalog.dto.CatalogSnapshot.SupplierCatalogView;
import io.github.samuel426.lodginghub.supplier.a.client.SupplierACatalogClient;
import io.github.samuel426.lodginghub.supplier.b.client.SupplierBCatalogClient;
import io.github.samuel426.lodginghub.supplier.client.SupplierHttpSupport;
import io.github.samuel426.lodginghub.supplier.model.Supplier;
import io.github.samuel426.lodginghub.supplier.model.SupplierCallException;
import io.github.samuel426.lodginghub.supplier.model.SupplierCatalog;
import io.github.samuel426.lodginghub.supplier.model.SupplierFailureCategory;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(
    properties = {"catalog.sync-on-startup=false", "suppliers.max-in-memory-bytes=4096"})
class CatalogIntegrationTest {
  private static final String UPDATED_MEADOW = "Updated Meadow";
  private static final String CANAL_HOUSE = "Canal House";
  private static final String GARDEN_TWIN = "Garden Twin";
  private static final String HEADERS = "headers";
  private static final String CONTENT_TYPE = "Content-Type";
  private static final String APPLICATION_JSON = "application/json";
  private static final Instant NOW = Instant.parse("2026-09-04T01:00:00Z");
  private static final String A_PATH = "/a/v1/hotels";
  private static final String B_PATH = "/b/api/properties";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));

  @Container
  static final GenericContainer<?> WIREMOCK =
      new GenericContainer<>(DockerImageName.parse("wiremock/wiremock:3.13.2"))
          .withExposedPorts(8080)
          .waitingFor(Wait.forHttp("/__admin/mappings"));

  @DynamicPropertySource
  static void endpoints(DynamicPropertyRegistry registry) {
    registry.add("suppliers.a.base-url", CatalogIntegrationTest::baseUrl);
    registry.add("suppliers.b.base-url", CatalogIntegrationTest::baseUrl);
  }

  @Autowired CatalogSyncService sync;
  @Autowired CatalogQueryService query;
  @Autowired CatalogPersistenceService persistence;
  @Autowired SupplierACatalogClient supplierA;
  @Autowired SupplierBCatalogClient supplierB;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @MockitoBean Clock clock;

  @BeforeEach
  void reset() throws Exception {
    when(clock.instant()).thenReturn(NOW);
    // These tables exist only in this class's disposable Testcontainers database.
    jdbc.execute(
        "TRUNCATE supplier_room_type_mapping, supplier_stay_mapping, room_type, stay, supplier_catalog_sync_state");
    admin("POST", "/__admin/reset", "{}");
  }

  @Test
  void createsDistinctStableIdsAcrossSuppliersAndUpdatesDetails() throws Exception {
    normalCatalogs();
    sync.synchronizeAll();
    var initial = query.snapshot();
    var a = view(initial, Supplier.SUPPLIER_A).stays().getFirst();
    var b = view(initial, Supplier.SUPPLIER_B).stays().getFirst();
    assertThat(initial.isReady()).isTrue();
    assertThat(initial.unavailableSuppliers()).isEmpty();
    assertThat(a.stayId()).isNotEqualTo(b.stayId());
    assertThat(a.rooms().getFirst().roomTypeId()).isNotEqualTo(b.rooms().getFirst().roomTypeId());

    clearMappings();
    stub(A_PATH, 200, aCatalog("Renamed Hotel", "Renamed Room", 4));
    stub(B_PATH, 200, bCatalog());
    when(clock.instant()).thenReturn(NOW.plusSeconds(60));
    sync.synchronizeAll();
    var updated = view(query.snapshot(), Supplier.SUPPLIER_A);
    assertThat(updated.stays().getFirst().stayId()).isEqualTo(a.stayId());
    assertThat(updated.stays().getFirst().rooms().getFirst().roomTypeId())
        .isEqualTo(a.rooms().getFirst().roomTypeId());
    assertThat(updated.stays().getFirst().name()).isEqualTo("Renamed Hotel");
    assertThat(updated.stays().getFirst().rooms().getFirst().maxOccupancy()).isEqualTo(4);
    assertThat(updated.lastSucceededAt()).isEqualTo(NOW.plusSeconds(60));
    assertThat(jdbc.queryForObject("select count(*) from stay", Integer.class)).isEqualTo(2);
    assertThatThrownBy(() -> updated.stays().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void missingStayDeactivatesParentAndRoomsAndReappearanceRestoresIds() throws Exception {
    normalCatalogs();
    sync.synchronizeAll();
    var original = view(query.snapshot(), Supplier.SUPPLIER_A).stays().getFirst();
    clearMappings();
    stub(A_PATH, 200, "{\"items\":[]}");
    stub(B_PATH, 200, bCatalog());
    sync.synchronizeAll();
    assertThat(view(query.snapshot(), Supplier.SUPPLIER_A).stays()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select is_active from supplier_stay_mapping where supplier='SUPPLIER_A'",
                Boolean.class))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                """
        select m.is_active from supplier_room_type_mapping m
        join supplier_stay_mapping sm on sm.id=m.supplier_stay_mapping_id
        where sm.supplier='SUPPLIER_A'
        """,
                Boolean.class))
        .isFalse();
    clearMappings();
    normalCatalogs();
    sync.synchronizeAll();
    assertThat(view(query.snapshot(), Supplier.SUPPLIER_A).stays().getFirst()).isEqualTo(original);
  }

  @Test
  void missingRoomDoesNotDeactivateItsStayAndRoomReactivates() throws Exception {
    normalCatalogs();
    sync.synchronizeAll();
    var original = view(query.snapshot(), Supplier.SUPPLIER_A).stays().getFirst();
    clearMappings();
    stub(
        A_PATH,
        200,
        """
        {"items":[{"hotelCode":"shared-hotel","hotelName":"%s","roomTypes":[]}]}
        """
            .formatted(CANAL_HOUSE));
    stub(B_PATH, 200, bCatalog());
    sync.synchronizeAll();
    var emptyRooms = view(query.snapshot(), Supplier.SUPPLIER_A).stays().getFirst();
    assertThat(emptyRooms.stayId()).isEqualTo(original.stayId());
    assertThat(emptyRooms.rooms()).isEmpty();
    clearMappings();
    normalCatalogs();
    sync.synchronizeAll();
    assertThat(view(query.snapshot(), Supplier.SUPPLIER_A).stays().getFirst()).isEqualTo(original);
  }

  @Test
  void failurePreservesOldSnapshotAndOtherSupplierStillCommits() throws Exception {
    normalCatalogs();
    sync.synchronizeAll();
    var original = view(query.snapshot(), Supplier.SUPPLIER_A);
    clearMappings();
    stub(A_PATH, 503, "{\"message\":\"temporary failure\"}");
    stub(B_PATH, 200, bCatalog().replace("Meadow House", UPDATED_MEADOW));
    when(clock.instant()).thenReturn(NOW.plusSeconds(60));
    sync.synchronizeAll();
    var snapshot = query.snapshot();
    var failed = view(snapshot, Supplier.SUPPLIER_A);
    assertThat(failed.stays()).isEqualTo(original.stays());
    assertThat(failed.lastSucceededAt()).isEqualTo(NOW);
    assertThat(failed.lastAttemptedAt()).isEqualTo(NOW.plusSeconds(60));
    assertThat(failed.lastFailureCategory()).isEqualTo("UPSTREAM_ERROR");
    assertThat(snapshot.unavailableSuppliers()).isEmpty();
    assertThat(view(snapshot, Supplier.SUPPLIER_B).stays().getFirst().name())
        .isEqualTo(UPDATED_MEADOW);

    clearMappings();
    normalCatalogs();
    sync.synchronizeAll();
    assertThat(view(query.snapshot(), Supplier.SUPPLIER_A).lastFailureCategory()).isNull();
  }

  @Test
  void distinguishesNeverReadyFromValidatedEmptyCatalog() throws Exception {
    stub(A_PATH, 503, "{}");
    stub(B_PATH, 200, "{\"resultCode\":\"E503\",\"data\":null}");
    sync.synchronizeAll();
    assertThat(query.snapshot().isReady()).isFalse();
    assertThat(query.snapshot().unavailableSuppliers())
        .containsExactly(Supplier.SUPPLIER_A, Supplier.SUPPLIER_B);
    clearMappings();
    stub(A_PATH, 200, "{\"items\":[]}");
    stub(B_PATH, 200, "{\"resultCode\":\"E503\",\"data\":null}");
    sync.synchronizeAll();
    var snapshot = query.snapshot();
    assertThat(snapshot.isReady()).isTrue();
    assertThat(snapshot.unavailableSuppliers()).containsExactly(Supplier.SUPPLIER_B);
    assertThat(view(snapshot, Supplier.SUPPLIER_A).stays()).isEmpty();
    assertThat(view(snapshot, Supplier.SUPPLIER_A).lastSucceededAt()).isEqualTo(NOW);
  }

  @Test
  void rejectsEntireSnapshotOnDuplicateKeyWithoutDeactivatingOldRows() throws Exception {
    normalCatalogs();
    sync.synchronizeAll();
    var original = view(query.snapshot(), Supplier.SUPPLIER_A);
    clearMappings();
    String duplicate =
        """
        {"items":[
          {"hotelCode":"new-hotel","hotelName":"New Hotel","roomTypes":[]},
          {"hotelCode":"new-hotel","hotelName":"Duplicate","roomTypes":[]}
        ]}
        """;
    stub(A_PATH, 200, duplicate);
    stub(B_PATH, 200, bCatalog());
    sync.synchronizeAll();
    var failed = view(query.snapshot(), Supplier.SUPPLIER_A);
    assertThat(failed.stays()).isEqualTo(original.stays());
    assertThat(failed.lastSucceededAt()).isEqualTo(original.lastSucceededAt());
    assertThat(failed.lastFailureCategory()).isEqualTo("INVALID_RESPONSE");
    assertThat(jdbc.queryForObject("select count(*) from stay", Integer.class)).isEqualTo(2);
  }

  @Test
  void sameRoomCodeInDifferentStaysIsAllowedAndDatabaseRejectsDuplicateScopedKeys() {
    var room = new SupplierCatalog.CatalogRoom("room", "Twin", 2);
    var catalog =
        new SupplierCatalog(
            List.of(
                new SupplierCatalog.CatalogStay("first", "First", List.of(room)),
                new SupplierCatalog.CatalogStay("second", "Second", List.of(room))));
    persistence.apply(Supplier.SUPPLIER_A, catalog, NOW);
    var stays = view(query.snapshot(), Supplier.SUPPLIER_A).stays();
    assertThat(stays).hasSize(2);
    assertThat(stays.getFirst().rooms().getFirst().roomTypeId())
        .isNotEqualTo(stays.getLast().rooms().getFirst().roomTypeId());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
        insert into supplier_room_type_mapping
        (supplier_stay_mapping_id, external_room_type_code, room_type_id, is_active, last_synced_at)
        select supplier_stay_mapping_id, external_room_type_code, room_type_id, true, last_synced_at
        from supplier_room_type_mapping limit 1
        """))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
        insert into supplier_stay_mapping
        (supplier, external_stay_code, stay_id, is_active, last_synced_at)
        select supplier, external_stay_code, stay_id, true, last_synced_at
        from supplier_stay_mapping limit 1
        """))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("update room_type set max_occupancy=0"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void databaseFailureRollsBackWholeSupplierSnapshotAndSuccessTimestamp() throws Exception {
    normalCatalogs();
    sync.synchronizeAll();
    var original = view(query.snapshot(), Supplier.SUPPLIER_A);
    jdbc.execute(
        "alter table stay add constraint test_reject_name check (name <> 'Forbidden Update')");
    try {
      clearMappings();
      stub(A_PATH, 200, aCatalog("Forbidden Update", "Changed Room", 5));
      stub(B_PATH, 200, bCatalog().replace("Meadow House", UPDATED_MEADOW));
      when(clock.instant()).thenReturn(NOW.plusSeconds(60));
      sync.synchronizeAll();
      var failed = view(query.snapshot(), Supplier.SUPPLIER_A);
      assertThat(failed.stays()).isEqualTo(original.stays());
      assertThat(failed.lastSucceededAt()).isEqualTo(NOW);
      assertThat(failed.lastFailureCategory()).isEqualTo("PERSISTENCE_ERROR");
      assertThat(view(query.snapshot(), Supplier.SUPPLIER_B).stays().getFirst().name())
          .isEqualTo(UPDATED_MEADOW);
    } finally {
      jdbc.execute("alter table stay drop constraint test_reject_name");
    }
  }

  @Test
  void refusesToCallSuppliersInsideAnExistingTransaction() throws Exception {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                assertThatThrownBy(sync::synchronizeAll)
                    .isInstanceOf(IllegalTransactionStateException.class));
    var requests = JSON.readTree(admin("GET", "/__admin/requests", null));
    assertThat(requests.get("requests").size()).isZero();
  }

  @ParameterizedTest
  @CsvSource({
    "400, INVALID_REQUEST",
    "401, AUTHENTICATION_ERROR",
    "403, AUTHENTICATION_ERROR",
    "429, RATE_LIMITED",
    "500, UPSTREAM_ERROR",
    "503, UPSTREAM_ERROR",
    "302, INVALID_RESPONSE"
  })
  void mapsHttpFailures(int status, SupplierFailureCategory expected) throws Exception {
    stub(A_PATH, status, "{\"message\":\"untrusted upstream content\"}");
    assertFailure(() -> supplierA.fetchCatalog().block(), expected);
  }

  @ParameterizedTest
  @CsvSource({
    "E400, INVALID_REQUEST", "E401, AUTHENTICATION_ERROR", "E429, RATE_LIMITED",
    "E500, UPSTREAM_ERROR", "E503, UPSTREAM_ERROR", "UNKNOWN, INVALID_RESPONSE"
  })
  void mapsBusinessFailuresInsideHttp200(String code, SupplierFailureCategory expected)
      throws Exception {
    stub(B_PATH, 200, "{\"resultCode\":\"" + code + "\",\"data\":null}");
    assertFailure(() -> supplierB.fetchCatalog().block(), expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"{}", "{\"items\":null}", "{\"items\":[null]}", "{", "null"})
  void invalidEnvelopeIsNotAnEmptySnapshot(String body) throws Exception {
    stub(A_PATH, 200, body);
    assertFailure(() -> supplierA.fetchCatalog().block(), SupplierFailureCategory.INVALID_RESPONSE);
  }

  @ParameterizedTest
  @ValueSource(strings = {"2.5", "\"2\"", "null", "0", "2147483648"})
  void doesNotCoerceOrDefaultInvalidOccupancy(String occupancy) throws Exception {
    stub(
        A_PATH,
        200,
        aCatalog(CANAL_HOUSE, GARDEN_TWIN, 2)
            .replace("\"maxOccupancy\":2", "\"maxOccupancy\":" + occupancy));
    assertFailure(() -> supplierA.fetchCatalog().block(), SupplierFailureCategory.INVALID_RESPONSE);
  }

  @Test
  void validatesBEnvelopeEvenWhenResultCodeIsSuccess() throws Exception {
    stub(B_PATH, 200, "{\"resultCode\":\"0000\",\"data\":null}");
    assertFailure(() -> supplierB.fetchCatalog().block(), SupplierFailureCategory.INVALID_RESPONSE);
  }

  @Test
  void numericExternalCodeIsNotSilentlyConvertedToString() throws Exception {
    stub(
        A_PATH,
        200,
        """
        {"items":[{"hotelCode":101,"hotelName":"Numeric code","roomTypes":[]}]}
        """);
    assertFailure(() -> supplierA.fetchCatalog().block(), SupplierFailureCategory.INVALID_RESPONSE);
  }

  @Test
  void connectionRefusalIsNotClassifiedAsNoResponseTimeout() throws Exception {
    int unusedPort;
    try (var socket = new ServerSocket(0)) {
      unusedPort = socket.getLocalPort();
    }
    var client = WebClient.create("http://127.0.0.1:" + unusedPort);
    assertFailure(
        () -> SupplierHttpSupport.get(client, A_PATH, String.class, Duration.ofSeconds(2)).block(),
        SupplierFailureCategory.CONNECTION_ERROR);
  }

  @Test
  void rejectsEmptyBodyAndOversizedBody() throws Exception {
    stub(A_PATH, 204, "");
    assertFailure(() -> supplierA.fetchCatalog().block(), SupplierFailureCategory.INVALID_RESPONSE);
    clearMappings();
    stub(A_PATH, 200, "{\"ignored\":\"" + "x".repeat(8192) + "\",\"items\":[]}");
    assertFailure(() -> supplierA.fetchCatalog().block(), SupplierFailureCategory.INVALID_RESPONSE);
  }

  @Test
  void timeoutDoesNotBlockOtherSupplierOrBecomeEmptySuccess() throws Exception {
    stub(
        A_PATH,
        Map.of(
            "status",
            200,
            HEADERS,
            Map.of(CONTENT_TYPE, APPLICATION_JSON),
            "body",
            aCatalog(CANAL_HOUSE, GARDEN_TWIN, 2),
            "fixedDelayMilliseconds",
            10_000));
    stub(B_PATH, 200, bCatalog());
    sync.synchronizeAll();
    var snapshot = query.snapshot();
    assertThat(snapshot.isReady()).isTrue();
    assertThat(snapshot.unavailableSuppliers()).containsExactly(Supplier.SUPPLIER_A);
    assertThat(view(snapshot, Supplier.SUPPLIER_A).lastFailureCategory()).isEqualTo("TIMEOUT");
    assertThat(view(snapshot, Supplier.SUPPLIER_B).stays()).hasSize(1);
  }

  @Test
  void wholeBodyDeadlineStopsAContinuouslyDrippingResponse() throws Exception {
    stub(
        A_PATH,
        Map.of(
            "status",
            200,
            HEADERS,
            Map.of(CONTENT_TYPE, APPLICATION_JSON),
            "body",
            aCatalog(CANAL_HOUSE, GARDEN_TWIN, 2),
            "chunkedDribbleDelay",
            Map.of("numberOfChunks", 20, "totalDuration", 6000)));
    assertFailure(() -> supplierA.fetchCatalog().block(), SupplierFailureCategory.TIMEOUT);
  }

  @Test
  void sendsAuthenticationAndCorrelationHeaders() throws Exception {
    normalCatalogs();
    assertThat(supplierA.fetchCatalog().block().stays()).hasSize(1);
    var requests = JSON.readTree(admin("GET", "/__admin/requests", null)).get("requests");
    assertThat(requests.size()).isEqualTo(1);
    var headers = requests.get(0).get("request").get(HEADERS);
    assertThat(headers.get("X-Api-Key").asString()).isEqualTo("local-mock-a");
    assertThat(headers.get("X-Correlation-Id").asString()).isNotBlank();
  }

  private void normalCatalogs() throws Exception {
    stub(A_PATH, 200, aCatalog(CANAL_HOUSE, GARDEN_TWIN, 2));
    stub(B_PATH, 200, bCatalog());
  }

  private static String aCatalog(String hotelName, String roomName, int occupancy) {
    return """
        {"items":[{"hotelCode":"shared-hotel","hotelName":"%s",
          "roomTypes":[{"roomTypeCode":"shared-room","roomTypeName":"%s","maxOccupancy":%d}]}]}
        """
        .formatted(hotelName, roomName, occupancy);
  }

  private static String bCatalog() {
    return """
        {"resultCode":"0000","resultMessage":"SUCCESS","data":{"items":[
          {"propertyId":"shared-hotel","propertyName":"Meadow House",
          "rooms":[{"roomId":"shared-room","roomName":"Courtyard Twin","maxOccupancy":3}]}]}}
        """;
  }

  private SupplierCatalogView view(CatalogSnapshot snapshot, Supplier supplier) {
    return snapshot.suppliers().stream()
        .filter(view -> view.supplier() == supplier)
        .findFirst()
        .orElseThrow();
  }

  private void assertFailure(ThrowingCallable call, SupplierFailureCategory category) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            SupplierCallException.class, error -> assertThat(error.category()).isEqualTo(category));
  }

  private void clearMappings() throws Exception {
    admin("DELETE", "/__admin/mappings", null);
  }

  private void stub(String path, int status, String body) throws Exception {
    stub(
        path,
        Map.of("status", status, HEADERS, Map.of(CONTENT_TYPE, APPLICATION_JSON), "body", body));
  }

  private void stub(String path, Map<String, Object> response) throws Exception {
    String key = path.equals(A_PATH) ? "local-mock-a" : "local-mock-b";
    admin(
        "POST",
        "/__admin/mappings",
        JSON.writeValueAsString(
            Map.of(
                "request",
                Map.of(
                    "method",
                    "GET",
                    "urlPath",
                    path,
                    HEADERS,
                    Map.of("X-Api-Key", Map.of("equalTo", key))),
                "response",
                response)));
  }

  private static String admin(String method, String path, String body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create(baseUrl() + path))
            .header(CONTENT_TYPE, APPLICATION_JSON)
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body))
            .build();
    try (var client = HttpClient.newHttpClient()) {
      var response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).isBetween(200, 299);
      return response.body();
    }
  }

  private static String baseUrl() {
    return "http://" + WIREMOCK.getHost() + ":" + WIREMOCK.getMappedPort(8080);
  }
}
