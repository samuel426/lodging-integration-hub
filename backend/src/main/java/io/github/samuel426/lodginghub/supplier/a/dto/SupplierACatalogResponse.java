package io.github.samuel426.lodginghub.supplier.a.dto;

import io.github.samuel426.lodginghub.supplier.model.SupplierCallException;
import io.github.samuel426.lodginghub.supplier.model.SupplierCatalog;
import java.util.List;

public record SupplierACatalogResponse(List<Hotel> items) {
  public SupplierCatalog toCatalog() {
    if (items == null || items.stream().anyMatch(item -> item == null)) {
      throw SupplierCallException.invalidResponse();
    }
    return new SupplierCatalog(items.stream().map(Hotel::toStay).toList());
  }

  public record Hotel(String hotelCode, String hotelName, List<Room> roomTypes) {
    SupplierCatalog.CatalogStay toStay() {
      if (roomTypes == null || roomTypes.stream().anyMatch(room -> room == null)) {
        throw SupplierCallException.invalidResponse();
      }
      return new SupplierCatalog.CatalogStay(
          hotelCode,
          hotelName,
          roomTypes.stream()
              .map(
                  room ->
                      new SupplierCatalog.CatalogRoom(
                          room.roomTypeCode(), room.roomTypeName(), room.maxOccupancy()))
              .toList());
    }
  }

  public record Room(String roomTypeCode, String roomTypeName, Integer maxOccupancy) {}
}
