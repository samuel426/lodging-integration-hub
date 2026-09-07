package io.github.samuel426.lodginghub.search.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.samuel426.lodginghub.global.config.CorrelationIdFilter;
import io.github.samuel426.lodginghub.supplier.model.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Mono;

@ExtendWith(OutputCaptureExtension.class)
class SearchObservationTest {
  private static final String BATCH_TIMER = "supplier.availability.duration";
  private static final String OUTCOME_TAG = "outcome";
  private static final String KIND_TAG = "kind";
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(SearchObservation.class);

  @BeforeEach
  void captureEvents() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void detachEvents() {
    logger.detachAppender(logs);
    logs.stop();
  }

  @Test
  void downstreamCancellationAfterValueDoesNotDoubleCount() {
    var registry = new SimpleMeterRegistry();
    var observation = new SearchObservation(registry);
    observation
        .batch(
            Supplier.SUPPLIER_A, 1, Mono.just(new SupplierBatchOutcome(List.of(), 0, true, null)))
        .flux()
        .take(1)
        .blockLast();
    assertThat(registry.find(BATCH_TIMER).tag(OUTCOME_TAG, "CANCELLED").timer()).isNull();
    assertThat(registry.get(BATCH_TIMER).tag(OUTCOME_TAG, "SUCCESS").timer().count()).isEqualTo(1);
  }

  @Test
  void batchFailureAndValidEmptyAreDifferentMetricsWithSafeLogs(CapturedOutput output) {
    var registry = new SimpleMeterRegistry();
    var observation = new SearchObservation(registry);
    observation
        .batch(
            Supplier.SUPPLIER_A,
            50,
            Mono.just(SupplierBatchOutcome.failed(SupplierFailureCategory.TIMEOUT)))
        .contextWrite(context -> context.put(CorrelationIdFilter.TRACE_ID, "metric-trace"))
        .block();
    observation
        .batch(
            Supplier.SUPPLIER_B, 1, Mono.just(new SupplierBatchOutcome(List.of(), 0, true, null)))
        .block();
    assertThat(
            registry
                .get(BATCH_TIMER)
                .tags("supplier", "SUPPLIER_A", OUTCOME_TAG, "TIMEOUT")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get(BATCH_TIMER)
                .tags("supplier", "SUPPLIER_B", OUTCOME_TAG, "SUCCESS")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(output.getAll()).contains("supplier_call");
    assertThat(logs.list.stream().flatMap(event -> event.getKeyValuePairs().stream()).toList())
        .anySatisfy(
            pair -> {
              assertThat(pair.key).isEqualTo("traceId");
              assertThat(pair.value).isEqualTo("metric-trace");
            });
    assertThat(registry.getMeters())
        .allSatisfy(
            meter ->
                assertThat(meter.getId().getTags())
                    .noneSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("traceId")));
  }

  @Test
  void emptyObservationsAndRejectionsHaveIndependentCounters() {
    var registry = new SimpleMeterRegistry();
    var observation = new SearchObservation(registry);
    observation.counts(new SearchAggregation.ObservationCounts(3, 1, 1, 2, 1));
    observation.finished("PARTIAL", System.nanoTime());
    observation.finished("NO_VALID_SUPPLIER_DATA", System.nanoTime());
    assertThat(registry.get("search.observations").tag(KIND_TAG, "valid_offer").counter().count())
        .isEqualTo(3);
    assertThat(registry.get("search.observations").tag(KIND_TAG, "empty_batch").counter().count())
        .isEqualTo(1);
    assertThat(registry.get("search.rejections").tag(KIND_TAG, "invalid_data").counter().count())
        .isEqualTo(2);
    assertThat(registry.get("search.rejections").tag(KIND_TAG, "missing_mapping").counter().count())
        .isEqualTo(1);
    assertThat(registry.get("search.duration").tag(OUTCOME_TAG, "PARTIAL").timer().count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("search.duration")
                .tag(OUTCOME_TAG, "NO_VALID_SUPPLIER_DATA")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void internalErrorAndCancellationAreRecordedWithoutPayload(CapturedOutput output) {
    var registry = new SimpleMeterRegistry();
    var observation = new SearchObservation(registry);
    observation
        .batch(
            Supplier.SUPPLIER_A, 1, Mono.error(new IllegalStateException("private-upstream-body")))
        .onErrorComplete()
        .block();
    observation.batch(Supplier.SUPPLIER_B, 1, Mono.never()).take(Duration.ofMillis(20)).block();
    assertThat(registry.get(BATCH_TIMER).tag(OUTCOME_TAG, "INTERNAL_ERROR").timer().count())
        .isEqualTo(1);
    assertThat(registry.get(BATCH_TIMER).tag(OUTCOME_TAG, "CANCELLED").timer().count())
        .isEqualTo(1);
    assertThat(output.getAll()).doesNotContain("private-upstream-body");
  }
}
