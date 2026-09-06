package io.github.samuel426.lodginghub.supplier.b.dto;

import io.github.samuel426.lodginghub.supplier.model.SupplierCallException;
import io.github.samuel426.lodginghub.supplier.model.SupplierCatalog;
import io.github.samuel426.lodginghub.supplier.model.SupplierFailureCategory;
import java.util.List;

public record SupplierBCatalogResponse(String resultCode, Data data) {
  public SupplierCatalog toCatalog() {
    if (!"0000".equals(resultCode)) {
      throw new SupplierCallException(failureCategory());
    }
    if (data == null
        || data.items() == null
        || data.items().stream().anyMatch(item -> item == null)) {
      throw SupplierCallException.invalidResponse();
    }
    return new SupplierCatalog(data.items().stream().map(Property::toStay).toList());
  }

  private SupplierFailureCategory failureCategory() {
    if (resultCode == null) {
      return SupplierFailureCategory.INVALID_RESPONSE;
    }
    return switch (resultCode) {
      case "E400" -> SupplierFailureCategory.INVALID_REQUEST;
      case "E401" -> SupplierFailureCategory.AUTHENTICATION_ERROR;
      case "E429" -> SupplierFailureCategory.RATE_LIMITED;
      case "E500", "E503" -> SupplierFailureCategory.UPSTREAM_ERROR;
      default -> SupplierFailureCategory.INVALID_RESPONSE;
    };
  }

  public record Data(List<Property> items) {}

  public record Property(String propertyId, String propertyName, List<Room> rooms) {
    SupplierCatalog.CatalogStay toStay() {
      if (rooms == null || rooms.stream().anyMatch(room -> room == null)) {
        throw SupplierCallException.invalidResponse();
      }
      return new SupplierCatalog.CatalogStay(
          propertyId,
          propertyName,
          rooms.stream()
              .map(
                  room ->
                      new SupplierCatalog.CatalogRoom(
                          room.roomId(), room.roomName(), room.maxOccupancy()))
              .toList());
    }
  }

  public record Room(String roomId, String roomName, Integer maxOccupancy) {}
}
