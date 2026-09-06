package io.github.samuel426.lodginghub.supplier.mapper;

import io.github.samuel426.lodginghub.supplier.model.SupplierBatchOutcome;
import io.github.samuel426.lodginghub.supplier.model.SupplierCallException;
import io.github.samuel426.lodginghub.supplier.model.SupplierOffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

public final class OfferBatchMapper {
  private OfferBatchMapper() {}

  public static SupplierBatchOutcome map(
      List<JsonNode> items, Function<JsonNode, SupplierOffer> mapper) {
    if (items == null) {
      throw SupplierCallException.invalidResponse();
    }
    var valid = new ArrayList<SupplierOffer>();
    int rejected = 0;
    for (JsonNode item : items) {
      try {
        if (item == null || !item.isObject()) {
          throw new InvalidSupplierOfferException();
        }
        valid.add(mapper.apply(item));
      } catch (InvalidSupplierOfferException | JacksonException error) {
        rejected++;
      }
    }
    return new SupplierBatchOutcome(valid, rejected, items.isEmpty(), null);
  }
}
