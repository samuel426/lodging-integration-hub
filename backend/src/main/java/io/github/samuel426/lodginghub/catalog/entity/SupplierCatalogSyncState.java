package io.github.samuel426.lodginghub.catalog.entity;

import io.github.samuel426.lodginghub.supplier.model.Supplier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "supplier_catalog_sync_state")
public class SupplierCatalogSyncState {
  @Id
  @Enumerated(EnumType.STRING)
  @Column(length = 32)
  private Supplier supplier;

  @Column(nullable = false)
  private Instant lastAttemptedAt;

  private Instant lastSucceededAt;

  @Column(length = 64)
  private String lastFailureCategory;

  protected SupplierCatalogSyncState() {}

  public SupplierCatalogSyncState(Supplier supplier, Instant attemptedAt) {
    this.supplier = supplier;
    this.lastAttemptedAt = attemptedAt;
  }

  // POL-002: null means no current failure after a successful snapshot, not missing state.
  @SuppressWarnings("PMD.NullAssignment")
  public void succeed(Instant attemptedAt, Instant succeededAt) {
    this.lastAttemptedAt = attemptedAt;
    this.lastSucceededAt = succeededAt;
    this.lastFailureCategory = null;
  }

  public void fail(Instant attemptedAt, String failureCategory) {
    this.lastAttemptedAt = attemptedAt;
    this.lastFailureCategory = failureCategory;
  }

  public Supplier getSupplier() {
    return supplier;
  }

  public Instant getLastAttemptedAt() {
    return lastAttemptedAt;
  }

  public Instant getLastSucceededAt() {
    return lastSucceededAt;
  }

  public String getLastFailureCategory() {
    return lastFailureCategory;
  }
}
