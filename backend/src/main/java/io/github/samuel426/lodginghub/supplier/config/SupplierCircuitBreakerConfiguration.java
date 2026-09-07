package io.github.samuel426.lodginghub.supplier.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.samuel426.lodginghub.supplier.model.Supplier;
import io.github.samuel426.lodginghub.supplier.model.SupplierBatchOutcome;
import io.github.samuel426.lodginghub.supplier.service.SupplierAvailabilityGuard;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupplierCircuitBreakerProperties.class)
public class SupplierCircuitBreakerConfiguration {
  private static final Logger LOG =
      LoggerFactory.getLogger(SupplierCircuitBreakerConfiguration.class);

  @Bean
  CircuitBreakerRegistry supplierCircuitBreakers(
      SupplierCircuitBreakerProperties properties, MeterRegistry metrics) {
    var config =
        CircuitBreakerConfig.custom()
            .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
            .slidingWindowSize(properties.slidingWindowSize())
            .minimumNumberOfCalls(properties.minimumNumberOfCalls())
            .failureRateThreshold(properties.failureRateThreshold())
            .waitDurationInOpenState(properties.waitDurationInOpenState())
            .permittedNumberOfCallsInHalfOpenState(properties.permittedCallsInHalfOpenState())
            .maxWaitDurationInHalfOpenState(properties.maxWaitDurationInHalfOpenState())
            .automaticTransitionFromOpenToHalfOpenEnabled(false)
            // Slow calls already terminate under the adapter deadline; use failure-rate protection.
            .slowCallDurationThreshold(Duration.ofDays(1))
            .recordResult(
                result ->
                    result instanceof SupplierBatchOutcome outcome
                        && SupplierAvailabilityGuard.isAvailabilityFailure(outcome))
            // Adapter transport failures are typed results. Unexpected exceptions remain internal
            // errors.
            .ignoreException(error -> true)
            .build();
    var registry = CircuitBreakerRegistry.of(config);
    for (Supplier supplier : Supplier.values()) {
      var breaker =
          registry.circuitBreaker(supplier.name(), config, Map.of("operation", "availability"));
      breaker
          .getEventPublisher()
          .onStateTransition(
              event -> {
                String transition = event.getStateTransition().name();
                metrics
                    .counter(
                        "supplier.circuit.transitions",
                        "supplier",
                        supplier.name(),
                        "transition",
                        transition)
                    .increment();
                LOG.atInfo()
                    .addKeyValue("supplier", supplier.name())
                    .addKeyValue("operation", "availability")
                    .addKeyValue("transition", transition)
                    .log("supplier_circuit_transition");
              });
    }
    TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(metrics);
    return registry;
  }
}
