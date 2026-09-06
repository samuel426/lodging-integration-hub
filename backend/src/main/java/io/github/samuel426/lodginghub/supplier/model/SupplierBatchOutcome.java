package io.github.samuel426.lodginghub.supplier.model;

import java.util.List;

public record SupplierBatchOutcome(
    List<SupplierOffer> validOffers,
    int rejectedOfferCount,
    boolean isValidatedEmptyBatch,
    SupplierFailureCategory failure) {
  public SupplierBatchOutcome {
    validOffers = List.copyOf(validOffers);
    if (rejectedOfferCount < 0
        || (isValidatedEmptyBatch
            && (!validOffers.isEmpty() || rejectedOfferCount != 0 || failure != null))
        || (failure != null && (!validOffers.isEmpty() || rejectedOfferCount != 0))) {
      throw new IllegalArgumentException("Inconsistent supplier batch outcome");
    }
  }

  public static SupplierBatchOutcome failed(SupplierFailureCategory category) {
    return new SupplierBatchOutcome(
        List.of(), 0, false, java.util.Objects.requireNonNull(category));
  }
}
