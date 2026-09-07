package io.github.samuel426.lodginghub.supplier.b.mapper;

import io.github.samuel426.lodginghub.supplier.b.dto.SupplierBAvailabilityResponse;
import io.github.samuel426.lodginghub.supplier.b.dto.SupplierBAvailabilityResponse.Inventory;
import io.github.samuel426.lodginghub.supplier.client.SupplierJsonSupport;
import io.github.samuel426.lodginghub.supplier.mapper.InvalidSupplierOfferException;
import io.github.samuel426.lodginghub.supplier.mapper.OfferBatchMapper;
import io.github.samuel426.lodginghub.supplier.mapper.OfferValidation;
import io.github.samuel426.lodginghub.supplier.model.*;
import tools.jackson.databind.JsonNode;

public final class SupplierBOfferMapper {
  private SupplierBOfferMapper() {}

  public static SupplierBatchOutcome map(
      SupplierBAvailabilityResponse response, SupplierBatchRequest request) {
    if (!"0000".equals(response.resultCode())) {
      throw new SupplierCallException(category(response.resultCode()));
    }
    if (response.data() == null) {
      throw SupplierCallException.invalidResponse();
    }
    return OfferBatchMapper.map(response.data().items(), item -> offer(item, request));
  }

  private static SupplierFailureCategory category(String code) {
    if (code == null) {
      return SupplierFailureCategory.INVALID_RESPONSE;
    }
    return switch (code) {
      case "E400" -> SupplierFailureCategory.INVALID_REQUEST;
      case "E401" -> SupplierFailureCategory.AUTHENTICATION_ERROR;
      case "E429" -> SupplierFailureCategory.RATE_LIMITED;
      case "E500", "E503" -> SupplierFailureCategory.UPSTREAM_ERROR;
      default -> SupplierFailureCategory.INVALID_RESPONSE;
    };
  }

  private static SupplierOffer offer(JsonNode item, SupplierBatchRequest request) {
    var source =
        SupplierJsonSupport.mapper().treeToValue(item, SupplierBAvailabilityResponse.Offer.class);
    if (!Boolean.TRUE.equals(source.taxIncluded())) {
      throw new InvalidSupplierOfferException();
    }
    var nights = OfferValidation.nights(source.inventory(), Inventory::date, request.condition());
    int inventory =
        nights.values().stream()
            .mapToInt(day -> OfferValidation.inventory(day.remainingRooms()))
            .min()
            .orElseThrow();
    return new SupplierOffer(
        Supplier.SUPPLIER_B,
        OfferValidation.stayCode(source.propertyId(), request),
        OfferValidation.text(source.roomId(), 128),
        OfferValidation.text(source.propertyName(), 255),
        OfferValidation.text(source.roomName(), 255),
        OfferValidation.occupancy(source.maxOccupancy()),
        OfferValidation.required(source.breakfastIncluded()),
        inventory,
        new PriceSummary(
            OfferValidation.money(source.totalPrice()),
            OfferValidation.currency(source.currency()),
            true,
            null,
            null));
  }
}
