package io.github.samuel426.lodginghub.supplier.b.dto;

import java.util.List;
import tools.jackson.databind.JsonNode;

public record SupplierBAvailabilityResponse(String resultCode, Data data) {
  public record Data(List<JsonNode> items) {}

  public record Offer(
      String propertyId,
      String propertyName,
      String roomId,
      String roomName,
      Integer maxOccupancy,
      Boolean breakfastIncluded,
      String currency,
      Long totalPrice,
      Boolean taxIncluded,
      List<Inventory> inventory) {}

  public record Inventory(String date, Integer remainingRooms) {}
}
