package io.github.samuel426.lodginghub.supplier.mapper;

/** Expected external data validation failure; never stores raw field values. */
public final class InvalidSupplierOfferException extends RuntimeException {
  @java.io.Serial private static final long serialVersionUID = 1L;

  public InvalidSupplierOfferException() {
    super("Invalid supplier offer");
  }
}
