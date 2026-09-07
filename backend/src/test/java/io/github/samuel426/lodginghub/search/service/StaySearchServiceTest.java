package io.github.samuel426.lodginghub.search.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.samuel426.lodginghub.catalog.dto.CatalogSnapshot;
import io.github.samuel426.lodginghub.catalog.dto.CatalogSnapshot.*;
import io.github.samuel426.lodginghub.catalog.service.CatalogQueryService;
import io.github.samuel426.lodginghub.supplier.client.SupplierAvailabilityClient;
import io.github.samuel426.lodginghub.supplier.model.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;

class StaySearchServiceTest {
  private static final String ROOM_CODE = "twin";
  private static final int INTERNAL_BUG_SCENARIO = 13;
  private static final int EMPTY_CATALOG_SCENARIO = 12;
  private static final AvailabilityCondition CONDITION =
      new AvailabilityCondition(LocalDate.parse("2026-10-10"), LocalDate.parse("2026-10-12"), 2, 0);
  private static final UUID STAY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID ROOM_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final Supplier A = Supplier.SUPPLIER_A;
  private static final Supplier B = Supplier.SUPPLIER_B;

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16})
  void approvedScenariosS01ThroughS16(int scenario) {
    var query = mock(CatalogQueryService.class);
    var a = success(A, 1, 0, 1, ROOM_CODE);
    var b = success(B, 1, 0, 1, ROOM_CODE);
    String expectedError = null;
    int expectedOffers = 2;
    int expectedRejected = 0;
    boolean expectedPartial = false;
    var snapshot = new CatalogSnapshot(List.of(catalog(A, true, 1), catalog(B, true, 1)));
    switch (scenario) {
      case 2 -> {
        a = empty();
        b = success(B, 1, 0, 0, ROOM_CODE);
        expectedOffers = 0;
      }
      case 3 -> {
        a = success(A, 8, 2, 1, ROOM_CODE);
        b = empty();
        expectedOffers = 8;
        expectedRejected = 2;
        expectedPartial = true;
      }
      case 4 -> {
        b = failed(SupplierFailureCategory.TIMEOUT);
        expectedOffers = 1;
        expectedPartial = true;
      }
      case 5 -> {
        a = empty();
        b = rejected();
        expectedOffers = 0;
        expectedRejected = 1;
        expectedPartial = true;
      }
      case 6 -> {
        a = success(A, 1, 1, 0, ROOM_CODE);
        b = failed(SupplierFailureCategory.TIMEOUT);
        expectedOffers = 0;
        expectedRejected = 1;
        expectedPartial = true;
      }
      case 7 -> {
        a = rejected();
        b = rejected();
        expectedError = "NO_VALID_SUPPLIER_DATA";
      }
      case 8 -> {
        a = rejected();
        b = failed(SupplierFailureCategory.TIMEOUT);
        expectedError = "NO_VALID_SUPPLIER_DATA";
      }
      case 9 -> {
        a = failed(SupplierFailureCategory.CONNECTION_ERROR);
        b = failed(SupplierFailureCategory.TIMEOUT);
        expectedError = "ALL_SUPPLIERS_UNAVAILABLE";
      }
      case 10 -> {
        a = success(A, 1, 0, 1, "unknown");
        b = success(B, 1, 0, 1, "unknown");
        expectedError = "CATALOG_MAPPING_UNAVAILABLE";
      }
      case 11 -> {
        snapshot = new CatalogSnapshot(List.of(catalog(A, false, 0), catalog(B, false, 0)));
        expectedError = "CATALOG_NOT_READY";
      }
      case 12 -> {
        snapshot = new CatalogSnapshot(List.of(catalog(A, true, 0), catalog(B, false, 0)));
        expectedOffers = 0;
        expectedPartial = true;
      }
      case 13 -> {
        expectedError = "INTERNAL_ERROR";
      }
      case 14 -> {
        a = failed(SupplierFailureCategory.AUTHENTICATION_ERROR);
        b = rejected();
        expectedError = "INTEGRATION_CONFIGURATION_ERROR";
      }
      case 15 -> {
        b = failed(SupplierFailureCategory.RATE_LIMITED);
        expectedOffers = 1;
        expectedPartial = true;
      }
      case 16 -> {
        a = failed(SupplierFailureCategory.RATE_LIMITED);
        b = a;
        expectedError = "ALL_SUPPLIERS_UNAVAILABLE";
      }
      default -> {}
    }
    when(query.snapshot()).thenReturn(snapshot);
    var aOutcome = a;
    var bOutcome = b;
    var service =
        service(
            query,
            List.of(
                client(
                    A,
                    request ->
                        scenario == INTERNAL_BUG_SCENARIO
                            ? Mono.error(new IllegalStateException("internal bug"))
                            : Mono.just(aOutcome)),
                client(B, request -> Mono.just(bOutcome))));
    if (scenario == INTERNAL_BUG_SCENARIO) {
      assertThatThrownBy(() -> service.search(CONDITION)).isInstanceOf(IllegalStateException.class);
    } else if (expectedError != null) {
      String code = expectedError;
      assertThatThrownBy(() -> service.search(CONDITION))
          .isInstanceOfSatisfying(
              SearchException.class, error -> assertThat(error.failure().name()).isEqualTo(code));
    } else {
      var response = service.search(CONDITION);
      assertThat(response.data().offers()).hasSize(expectedOffers);
      assertThat(response.meta().partial()).isEqualTo(expectedPartial);
      assertThat(response.meta().rejectedOfferCount()).isEqualTo(expectedRejected);
      assertThat(response.data().offers())
          .allSatisfy(
              offer -> {
                assertThat(offer.stayId()).isEqualTo(STAY_ID);
                assertThat(offer.roomTypeId()).isEqualTo(ROOM_ID);
              });
      if (scenario == EMPTY_CATALOG_SCENARIO) {
        assertThat(response.meta().unavailableCatalogSuppliers()).containsExactly(B);
      }
    }
  }

  @Test
  void concurrencyIsBoundedAcrossAllSuppliersAndSuccessfulBatchesSurvive() {
    var query = mock(CatalogQueryService.class);
    when(query.snapshot())
        .thenReturn(new CatalogSnapshot(List.of(catalog(A, true, 151), catalog(B, true, 151))));
    var active = new AtomicInteger();
    var maximum = new AtomicInteger();
    var calls = new AtomicInteger();
    Function<SupplierBatchRequest, Mono<SupplierBatchOutcome>> fetch =
        request ->
            Mono.defer(
                () -> {
                  int inFlight = active.incrementAndGet();
                  maximum.accumulateAndGet(inFlight, Math::max);
                  calls.incrementAndGet();
                  assertThat(request.stayCodes()).hasSizeLessThanOrEqualTo(50);
                  return Mono.delay(Duration.ofMillis(40))
                      .map(
                          ignored -> {
                            active.decrementAndGet();
                            return request.stayCodes().size() == 1
                                ? failed(SupplierFailureCategory.TIMEOUT)
                                : empty();
                          });
                });
    var response = service(query, List.of(client(A, fetch), client(B, fetch))).search(CONDITION);
    assertThat(maximum.get()).isEqualTo(4);
    assertThat(calls.get()).isEqualTo(8);
    assertThat(active.get()).isZero();
    assertThat(response.meta().supplierFailures())
        .hasSize(2)
        .allSatisfy(f -> assertThat(f.failedBatchCount()).isEqualTo(1));
    assertThat(response.meta().partial()).isTrue();
  }

  @Test
  void failureSummaryDoesNotDependOnCompletionOrder() {
    var query = mock(CatalogQueryService.class);
    when(query.snapshot())
        .thenReturn(new CatalogSnapshot(List.of(catalog(A, true, 101), catalog(B, true, 101))));
    var first = service(query, List.of(delayed(A, false), delayed(B, true))).search(CONDITION);
    var second = service(query, List.of(delayed(A, true), delayed(B, false))).search(CONDITION);
    assertThat(first).isEqualTo(second);
    assertThat(first.meta().supplierFailures())
        .hasSize(2)
        .allSatisfy(f -> assertThat(f.failedBatchCount()).isEqualTo(2));
  }

  @Test
  void occupancyFilteringPreservesValidObservation() {
    var query = mock(CatalogQueryService.class);
    when(query.snapshot()).thenReturn(new CatalogSnapshot(List.of(catalog(A, true, 1))));
    var service =
        service(query, List.of(client(A, request -> Mono.just(success(A, 1, 0, 1, ROOM_CODE)))));
    var result =
        service.search(
            new AvailabilityCondition(
                CONDITION.checkIn(), CONDITION.checkOut(), Integer.MAX_VALUE, Integer.MAX_VALUE));
    assertThat(result.data().offers()).isEmpty();
    assertThat(result.meta().partial()).isFalse();
  }

  @Test
  void missingClientIsConfigurationFailureAndEmptyPublisherIsInternalDefect() {
    var query = mock(CatalogQueryService.class);
    when(query.snapshot()).thenReturn(new CatalogSnapshot(List.of(catalog(A, true, 1))));
    assertThatThrownBy(() -> service(query, List.of()).search(CONDITION))
        .isInstanceOfSatisfying(
            SearchException.class,
            e -> assertThat(e.failure()).isEqualTo(SearchFailure.INTEGRATION_CONFIGURATION_ERROR));
    assertThatThrownBy(
            () -> service(query, List.of(client(A, request -> Mono.empty()))).search(CONDITION))
        .isInstanceOf(IllegalStateException.class);
  }

  private static StaySearchService service(
      CatalogQueryService query, List<SupplierAvailabilityClient> clients) {
    return new StaySearchService(
        query,
        clients,
        new SearchObservation(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
        new io.github.samuel426.lodginghub.supplier.service.SupplierAvailabilityGuard(
            io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry.ofDefaults()));
  }

  private SupplierAvailabilityClient delayed(Supplier supplier, boolean slow) {
    return client(
        supplier,
        request ->
            Mono.delay(Duration.ofMillis(slow ? 30 : 5))
                .map(
                    ignored ->
                        request.stayCodes().size() == 1
                            ? empty()
                            : failed(SupplierFailureCategory.TIMEOUT)));
  }

  private static SupplierCatalogView catalog(Supplier supplier, boolean ready, int count) {
    var stays =
        IntStream.range(0, count)
            .mapToObj(
                i ->
                    new StayView(
                        STAY_ID,
                        "stay-" + i,
                        "Catalog Stay",
                        List.of(new RoomView(ROOM_ID, ROOM_CODE, "Catalog Room", 2))))
            .toList();
    return new SupplierCatalogView(
        supplier, Instant.EPOCH, ready ? Instant.EPOCH : null, null, stays);
  }

  private static SupplierBatchOutcome success(
      Supplier supplier, int count, int rejected, int stock, String room) {
    return new SupplierBatchOutcome(
        IntStream.range(0, count)
            .mapToObj(
                i ->
                    new SupplierOffer(
                        supplier,
                        "stay-0",
                        room,
                        "Live Stay",
                        "Live Room",
                        2,
                        false,
                        stock,
                        new PriceSummary(220000, "KRW", true, null, null)))
            .toList(),
        rejected,
        false,
        null);
  }

  private static SupplierBatchOutcome empty() {
    return new SupplierBatchOutcome(List.of(), 0, true, null);
  }

  private static SupplierBatchOutcome rejected() {
    return new SupplierBatchOutcome(List.of(), 1, false, null);
  }

  private static SupplierBatchOutcome failed(SupplierFailureCategory category) {
    return SupplierBatchOutcome.failed(category);
  }

  private static SupplierAvailabilityClient client(
      Supplier supplier, Function<SupplierBatchRequest, Mono<SupplierBatchOutcome>> fetch) {
    return new SupplierAvailabilityClient() {
      @Override
      public Supplier supplier() {
        return supplier;
      }

      @Override
      public Mono<SupplierBatchOutcome> fetchAvailability(SupplierBatchRequest request) {
        return fetch.apply(request);
      }
    };
  }
}
