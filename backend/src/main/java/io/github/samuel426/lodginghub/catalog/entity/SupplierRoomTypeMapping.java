package io.github.samuel426.lodginghub.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(
    name = "supplier_room_type_mapping",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_supplier_room",
            columnNames = {"supplier_stay_mapping_id", "external_room_type_code"}))
public class SupplierRoomTypeMapping {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "supplier_stay_mapping_id", nullable = false, updatable = false)
  private SupplierStayMapping stayMapping;

  @Column(nullable = false, length = 128, updatable = false)
  private String externalRoomTypeCode;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "room_type_id", nullable = false, updatable = false)
  private RoomType roomType;

  @Column(name = "is_active", nullable = false)
  private boolean isListingActive;

  @Column(nullable = false)
  private Instant lastSyncedAt;

  protected SupplierRoomTypeMapping() {}

  public SupplierRoomTypeMapping(
      SupplierStayMapping stayMapping,
      String externalRoomTypeCode,
      RoomType roomType,
      Instant now) {
    if (!stayMapping.getStay().getId().equals(roomType.getStay().getId())) {
      throw new IllegalArgumentException("Room and mapping must belong to the same stay");
    }
    this.stayMapping = stayMapping;
    this.externalRoomTypeCode = externalRoomTypeCode;
    this.roomType = roomType;
    this.isListingActive = true;
    this.lastSyncedAt = now;
  }

  public void synchronize(boolean isListingActive, Instant now) {
    this.isListingActive = isListingActive;
    this.lastSyncedAt = now;
  }

  public SupplierStayMapping getStayMapping() {
    return stayMapping;
  }

  public String getExternalRoomTypeCode() {
    return externalRoomTypeCode;
  }

  public RoomType getRoomType() {
    return roomType;
  }

  public boolean isActive() {
    return isListingActive;
  }
}
