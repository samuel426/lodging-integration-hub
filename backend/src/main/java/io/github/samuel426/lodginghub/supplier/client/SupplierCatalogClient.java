package io.github.samuel426.lodginghub.supplier.client;

import io.github.samuel426.lodginghub.supplier.model.Supplier;
import io.github.samuel426.lodginghub.supplier.model.SupplierCatalog;
import reactor.core.publisher.Mono;

public interface SupplierCatalogClient {
  Supplier supplier();

  Mono<SupplierCatalog> fetchCatalog();
}
