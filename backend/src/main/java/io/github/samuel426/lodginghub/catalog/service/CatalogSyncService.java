package io.github.samuel426.lodginghub.catalog.service;

import io.github.samuel426.lodginghub.supplier.client.SupplierCatalogClient;
import io.github.samuel426.lodginghub.supplier.model.Supplier;
import io.github.samuel426.lodginghub.supplier.model.SupplierCallException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogSyncService {
  private static final Logger log = LoggerFactory.getLogger(CatalogSyncService.class);
  private final List<SupplierCatalogClient> clients;
  private final CatalogPersistenceService persistence;
  private final Clock clock;
  private final MeterRegistry metrics;

  public CatalogSyncService(
      List<SupplierCatalogClient> clients,
      CatalogPersistenceService persistence,
      Clock clock,
      MeterRegistry metrics) {
    var suppliers = new HashSet<Supplier>();
    if (clients.isEmpty()
        || clients.stream()
            .anyMatch(
                client ->
                    client == null
                        || client.supplier() == null
                        || !suppliers.add(client.supplier()))) {
      throw new IllegalArgumentException("Catalog clients must have unique suppliers");
    }
    this.clients = List.copyOf(clients);
    this.persistence = persistence;
    this.clock = clock;
    this.metrics = metrics;
  }

  @Transactional(propagation = Propagation.NEVER)
  public void synchronizeAll() {
    // Startup-only single-instance workflow. Deliberately no periodic or concurrent sync API.
    for (SupplierCatalogClient client : clients) {
      synchronize(client);
    }
  }

  private void synchronize(SupplierCatalogClient client) {
    Instant attemptedAt = clock.instant();
    long started = System.nanoTime();
    String outcome = "INTERNAL_ERROR";
    try {
      var snapshot = client.fetchCatalog().block();
      if (snapshot == null) {
        throw SupplierCallException.invalidResponse();
      }
      persistence.apply(client.supplier(), snapshot, attemptedAt);
      outcome = "SUCCESS";
    } catch (SupplierCallException error) {
      outcome = error.category().name();
      recordFailure(client.supplier(), attemptedAt, outcome);
    } catch (DataAccessException error) {
      outcome = "PERSISTENCE_ERROR";
      recordFailure(client.supplier(), attemptedAt, outcome);
    } finally {
      long elapsed = System.nanoTime() - started;
      metrics
          .counter(
              "supplier.catalog.sync", "supplier", client.supplier().name(), "outcome", outcome)
          .increment();
      metrics
          .timer(
              "supplier.catalog.sync.duration",
              "supplier",
              client.supplier().name(),
              "outcome",
              outcome)
          .record(elapsed, TimeUnit.NANOSECONDS);
    }
    log.info(
        "supplier={} operation=catalog-sync outcome={} durationMs={}",
        client.supplier(),
        outcome,
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
  }

  private void recordFailure(Supplier supplier, Instant attemptedAt, String category) {
    try {
      persistence.recordFailure(supplier, attemptedAt, category);
    } catch (DataAccessException error) {
      // Do not log driver messages: they can include upstream values stored in columns.
      log.error("supplier={} operation=catalog-state outcome=PERSISTENCE_ERROR", supplier);
      metrics.counter("supplier.catalog.state.failures", "supplier", supplier.name()).increment();
    }
  }
}
