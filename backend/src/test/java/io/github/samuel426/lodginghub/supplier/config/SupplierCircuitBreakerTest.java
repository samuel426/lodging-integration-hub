package io.github.samuel426.lodginghub.supplier.config;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.samuel426.lodginghub.supplier.model.*;
import io.github.samuel426.lodginghub.supplier.service.SupplierAvailabilityGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.core.publisher.Mono;

class SupplierCircuitBreakerTest {
  private static final Supplier A = Supplier.SUPPLIER_A;
  private static final Supplier B = Supplier.SUPPLIER_B;
  private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
  private CircuitBreakerRegistry registry;
  private SupplierAvailabilityGuard guard;

  @BeforeEach
  void setup() {
    registry =
        new SupplierCircuitBreakerConfiguration()
            .supplierCircuitBreakers(
                new SupplierCircuitBreakerProperties(
                    4, 4, 50, Duration.ofSeconds(10), 2, Duration.ofSeconds(5)),
                metrics);
    guard = new SupplierAvailabilityGuard(registry);
  }

  @ParameterizedTest
  @EnumSource(
      value = SupplierFailureCategory.class,
      names = {"TIMEOUT", "CONNECTION_ERROR", "UPSTREAM_ERROR", "RATE_LIMITED"})
  void availabilityFailuresOpenOnlyTheirSupplierAndSkipColdPublisher(
      SupplierFailureCategory category) {
    var calls = new AtomicInteger();
    var call =
        Mono.fromSupplier(
            () -> {
              calls.incrementAndGet();
              return SupplierBatchOutcome.failed(category);
            });
    for (int i = 0; i < 3; i++) {
      assertThat(guard.protect(A, call).block().failure()).isEqualTo(category);
    }
    assertThat(breaker(A).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(guard.protect(A, call).block().failure()).isEqualTo(category);
    assertThat(breaker(A).getState()).isEqualTo(CircuitBreaker.State.OPEN);
    assertThat(guard.protect(A, call).block().failure())
        .isEqualTo(SupplierFailureCategory.CIRCUIT_OPEN);
    assertThat(calls.get()).isEqualTo(4);
    assertThat(guard.protect(B, Mono.just(empty())).block()).isEqualTo(empty());
    assertThat(breaker(B).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(
            metrics
                .get("supplier.circuit.transitions")
                .tags("supplier", A.name(), "transition", "CLOSED_TO_OPEN")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            metrics
                .get("resilience4j.circuitbreaker.not.permitted.calls")
                .tag("name", A.name())
                .counter()
                .count())
        .isEqualTo(1);
  }

  @Test
  void failureRateUsesMinimumSampleAndSuccessfulEmptyResults() {
    guard.protect(A, Mono.just(empty())).block();
    guard.protect(A, Mono.just(empty())).block();
    guard
        .protect(A, Mono.just(SupplierBatchOutcome.failed(SupplierFailureCategory.UPSTREAM_ERROR)))
        .block();
    assertThat(breaker(A).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    guard
        .protect(A, Mono.just(SupplierBatchOutcome.failed(SupplierFailureCategory.UPSTREAM_ERROR)))
        .block();
    assertThat(breaker(A).getMetrics().getFailureRate()).isEqualTo(50);
    assertThat(breaker(A).getState()).isEqualTo(CircuitBreaker.State.OPEN);
  }

  @ParameterizedTest
  @EnumSource(
      value = SupplierFailureCategory.class,
      names = {"AUTHENTICATION_ERROR", "INVALID_REQUEST", "INVALID_RESPONSE"})
  void excludedFailuresAreNeitherHealthyNorAvailabilityFailures(SupplierFailureCategory category) {
    var result = SupplierBatchOutcome.failed(category);
    for (int i = 0; i < 8; i++) {
      assertThat(guard.protect(A, Mono.just(result)).block()).isEqualTo(result);
    }
    assertThat(breaker(A).getMetrics().getNumberOfBufferedCalls()).isZero();
    assertThat(breaker(A).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  @Test
  void allRejectedDataIsNotHealthyAndInternalBugsPropagate() {
    var result = new SupplierBatchOutcome(List.of(), 2, false, null);
    assertThat(guard.protect(A, Mono.just(result)).block()).isEqualTo(result);
    assertThatThrownBy(
            () -> guard.protect(A, Mono.error(new IllegalStateException("internal"))).block())
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> guard.protect(A, Mono.empty()).block())
        .isInstanceOf(IllegalStateException.class);
    assertThat(breaker(A).getMetrics().getNumberOfBufferedCalls()).isZero();
  }

  @Test
  void twoSuccessfulProbesCloseAndFailedProbesReopen() {
    breaker(A).transitionToOpenState();
    breaker(A).transitionToHalfOpenState();
    guard.protect(A, Mono.just(empty())).block();
    assertThat(breaker(A).getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    guard.protect(A, Mono.just(empty())).block();
    assertThat(breaker(A).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    breaker(A).transitionToOpenState();
    breaker(A).transitionToHalfOpenState();
    guard.protect(A, Mono.just(empty())).block();
    guard
        .protect(A, Mono.just(SupplierBatchOutcome.failed(SupplierFailureCategory.TIMEOUT)))
        .block();
    assertThat(breaker(A).getState()).isEqualTo(CircuitBreaker.State.OPEN);
  }

  @Test
  void halfOpenConcurrencyIsBoundedAndCancellationReleasesProbe() {
    breaker(A).transitionToOpenState();
    breaker(A).transitionToHalfOpenState();
    var subscriptions = new AtomicInteger();
    Mono<SupplierBatchOutcome> pending =
        Mono.defer(
            () -> {
              subscriptions.incrementAndGet();
              return Mono.never();
            });
    var first = guard.protect(A, pending).subscribe();
    var second = guard.protect(A, pending).subscribe();
    assertThat(guard.protect(A, pending).block().failure())
        .isEqualTo(SupplierFailureCategory.CIRCUIT_OPEN);
    assertThat(subscriptions.get()).isEqualTo(2);
    first.dispose();
    var replacement = guard.protect(A, pending).subscribe();
    assertThat(subscriptions.get()).isEqualTo(3);
    second.dispose();
    replacement.dispose();
    assertThat(breaker(A).getMetrics().getNumberOfBufferedCalls()).isZero();
    breaker(A).reset();
  }

  @Test
  void halfOpenWithoutEnoughObservationsDoesNotWaitForever() {
    var shortRegistry =
        new SupplierCircuitBreakerConfiguration()
            .supplierCircuitBreakers(
                new SupplierCircuitBreakerProperties(
                    4, 4, 50, Duration.ofSeconds(10), 2, Duration.ofMillis(100)),
                new SimpleMeterRegistry());
    var circuit = shortRegistry.circuitBreaker(A.name());
    circuit.transitionToOpenState();
    circuit.transitionToHalfOpenState();
    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(() -> assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN));
  }

  @Test
  void invalidConfigurationFailsAtStartup() {
    assertThatThrownBy(
            () ->
                new SupplierCircuitBreakerProperties(
                    4, 5, 50, Duration.ofSeconds(10), 2, Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SupplierCircuitBreakerProperties(
                    4, 4, Float.NaN, Duration.ofSeconds(10), 2, Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new SupplierCircuitBreakerProperties(
                    4, 4, 50, Duration.ZERO, 2, Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private CircuitBreaker breaker(Supplier supplier) {
    return registry.circuitBreaker(supplier.name());
  }

  private static SupplierBatchOutcome empty() {
    return new SupplierBatchOutcome(List.of(), 0, true, null);
  }
}
