package io.github.samuel426.lodginghub.supplier.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.samuel426.lodginghub.supplier.model.Supplier;
import io.github.samuel426.lodginghub.supplier.model.SupplierBatchOutcome;
import io.github.samuel426.lodginghub.supplier.model.SupplierFailureCategory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class SupplierAvailabilityGuard {
  private final CircuitBreakerRegistry registry;

  public SupplierAvailabilityGuard(CircuitBreakerRegistry registry) {
    this.registry = registry;
  }

  public Mono<SupplierBatchOutcome> protect(Supplier supplier, Mono<SupplierBatchOutcome> call) {
    return call.switchIfEmpty(Mono.error(new IllegalStateException("Adapter returned no outcome")))
        .flatMap(
            outcome ->
                isExcluded(outcome)
                    ? Mono.error(new ExcludedOutcomeException(outcome))
                    : Mono.just(outcome))
        .transformDeferred(CircuitBreakerOperator.of(registry.circuitBreaker(supplier.name())))
        .onErrorResume(ExcludedOutcomeException.class, error -> Mono.just(error.batchOutcome))
        .onErrorResume(
            CallNotPermittedException.class,
            error -> Mono.just(SupplierBatchOutcome.failed(SupplierFailureCategory.CIRCUIT_OPEN)));
  }

  public static boolean isAvailabilityFailure(SupplierBatchOutcome outcome) {
    return outcome.failure() == SupplierFailureCategory.TIMEOUT
        || outcome.failure() == SupplierFailureCategory.CONNECTION_ERROR
        || outcome.failure() == SupplierFailureCategory.UPSTREAM_ERROR
        || outcome.failure() == SupplierFailureCategory.RATE_LIMITED;
  }

  private static boolean isExcluded(SupplierBatchOutcome outcome) {
    if (outcome.failure() != null) {
      return !isAvailabilityFailure(outcome);
    }
    return outcome.validOffers().isEmpty() && !outcome.isValidatedEmptyBatch();
  }

  private static final class ExcludedOutcomeException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient SupplierBatchOutcome batchOutcome;

    private ExcludedOutcomeException(SupplierBatchOutcome outcome) {
      super("Outcome excluded from availability failure rate", null, false, false);
      this.batchOutcome = outcome;
    }
  }
}
