package io.github.samuel426.lodginghub.supplier.model;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record SupplierBatchRequest(List<String> stayCodes, AvailabilityCondition condition) {
  public static final int MAX_STAYS = 50;

  public SupplierBatchRequest {
    stayCodes = List.copyOf(stayCodes);
    Objects.requireNonNull(condition);
    if (stayCodes.isEmpty()
        || stayCodes.size() > MAX_STAYS
        || new HashSet<>(stayCodes).size() != stayCodes.size()) {
      throw new IllegalArgumentException("Invalid supplier batch size or duplicate code");
    }
  }
}
