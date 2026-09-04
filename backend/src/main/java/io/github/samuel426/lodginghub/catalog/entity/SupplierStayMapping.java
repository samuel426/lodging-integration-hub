package io.github.samuel426.lodginghub.catalog.entity;

import io.github.samuel426.lodginghub.supplier.model.Supplier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
    name = "supplier_stay_mapping",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_supplier_stay",
            columnNames = {"supplier", "external_stay_code"}))
public class SupplierStayMapping {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32, updatable = false)
  private Supplier supplier;

  @Column(nullable = false, length = 128, updatable = false)
  private String externalStayCode;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "stay_id", nullable = false, updatable = false)
  private Stay stay;

  @Column(name = "is_active", nullable = false)
  private boolean isListingActive;

  @Column(nullable = false)
  private Instant lastSyncedAt;

  protected SupplierStayMapping() {}

  public SupplierStayMapping(Supplier supplier, String externalStayCode, Stay stay, Instant now) {
    this.supplier = supplier;
    this.externalStayCode = externalStayCode;
    this.stay = stay;
    this.isListingActive = true;
    this.lastSyncedAt = now;
  }

  public void synchronize(boolean isListingActive, Instant now) {
    this.isListingActive = isListingActive;
    this.lastSyncedAt = now;
  }

  public Long getId() {
    return id;
  }

  public Supplier getSupplier() {
    return supplier;
  }

  public String getExternalStayCode() {
    return externalStayCode;
  }

  public Stay getStay() {
    return stay;
  }

  public boolean isActive() {
    return isListingActive;
  }
}
