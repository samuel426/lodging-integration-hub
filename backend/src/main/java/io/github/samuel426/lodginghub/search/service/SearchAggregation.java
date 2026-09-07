package io.github.samuel426.lodginghub.search.service;

import io.github.samuel426.lodginghub.catalog.dto.CatalogSnapshot;
import io.github.samuel426.lodginghub.catalog.dto.CatalogSnapshot.RoomView;
import io.github.samuel426.lodginghub.catalog.dto.CatalogSnapshot.StayView;
import io.github.samuel426.lodginghub.search.dto.SearchResponse;
import io.github.samuel426.lodginghub.search.dto.SearchResponse.*;
import io.github.samuel426.lodginghub.supplier.model.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Request-local aggregation performed after asynchronous calls complete, on one thread. */
final class SearchAggregation {
  private final CatalogSnapshot snapshot;
  private final AvailabilityCondition condition;
  private final Map<MappingKey, MappedRoom> mappings = new HashMap<>();
  private final Map<FailureKey, Integer> failures = new HashMap<>();
  private final List<StayOfferResponse> offers = new ArrayList<>();
  private int rejected;
  private int validOffers;
  private int emptyBatches;
  private int emptyCatalogs;
  private int invalidOffers;
  private int missingMappings;
  private boolean hasObservation;
  private boolean hasMappingFailure;
  private boolean hasInvalidData;

  SearchAggregation(CatalogSnapshot snapshot, AvailabilityCondition condition) {
    this.snapshot = snapshot;
    this.condition = condition;
    for (var catalog : snapshot.suppliers()) {
      if (!catalog.isReady()) {
        continue;
      }
      hasObservation |= catalog.stays().isEmpty();
      if (catalog.stays().isEmpty()) {
        emptyCatalogs++;
      }
      for (var stay : catalog.stays()) {
        for (var room : stay.rooms()) {
          mappings.put(
              new MappingKey(catalog.supplier(), stay.externalCode(), room.externalCode()),
              new MappedRoom(stay, room));
        }
      }
    }
  }

  void accept(Supplier supplier, SupplierBatchOutcome outcome) {
    if (outcome.failure() != null) {
      failures.merge(new FailureKey(supplier, outcome.failure()), 1, Integer::sum);
      hasInvalidData |= outcome.failure() == SupplierFailureCategory.INVALID_RESPONSE;
      return;
    }
    rejected += outcome.rejectedOfferCount();
    invalidOffers += outcome.rejectedOfferCount();
    hasInvalidData |= outcome.rejectedOfferCount() > 0;
    hasObservation |= outcome.isValidatedEmptyBatch();
    if (outcome.isValidatedEmptyBatch()) {
      emptyBatches++;
    }
    for (var offer : outcome.validOffers()) {
      if (offer.supplier() != supplier) {
        throw new IllegalStateException("Adapter supplier mismatch");
      }
      var mapping =
          mappings.get(
              new MappingKey(supplier, offer.externalStayCode(), offer.externalRoomTypeCode()));
      if (mapping == null) {
        rejected++;
        missingMappings++;
        hasMappingFailure = true;
        continue;
      }
      hasObservation = true;
      validOffers++;
      if (offer.availableRoomCount() == 0 || condition.guests() > offer.maxOccupancy()) {
        continue;
      }
      offers.add(
          new StayOfferResponse(
              mapping.stay().stayId(),
              mapping.stay().name(),
              mapping.room().roomTypeId(),
              mapping.room().name(),
              offer.maxOccupancy(),
              offer.availableRoomCount(),
              supplier,
              offer.breakfastIncluded(),
              offer.price()));
    }
  }

  SearchResponse finish() {
    if (!hasObservation) {
      boolean hasConfigurationFailure =
          failures.keySet().stream()
              .anyMatch(
                  key ->
                      key.category() == SupplierFailureCategory.AUTHENTICATION_ERROR
                          || key.category() == SupplierFailureCategory.INVALID_REQUEST);
      if (hasConfigurationFailure) {
        throw new SearchException(SearchFailure.INTEGRATION_CONFIGURATION_ERROR);
      }
      if (hasMappingFailure) {
        throw new SearchException(SearchFailure.CATALOG_MAPPING_UNAVAILABLE);
      }
      if (hasInvalidData) {
        throw new SearchException(SearchFailure.NO_VALID_SUPPLIER_DATA);
      }
      throw new SearchException(SearchFailure.ALL_SUPPLIERS_UNAVAILABLE);
    }
    var summary =
        failures.entrySet().stream()
            .sorted(
                Map.Entry.comparingByKey(
                    Comparator.comparing(FailureKey::supplier).thenComparing(FailureKey::category)))
            .map(
                entry ->
                    new SupplierFailureResponse(
                        entry.getKey().supplier(), entry.getKey().category(), entry.getValue()))
            .toList();
    var unavailable = snapshot.unavailableSuppliers().stream().sorted().toList();
    offers.sort(
        Comparator.comparing(StayOfferResponse::supplier)
            .thenComparing(StayOfferResponse::stayId)
            .thenComparing(StayOfferResponse::roomTypeId));
    return new SearchResponse(
        new SearchData(offers),
        new SearchMeta(
            rejected > 0 || !summary.isEmpty() || !unavailable.isEmpty(),
            rejected,
            unavailable,
            summary));
  }

  private record MappingKey(Supplier supplier, String stayCode, String roomCode) {}

  ObservationCounts counts() {
    return new ObservationCounts(
        validOffers, emptyBatches, emptyCatalogs, invalidOffers, missingMappings);
  }

  record ObservationCounts(
      int validOffers,
      int emptyBatches,
      int emptyCatalogs,
      int invalidOffers,
      int missingMappings) {}

  private record MappedRoom(StayView stay, RoomView room) {}

  private record FailureKey(Supplier supplier, SupplierFailureCategory category) {}
}
