package io.github.samuel426.lodginghub.supplier.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.samuel426.lodginghub.supplier.model.SupplierCatalog.CatalogRoom;
import io.github.samuel426.lodginghub.supplier.model.SupplierCatalog.CatalogStay;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SupplierCatalogTest {
  private static final String ROOM_CODE = "room";
  private static final String ROOM_NAME = "Room";

  @Test
  void emptySnapshotIsValidButMissingListIsNot() {
    assertThat(new SupplierCatalog(List.of()).stays()).isEmpty();
    assertThatThrownBy(() -> new SupplierCatalog(null)).isInstanceOf(SupplierCallException.class);
    assertThatThrownBy(() -> new SupplierCatalog(Arrays.asList((CatalogStay) null)))
        .isInstanceOf(SupplierCallException.class);
  }

  @Test
  void validatesKeysAtTheirActualScope() {
    var room = new CatalogRoom("shared-room", "Twin", 2);
    var first = new CatalogStay("hotel-1", "First", List.of(room));
    var second = new CatalogStay("hotel-2", "Second", List.of(room));
    assertThat(new SupplierCatalog(List.of(first, second)).stays()).hasSize(2);
    assertThatThrownBy(() -> new SupplierCatalog(List.of(first, first)))
        .isInstanceOf(SupplierCallException.class);
    assertThatThrownBy(() -> new CatalogStay("hotel-1", "First", List.of(room, room)))
        .isInstanceOf(SupplierCallException.class);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\n", "hotel\r\nvalue"})
  void rejectsMissingOrUnsafeText(String value) {
    assertThatThrownBy(() -> new CatalogStay(value, "Hotel", List.of()))
        .isInstanceOf(SupplierCallException.class);
    assertThatThrownBy(() -> new CatalogRoom(ROOM_CODE, value, 2))
        .isInstanceOf(SupplierCallException.class);
  }

  @Test
  void rejectsOutOfStorageBoundsAndMissingOccupancy() {
    assertThatThrownBy(() -> new CatalogRoom("r".repeat(129), ROOM_NAME, 2))
        .isInstanceOf(SupplierCallException.class);
    assertThatThrownBy(() -> new CatalogStay("hotel", "n".repeat(256), List.of()))
        .isInstanceOf(SupplierCallException.class);
    assertThatThrownBy(() -> new CatalogRoom(ROOM_CODE, ROOM_NAME, null))
        .isInstanceOf(SupplierCallException.class);
    assertThatThrownBy(() -> new CatalogRoom(ROOM_CODE, ROOM_NAME, 0))
        .isInstanceOf(SupplierCallException.class);
    assertThatThrownBy(() -> new CatalogRoom(ROOM_CODE, ROOM_NAME, -1))
        .isInstanceOf(SupplierCallException.class);
    assertThatThrownBy(() -> new CatalogStay("hotel", "Hotel", null))
        .isInstanceOf(SupplierCallException.class);
  }

  @Test
  void takesDefensiveCopiesAtEveryListBoundary() {
    var rooms = new ArrayList<>(List.of(new CatalogRoom(ROOM_CODE, ROOM_NAME, 2)));
    var stays = new ArrayList<>(List.of(new CatalogStay("hotel", "Hotel", rooms)));
    var catalog = new SupplierCatalog(stays);
    rooms.clear();
    stays.clear();
    assertThat(catalog.stays()).hasSize(1);
    assertThat(catalog.stays().getFirst().rooms()).hasSize(1);
    assertThatThrownBy(() -> catalog.stays().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
