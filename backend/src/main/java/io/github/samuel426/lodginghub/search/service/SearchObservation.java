package io.github.samuel426.lodginghub.search.service;

import io.github.samuel426.lodginghub.global.config.CorrelationIdFilter;
import io.github.samuel426.lodginghub.supplier.model.Supplier;
import io.github.samuel426.lodginghub.supplier.model.SupplierBatchOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class SearchObservation {
  private static final String OUTCOME_TAG = "outcome";
  private static final Logger LOG = LoggerFactory.getLogger(SearchObservation.class);
  private final MeterRegistry registry;

  public SearchObservation(MeterRegistry registry) {
    this.registry = registry;
  }

  public Mono<SupplierBatchOutcome> batch(
      Supplier supplier, int size, Mono<SupplierBatchOutcome> call) {
    return Mono.deferContextual(
        context -> {
          long started = System.nanoTime();
          String trace = context.getOrDefault(CorrelationIdFilter.TRACE_ID, "startup");
          var recorded = new AtomicBoolean();
          Consumer<String> record =
              outcome -> {
                if (recorded.compareAndSet(false, true)) {
                  batchFinished(supplier, size, outcome, trace, started);
                }
              };
          return call.doOnNext(
                  result -> {
                    String outcome =
                        result.failure() != null
                            ? result.failure().name()
                            : result.rejectedOfferCount() > 0 ? "PARTIAL_DATA" : "SUCCESS";
                    record.accept(outcome);
                  })
              .doOnError(error -> record.accept("INTERNAL_ERROR"))
              .doOnCancel(() -> record.accept("CANCELLED"));
        });
  }

  private void batchFinished(
      Supplier supplier, int size, String outcome, String trace, long started) {
    long nanos = System.nanoTime() - started;
    Timer.builder("supplier.availability.duration")
        .tags("supplier", supplier.name(), OUTCOME_TAG, outcome)
        .register(registry)
        .record(nanos, TimeUnit.NANOSECONDS);
    LOG.atInfo()
        .addKeyValue("traceId", trace)
        .addKeyValue("supplier", supplier.name())
        .addKeyValue("operation", "availability")
        .addKeyValue(OUTCOME_TAG, outcome)
        .addKeyValue("batchSize", size)
        .addKeyValue("durationMs", TimeUnit.NANOSECONDS.toMillis(nanos))
        .log("supplier_call");
  }

  public void counts(SearchAggregation.ObservationCounts counts) {
    count("search.observations", "valid_offer", counts.validOffers());
    count("search.observations", "empty_batch", counts.emptyBatches());
    count("search.observations", "empty_catalog", counts.emptyCatalogs());
    count("search.rejections", "invalid_data", counts.invalidOffers());
    count("search.rejections", "missing_mapping", counts.missingMappings());
  }

  private void count(String name, String kind, int count) {
    if (count > 0) {
      registry.counter(name, "kind", kind).increment(count);
    }
  }

  public void finished(String outcome, long started) {
    Timer.builder("search.duration")
        .tag(OUTCOME_TAG, outcome)
        .register(registry)
        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    LOG.atInfo().addKeyValue(OUTCOME_TAG, outcome).log("search_completed");
  }
}
