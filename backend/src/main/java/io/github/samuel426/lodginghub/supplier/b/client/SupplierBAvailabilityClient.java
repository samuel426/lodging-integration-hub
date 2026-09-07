package io.github.samuel426.lodginghub.supplier.b.client;

import io.github.samuel426.lodginghub.global.config.SupplierClientProperties;
import io.github.samuel426.lodginghub.supplier.b.dto.SupplierBAvailabilityResponse;
import io.github.samuel426.lodginghub.supplier.b.mapper.SupplierBOfferMapper;
import io.github.samuel426.lodginghub.supplier.client.SupplierAvailabilityClient;
import io.github.samuel426.lodginghub.supplier.client.SupplierAvailabilityUri;
import io.github.samuel426.lodginghub.supplier.client.SupplierHttpSupport;
import io.github.samuel426.lodginghub.supplier.model.*;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class SupplierBAvailabilityClient implements SupplierAvailabilityClient {
  private final WebClient client;
  private final Duration deadline;

  public SupplierBAvailabilityClient(
      @Qualifier("supplierBWebClient") WebClient client, SupplierClientProperties properties) {
    this.client = client;
    this.deadline = properties.requestTimeout();
  }

  @Override
  public Supplier supplier() {
    return Supplier.SUPPLIER_B;
  }

  @Override
  public Mono<SupplierBatchOutcome> fetchAvailability(SupplierBatchRequest request) {
    return Mono.defer(
            () ->
                SupplierHttpSupport.get(
                        client,
                        builder ->
                            SupplierAvailabilityUri.build(
                                builder, "/b/api/search", "propertyIds", request),
                        SupplierBAvailabilityResponse.class,
                        deadline)
                    .map(response -> SupplierBOfferMapper.map(response, request)))
        .onErrorResume(
            SupplierCallException.class,
            error -> Mono.just(SupplierBatchOutcome.failed(error.category())));
  }
}
