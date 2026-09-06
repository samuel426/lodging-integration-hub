package io.github.samuel426.lodginghub.supplier.model;

/** Validated data, before catalog lookup and business filtering. */
public record SupplierOffer(
    Supplier supplier,
    String externalStayCode,
    String externalRoomTypeCode,
    String stayName,
    String roomTypeName,
    int maxOccupancy,
    boolean breakfastIncluded,
    int availableRoomCount,
    PriceSummary price) {}
