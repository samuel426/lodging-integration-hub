package io.github.samuel426.lodginghub.supplier.model;

/** Safe failure classification. Never retains upstream bodies, URLs or credentials. */
public final class SupplierCallException extends RuntimeException {
  @java.io.Serial private static final long serialVersionUID = 1L;
  private final SupplierFailureCategory failureCategory;

  public SupplierCallException(SupplierFailureCategory category) {
    super("Supplier call failed: " + category);
    this.failureCategory = category;
  }

  public SupplierFailureCategory category() {
    return failureCategory;
  }

  public static SupplierCallException invalidResponse() {
    return new SupplierCallException(SupplierFailureCategory.INVALID_RESPONSE);
  }
}
