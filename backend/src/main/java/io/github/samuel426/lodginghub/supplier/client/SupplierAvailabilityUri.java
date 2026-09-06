package io.github.samuel426.lodginghub.supplier.client;

import io.github.samuel426.lodginghub.supplier.model.SupplierBatchRequest;
import io.github.samuel426.lodginghub.supplier.model.SupplierCallException;
import io.github.samuel426.lodginghub.supplier.model.SupplierFailureCategory;
import java.net.URI;
import org.springframework.web.util.UriBuilder;

public final class SupplierAvailabilityUri {
  private SupplierAvailabilityUri() {}

  public static URI build(
      UriBuilder builder, String path, String codesParameter, SupplierBatchRequest request) {
    // CSV contracts cannot represent an individual code containing the separator.
    if (request.stayCodes().stream()
        .anyMatch(
            code ->
                code.isBlank()
                    || code.contains(",")
                    || code.chars().anyMatch(Character::isISOControl))) {
      throw new SupplierCallException(SupplierFailureCategory.INVALID_REQUEST);
    }
    var condition = request.condition();
    return builder
        .path(path)
        .queryParam(codesParameter, "{codes}")
        .queryParam("checkIn", condition.checkIn())
        .queryParam("checkOut", condition.checkOut())
        .queryParam("adults", condition.adults())
        .queryParam("children", condition.children())
        .build(String.join(",", request.stayCodes()));
  }
}
