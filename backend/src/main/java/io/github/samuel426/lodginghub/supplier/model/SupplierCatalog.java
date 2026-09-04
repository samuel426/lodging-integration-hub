package io.github.samuel426.lodginghub.supplier.model;

import java.util.HashSet;
import java.util.List;
import java.util.function.Function;

/** A completely validated, immutable full snapshot. An empty list is meaningful. */
public record SupplierCatalog(List<CatalogStay> stays) {
  public SupplierCatalog {
    stays = validatedCopy(stays, CatalogStay::externalCode);
  }

  public record CatalogStay(String externalCode, String name, List<CatalogRoom> rooms) {
    public CatalogStay {
      requireText(externalCode, 128);
      requireText(name, 255);
      rooms = validatedCopy(rooms, CatalogRoom::externalCode);
    }
  }

  public record CatalogRoom(String externalCode, String name, Integer maxOccupancy) {
    public CatalogRoom {
      requireText(externalCode, 128);
      requireText(name, 255);
      if (maxOccupancy == null || maxOccupancy < 1) {
        throw SupplierCallException.invalidResponse();
      }
    }
  }

  private static void requireText(String value, int maxLength) {
    if (value == null
        || value.isBlank()
        || value.length() > maxLength
        || value.chars().anyMatch(Character::isISOControl)) {
      throw SupplierCallException.invalidResponse();
    }
  }

  private static <T> List<T> validatedCopy(List<T> items, Function<T, String> key) {
    if (items == null) {
      throw SupplierCallException.invalidResponse();
    }
    var keys = new HashSet<String>();
    for (T item : items) {
      if (item == null || !keys.add(key.apply(item))) {
        throw SupplierCallException.invalidResponse();
      }
    }
    return List.copyOf(items);
  }
}
