package io.github.samuel426.lodginghub.catalog.repository;

import io.github.samuel426.lodginghub.catalog.entity.SupplierCatalogSyncState;
import io.github.samuel426.lodginghub.catalog.entity.SupplierRoomTypeMapping;
import io.github.samuel426.lodginghub.catalog.entity.SupplierStayMapping;
import io.github.samuel426.lodginghub.supplier.model.Supplier;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {
  private final EntityManager entityManager;

  public CatalogRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  public List<SupplierStayMapping> findStays(Supplier supplier, boolean activeOnly) {
    return entityManager
        .createQuery(
            """
        select m from SupplierStayMapping m join fetch m.stay
        where m.supplier = :supplier and (:activeOnly = false or m.isListingActive = true)
        """,
            SupplierStayMapping.class)
        .setParameter("supplier", supplier)
        .setParameter("activeOnly", activeOnly)
        .getResultList();
  }

  public List<SupplierRoomTypeMapping> findRooms(Supplier supplier, boolean activeOnly) {
    return entityManager
        .createQuery(
            """
        select m from SupplierRoomTypeMapping m
        join fetch m.stayMapping sm join fetch sm.stay
        join fetch m.roomType r join fetch r.stay
        where sm.supplier = :supplier
        and (:activeOnly = false or (m.isListingActive = true and sm.isListingActive = true))
        """,
            SupplierRoomTypeMapping.class)
        .setParameter("supplier", supplier)
        .setParameter("activeOnly", activeOnly)
        .getResultList();
  }

  public SupplierCatalogSyncState findState(Supplier supplier) {
    return entityManager.find(SupplierCatalogSyncState.class, supplier);
  }

  public void persist(Object entity) {
    entityManager.persist(entity);
  }

  public void flush() {
    entityManager.flush();
  }
}
