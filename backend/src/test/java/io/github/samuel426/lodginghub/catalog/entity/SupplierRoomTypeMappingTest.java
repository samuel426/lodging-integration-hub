package io.github.samuel426.lodginghub.catalog.entity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.samuel426.lodginghub.supplier.model.Supplier;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SupplierRoomTypeMappingTest {
  @Test
  void rejectsRoomOwnedByAnotherInternalStay() {
    Instant now = Instant.parse("2026-09-04T00:00:00Z");
    var first = new Stay("First", now);
    var second = new Stay("Second", now);
    var mapping = new SupplierStayMapping(Supplier.SUPPLIER_A, "hotel", first, now);
    var room = new RoomType(second, "Room", 2, now);
    assertThatThrownBy(() -> new SupplierRoomTypeMapping(mapping, "room", room, now))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Room and mapping must belong to the same stay");
  }
}
