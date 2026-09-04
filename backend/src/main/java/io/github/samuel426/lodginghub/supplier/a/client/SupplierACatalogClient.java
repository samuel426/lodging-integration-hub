package io.github.samuel426.lodginghub.supplier.a.client;

import io.github.samuel426.lodginghub.global.config.SupplierClientProperties;
import io.github.samuel426.lodginghub.supplier.a.dto.SupplierACatalogResponse;
import io.github.samuel426.lodginghub.supplier.client.SupplierCatalogClient;
import io.github.samuel426.lodginghub.supplier.client.SupplierHttpSupport;
import io.github.samuel426.lodginghub.supplier.model.Supplier;
import io.github.samuel426.lodginghub.supplier.model.SupplierCatalog;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class SupplierACatalogClient implements SupplierCatalogClient {
  private final WebClient client;
  private final Duration deadline;

  public SupplierACatalogClient(
      @Qualifier("supplierAWebClient") WebClient client, SupplierClientProperties properties) {
    this.client = client;
    this.deadline = properties.requestTimeout();
  }

  @Override
  public Supplier supplier() {
    return Supplier.SUPPLIER_A;
  }

  @Override
  public Mono<SupplierCatalog> fetchCatalog() {
    return SupplierHttpSupport.get(client, "/a/v1/hotels", SupplierACatalogResponse.class, deadline)
        .map(SupplierACatalogResponse::toCatalog);
  }
}
