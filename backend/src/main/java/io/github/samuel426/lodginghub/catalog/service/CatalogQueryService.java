package io.github.samuel426.lodginghub.catalog.service;

import io.github.samuel426.lodginghub.catalog.dto.CatalogSnapshot;
import io.github.samuel426.lodginghub.catalog.dto.CatalogSnapshot.RoomView;
import io.github.samuel426.lodginghub.catalog.dto.CatalogSnapshot.StayView;
import io.github.samuel426.lodginghub.catalog.dto.CatalogSnapshot.SupplierCatalogView;
import io.github.samuel426.lodginghub.catalog.repository.CatalogRepository;
import io.github.samuel426.lodginghub.supplier.client.SupplierCatalogClient;
import io.github.samuel426.lodginghub.supplier.model.Supplier;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogQueryService {
  private final CatalogRepository repository;
  private final List<Supplier> suppliers;

  public CatalogQueryService(CatalogRepository repository, List<SupplierCatalogClient> clients) {
    this.repository = repository;
    this.suppliers = clients.stream().map(SupplierCatalogClient::supplier).sorted().toList();
  }

  // All mapping/state queries must observe one database snapshot during a refresh.
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public CatalogSnapshot snapshot() {
    return new CatalogSnapshot(suppliers.stream().map(this::supplierView).toList());
  }

  private SupplierCatalogView supplierView(Supplier supplier) {
    var state = repository.findState(supplier);
    if (state == null || state.getLastSucceededAt() == null) {
      return new SupplierCatalogView(
          supplier,
          state == null ? null : state.getLastAttemptedAt(),
          null,
          state == null ? null : state.getLastFailureCategory(),
          List.of());
    }
    var rooms =
        repository.findRooms(supplier, true).stream()
            .collect(
                Collectors.groupingBy(
                    mapping -> mapping.getStayMapping().getId(),
                    Collectors.mapping(
                        mapping ->
                            new RoomView(
                                mapping.getRoomType().getId(),
                                mapping.getExternalRoomTypeCode(),
                                mapping.getRoomType().getName(),
                                mapping.getRoomType().getMaxOccupancy()),
                        Collectors.toList())));
    var stays =
        repository.findStays(supplier, true).stream()
            .map(
                mapping ->
                    new StayView(
                        mapping.getStay().getId(),
                        mapping.getExternalStayCode(),
                        mapping.getStay().getName(),
                        rooms.getOrDefault(mapping.getId(), List.of())))
            .toList();
    return new SupplierCatalogView(
        supplier,
        state.getLastAttemptedAt(),
        state.getLastSucceededAt(),
        state.getLastFailureCategory(),
        stays);
  }
}
