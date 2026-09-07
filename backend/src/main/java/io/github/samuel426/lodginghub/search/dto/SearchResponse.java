package io.github.samuel426.lodginghub.search.dto;

import io.github.samuel426.lodginghub.supplier.model.PriceSummary;
import io.github.samuel426.lodginghub.supplier.model.Supplier;
import io.github.samuel426.lodginghub.supplier.model.SupplierFailureCategory;
import java.util.List;
import java.util.UUID;

public record SearchResponse(SearchData data, SearchMeta meta) {
  public record SearchData(List<StayOfferResponse> offers) {
    public SearchData {
      offers = List.copyOf(offers);
    }
  }

  public record StayOfferResponse(
      UUID stayId,
      String stayName,
      UUID roomTypeId,
      String roomTypeName,
      int maxOccupancy,
      int availableRoomCount,
      Supplier supplier,
      boolean breakfastIncluded,
      PriceSummary price) {}

  public record SearchMeta(
      boolean partial,
      int rejectedOfferCount,
      List<Supplier> unavailableCatalogSuppliers,
      List<SupplierFailureResponse> supplierFailures) {
    public SearchMeta {
      unavailableCatalogSuppliers = List.copyOf(unavailableCatalogSuppliers);
      supplierFailures = List.copyOf(supplierFailures);
    }
  }

  public record SupplierFailureResponse(
      Supplier supplier, SupplierFailureCategory category, int failedBatchCount) {}
}
