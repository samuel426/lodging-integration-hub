package io.github.samuel426.lodginghub.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.samuel426.lodginghub.supplier.client.SupplierCatalogClient;
import io.github.samuel426.lodginghub.supplier.model.Supplier;
import io.github.samuel426.lodginghub.supplier.model.SupplierCallException;
import io.github.samuel426.lodginghub.supplier.model.SupplierCatalog;
import io.github.samuel426.lodginghub.supplier.model.SupplierFailureCategory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import reactor.core.publisher.Mono;

class CatalogSyncServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-04T00:00:00Z");
  private final CatalogPersistenceService persistence = mock(CatalogPersistenceService.class);
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

  @Test
  void knownFailureIsIsolatedEvenWhenItsStateCannotBeSaved() {
    var first =
        client(
            Supplier.SUPPLIER_A,
            Mono.error(new SupplierCallException(SupplierFailureCategory.TIMEOUT)));
    var catalog = new SupplierCatalog(List.of());
    var second = client(Supplier.SUPPLIER_B, Mono.just(catalog));
    doThrow(new DataAccessResourceFailureException("test failure"))
        .when(persistence)
        .recordFailure(Supplier.SUPPLIER_A, NOW, "TIMEOUT");

    service(List.of(first, second)).synchronizeAll();

    verify(persistence).apply(Supplier.SUPPLIER_B, catalog, NOW);
    assertThat(metrics.get("supplier.catalog.state.failures").counter().count()).isEqualTo(1);
    assertThat(metrics.get("supplier.catalog.sync").tag("outcome", "TIMEOUT").counter().count())
        .isEqualTo(1);
  }

  @Test
  void programmingDefectIsNotReportedAsSuccessfulSyncOrSupplierFailure() {
    var first = client(Supplier.SUPPLIER_A, Mono.error(new IllegalStateException("mapper defect")));
    assertThatThrownBy(() -> service(List.of(first)).synchronizeAll())
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(persistence);
    assertThat(
            metrics.get("supplier.catalog.sync").tag("outcome", "INTERNAL_ERROR").counter().count())
        .isEqualTo(1);
    assertThat(metrics.find("supplier.catalog.sync").tag("outcome", "SUCCESS").counter()).isNull();
  }

  @Test
  void emptyPublisherIsInvalidResponseNotEmptyCatalog() {
    service(List.of(client(Supplier.SUPPLIER_A, Mono.empty()))).synchronizeAll();
    verify(persistence).recordFailure(Supplier.SUPPLIER_A, NOW, "INVALID_RESPONSE");
    verify(persistence, never()).apply(any(), any(), any());
  }

  @Test
  void rejectsDuplicateOrAbsentClients() {
    var client = client(Supplier.SUPPLIER_A, Mono.empty());
    assertThatThrownBy(() -> service(List.of(client, client)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service(List.of())).isInstanceOf(IllegalArgumentException.class);
  }

  private CatalogSyncService service(List<SupplierCatalogClient> clients) {
    return new CatalogSyncService(clients, persistence, Clock.fixed(NOW, ZoneOffset.UTC), metrics);
  }

  private SupplierCatalogClient client(Supplier supplier, Mono<SupplierCatalog> result) {
    var client = mock(SupplierCatalogClient.class);
    when(client.supplier()).thenReturn(supplier);
    when(client.fetchCatalog()).thenReturn(result);
    return client;
  }
}
