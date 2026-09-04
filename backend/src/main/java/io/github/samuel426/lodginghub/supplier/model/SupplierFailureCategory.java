package io.github.samuel426.lodginghub.supplier.model;

public enum SupplierFailureCategory {
  TIMEOUT,
  CONNECTION_ERROR,
  RATE_LIMITED,
  AUTHENTICATION_ERROR,
  INVALID_REQUEST,
  INVALID_RESPONSE,
  UPSTREAM_ERROR
}
