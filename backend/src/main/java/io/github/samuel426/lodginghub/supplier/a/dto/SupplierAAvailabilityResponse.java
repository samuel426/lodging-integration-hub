package io.github.samuel426.lodginghub.supplier.a.dto;

import java.util.List;
import tools.jackson.databind.JsonNode;

public record SupplierAAvailabilityResponse(List<JsonNode> items) {
  public record Offer(
      String hotelCode,
      String hotelName,
      String roomTypeCode,
      String roomTypeName,
      Integer maxOccupancy,
      Boolean breakfastIncluded,
      String currency,
      List<DailyRate> dailyRates) {}

  public record DailyRate(String date, Integer remainingRooms, Long nightlyRate, Long taxAmount) {}
}
