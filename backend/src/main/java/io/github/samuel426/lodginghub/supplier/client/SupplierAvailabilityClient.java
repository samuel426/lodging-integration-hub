package io.github.samuel426.lodginghub.supplier.client;

import io.github.samuel426.lodginghub.supplier.model.Supplier;
import io.github.samuel426.lodginghub.supplier.model.SupplierBatchOutcome;
import io.github.samuel426.lodginghub.supplier.model.SupplierBatchRequest;
import reactor.core.publisher.Mono;

public interface SupplierAvailabilityClient {
  Supplier supplier();

  Mono<SupplierBatchOutcome> fetchAvailability(SupplierBatchRequest request);
}
