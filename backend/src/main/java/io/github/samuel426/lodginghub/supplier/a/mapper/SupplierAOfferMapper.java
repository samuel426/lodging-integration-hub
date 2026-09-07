package io.github.samuel426.lodginghub.supplier.a.mapper;

import io.github.samuel426.lodginghub.supplier.a.dto.SupplierAAvailabilityResponse;
import io.github.samuel426.lodginghub.supplier.a.dto.SupplierAAvailabilityResponse.DailyRate;
import io.github.samuel426.lodginghub.supplier.client.SupplierJsonSupport;
import io.github.samuel426.lodginghub.supplier.mapper.OfferBatchMapper;
import io.github.samuel426.lodginghub.supplier.mapper.OfferValidation;
import io.github.samuel426.lodginghub.supplier.model.*;
import java.util.ArrayList;
import tools.jackson.databind.JsonNode;

public final class SupplierAOfferMapper {
  private SupplierAOfferMapper() {}

  public static SupplierBatchOutcome map(
      SupplierAAvailabilityResponse response, SupplierBatchRequest request) {
    return OfferBatchMapper.map(response.items(), item -> offer(item, request));
  }

  private static SupplierOffer offer(JsonNode item, SupplierBatchRequest request) {
    var source =
        SupplierJsonSupport.mapper().treeToValue(item, SupplierAAvailabilityResponse.Offer.class);
    var nights = OfferValidation.nights(source.dailyRates(), DailyRate::date, request.condition());
    long total = 0;
    long tax = 0;
    int inventory = Integer.MAX_VALUE;
    var breakdown = new ArrayList<PriceSummary.NightlyPrice>();
    for (var entry : nights.entrySet()) {
      var day = entry.getValue();
      long dayTax = OfferValidation.money(day.taxAmount());
      long gross = OfferValidation.add(OfferValidation.money(day.nightlyRate()), dayTax);
      total = OfferValidation.add(total, gross);
      tax = OfferValidation.add(tax, dayTax);
      inventory = Math.min(inventory, OfferValidation.inventory(day.remainingRooms()));
      breakdown.add(new PriceSummary.NightlyPrice(entry.getKey(), gross));
    }
    return new SupplierOffer(
        Supplier.SUPPLIER_A,
        OfferValidation.stayCode(source.hotelCode(), request),
        OfferValidation.text(source.roomTypeCode(), 128),
        OfferValidation.text(source.hotelName(), 255),
        OfferValidation.text(source.roomTypeName(), 255),
        OfferValidation.occupancy(source.maxOccupancy()),
        OfferValidation.required(source.breakfastIncluded()),
        inventory,
        new PriceSummary(total, OfferValidation.currency(source.currency()), true, tax, breakdown));
  }
}
