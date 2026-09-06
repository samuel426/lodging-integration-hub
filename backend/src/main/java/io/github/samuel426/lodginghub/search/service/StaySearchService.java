package io.github.samuel426.lodginghub.search.service;

import io.github.samuel426.lodginghub.catalog.dto.CatalogSnapshot.StayView;
import io.github.samuel426.lodginghub.catalog.service.CatalogQueryService;
import io.github.samuel426.lodginghub.global.config.CorrelationIdFilter;
import io.github.samuel426.lodginghub.search.dto.SearchResponse;
import io.github.samuel426.lodginghub.supplier.client.SupplierAvailabilityClient;
import io.github.samuel426.lodginghub.supplier.model.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class StaySearchService {
  private static final int CONCURRENCY = 4;
  private final CatalogQueryService catalog;
  private final Map<Supplier, SupplierAvailabilityClient> clients;
  private final SearchObservation observation;

  public StaySearchService(
      CatalogQueryService catalog,
      List<SupplierAvailabilityClient> clients,
      SearchObservation observation) {
    this.catalog = catalog;
    this.observation = observation;
    this.clients =
        clients.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    SupplierAvailabilityClient::supplier, Function.identity()));
  }

  @Transactional(propagation = Propagation.NEVER)
  public SearchResponse search(AvailabilityCondition condition) {
    long started = System.nanoTime();
    String outcome = "INTERNAL_ERROR";
    try {
      var response = execute(condition);
      outcome = response.meta().partial() ? "PARTIAL" : "SUCCESS";
      return response;
    } catch (SearchException error) {
      outcome = error.failure().name();
      throw error;
    } finally {
      observation.finished(outcome, started);
    }
  }

  private SearchResponse execute(AvailabilityCondition condition) {
    String inboundTrace = MDC.get(CorrelationIdFilter.TRACE_ID);
    String trace = inboundTrace == null ? UUID.randomUUID().toString() : inboundTrace;
    var snapshot = catalog.snapshot();
    if (!snapshot.isReady()) {
      throw new SearchException(SearchFailure.CATALOG_NOT_READY);
    }
    var aggregation = new SearchAggregation(snapshot, condition);
    var jobs =
        snapshot.suppliers().stream()
            .filter(view -> view.isReady())
            .flatMap(
                view ->
                    SupplierBatches.split(
                            view.stays().stream().map(StayView::externalCode).toList(), condition)
                        .stream()
                        .map(batch -> new BatchJob(view.supplier(), batch)))
            .toList();
    var results =
        Flux.fromIterable(jobs)
            .flatMap(
                job ->
                    observation
                        .batch(
                            job.supplier(),
                            job.request().stayCodes().size(),
                            Mono.defer(
                                    () -> {
                                      var client = clients.get(job.supplier());
                                      return client == null
                                          ? Mono.just(
                                              SupplierBatchOutcome.failed(
                                                  SupplierFailureCategory.INVALID_REQUEST))
                                          : client.fetchAvailability(job.request());
                                    })
                                .switchIfEmpty(
                                    Mono.error(
                                        new IllegalStateException("Adapter returned no outcome"))))
                        .map(outcome -> new BatchResult(job.supplier(), outcome)),
                CONCURRENCY)
            .collectList()
            .contextWrite(context -> context.put(CorrelationIdFilter.TRACE_ID, trace))
            .block();
    for (var result : results) {
      aggregation.accept(result.supplier(), result.outcome());
    }
    observation.counts(aggregation.counts());
    return aggregation.finish();
  }

  private record BatchJob(Supplier supplier, SupplierBatchRequest request) {}

  private record BatchResult(Supplier supplier, SupplierBatchOutcome outcome) {}
}
