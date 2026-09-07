package io.github.samuel426.lodginghub.supplier.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("suppliers.circuit-breaker")
public record SupplierCircuitBreakerProperties(
    int slidingWindowSize,
    int minimumNumberOfCalls,
    float failureRateThreshold,
    Duration waitDurationInOpenState,
    int permittedCallsInHalfOpenState,
    Duration maxWaitDurationInHalfOpenState) {
  public SupplierCircuitBreakerProperties {
    if (slidingWindowSize <= 0
        || minimumNumberOfCalls <= 0
        || minimumNumberOfCalls > slidingWindowSize
        || !Float.isFinite(failureRateThreshold)
        || failureRateThreshold <= 0
        || failureRateThreshold > 100
        || permittedCallsInHalfOpenState <= 0
        || !isPositive(waitDurationInOpenState)
        || !isPositive(maxWaitDurationInHalfOpenState)) {
      throw new IllegalArgumentException("Invalid supplier circuit breaker configuration");
    }
  }

  private static boolean isPositive(Duration value) {
    return value != null && !value.isNegative() && value.toMillis() > 0;
  }
}
