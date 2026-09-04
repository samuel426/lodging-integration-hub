package io.github.samuel426.lodginghub.catalog.dto;

import io.github.samuel426.lodginghub.supplier.model.Supplier;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Detached, immutable view; no JPA objects escape the catalog domain. */
public record CatalogSnapshot(List<SupplierCatalogView> suppliers) {
  public CatalogSnapshot {
    suppliers = List.copyOf(suppliers);
  }

  public boolean isReady() {
    return suppliers.stream().anyMatch(SupplierCatalogView::isReady);
  }

  public List<Supplier> unavailableSuppliers() {
    return suppliers.stream()
        .filter(view -> !view.isReady())
        .map(SupplierCatalogView::supplier)
        .toList();
  }

  public record SupplierCatalogView(
      Supplier supplier,
      Instant lastAttemptedAt,
      Instant lastSucceededAt,
      String lastFailureCategory,
      List<StayView> stays) {
    public SupplierCatalogView {
      stays = List.copyOf(stays);
    }

    public boolean isReady() {
      return lastSucceededAt != null;
    }
  }

  public record StayView(UUID stayId, String externalCode, String name, List<RoomView> rooms) {
    public StayView {
      rooms = List.copyOf(rooms);
    }
  }

  public record RoomView(UUID roomTypeId, String externalCode, String name, int maxOccupancy) {}
}
