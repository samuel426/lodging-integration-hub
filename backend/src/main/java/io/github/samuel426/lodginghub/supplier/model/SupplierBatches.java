package io.github.samuel426.lodginghub.supplier.model;

import java.util.ArrayList;
import java.util.List;

public final class SupplierBatches {
  private SupplierBatches() {}

  public static List<SupplierBatchRequest> split(
      List<String> codes, AvailabilityCondition condition) {
    var batches = new ArrayList<SupplierBatchRequest>();
    for (int from = 0; from < codes.size(); from += SupplierBatchRequest.MAX_STAYS) {
      batches.add(
          new SupplierBatchRequest(
              codes.subList(from, Math.min(from + SupplierBatchRequest.MAX_STAYS, codes.size())),
              condition));
    }
    return List.copyOf(batches);
  }
}
