package io.github.samuel426.lodginghub.catalog.service;

import io.github.samuel426.lodginghub.catalog.entity.RoomType;
import io.github.samuel426.lodginghub.catalog.entity.Stay;
import io.github.samuel426.lodginghub.catalog.entity.SupplierCatalogSyncState;
import io.github.samuel426.lodginghub.catalog.entity.SupplierRoomTypeMapping;
import io.github.samuel426.lodginghub.catalog.entity.SupplierStayMapping;
import io.github.samuel426.lodginghub.catalog.repository.CatalogRepository;
import io.github.samuel426.lodginghub.supplier.model.Supplier;
import io.github.samuel426.lodginghub.supplier.model.SupplierCatalog;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogPersistenceService {
  private final CatalogRepository repository;
  private final Clock clock;

  public CatalogPersistenceService(CatalogRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void apply(Supplier supplier, SupplierCatalog catalog, Instant attemptedAt) {
    Instant now = clock.instant();
    var stays =
        repository.findStays(supplier, false).stream()
            .collect(
                Collectors.toMap(SupplierStayMapping::getExternalStayCode, Function.identity()));
    var rooms =
        repository.findRooms(supplier, false).stream()
            .collect(Collectors.toMap(CatalogPersistenceService::key, Function.identity()));

    // Inactive mappings remain addressable by their original external keys.
    stays.values().forEach(mapping -> mapping.synchronize(false, now));
    rooms.values().forEach(mapping -> mapping.synchronize(false, now));
    for (var stay : catalog.stays()) {
      var mapping = stays.get(stay.externalCode());
      if (mapping == null) {
        var entity = new Stay(stay.name(), now);
        repository.persist(entity);
        mapping = new SupplierStayMapping(supplier, stay.externalCode(), entity, now);
        repository.persist(mapping);
      } else {
        mapping.getStay().rename(stay.name(), now);
        mapping.synchronize(true, now);
      }
      synchronizeRooms(mapping, stay, rooms, now);
    }
    SupplierCatalogSyncState state = stateForUpdate(supplier, attemptedAt);
    state.succeed(attemptedAt, now);
    repository.flush();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(Supplier supplier, Instant attemptedAt, String category) {
    stateForUpdate(supplier, attemptedAt).fail(attemptedAt, category);
    repository.flush();
  }

  private SupplierCatalogSyncState stateForUpdate(Supplier supplier, Instant attemptedAt) {
    var state = repository.findState(supplier);
    if (state == null) {
      state = new SupplierCatalogSyncState(supplier, attemptedAt);
      // Assigned identifiers use persist, not merge, inside this transaction.
      repository.persist(state);
    }
    return state;
  }

  private void synchronizeRooms(
      SupplierStayMapping stayMapping,
      SupplierCatalog.CatalogStay stay,
      Map<RoomKey, SupplierRoomTypeMapping> rooms,
      Instant now) {
    for (var room : stay.rooms()) {
      var mapping = rooms.get(new RoomKey(stay.externalCode(), room.externalCode()));
      if (mapping == null) {
        var entity = new RoomType(stayMapping.getStay(), room.name(), room.maxOccupancy(), now);
        repository.persist(entity);
        repository.persist(
            new SupplierRoomTypeMapping(stayMapping, room.externalCode(), entity, now));
      } else {
        if (!mapping.getRoomType().getStay().getId().equals(stayMapping.getStay().getId())) {
          throw new IllegalStateException("Catalog mapping ownership is inconsistent");
        }
        mapping.getRoomType().updateDetails(room.name(), room.maxOccupancy(), now);
        mapping.synchronize(true, now);
      }
    }
  }

  private static RoomKey key(SupplierRoomTypeMapping mapping) {
    return new RoomKey(
        mapping.getStayMapping().getExternalStayCode(), mapping.getExternalRoomTypeCode());
  }

  private record RoomKey(String stayCode, String roomCode) {}
}
