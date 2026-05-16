package com.DeliveryInventoryService.DeliveryInventoryService.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Shipment;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Shipment.ShipmentStatus;

import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Repository
public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {
        List<Shipment> findByStatus(ShipmentStatus status);

        Optional<Shipment> findByShipmentNo(String shipmentNo);

        List<Shipment> findByOriginWarehouseId(UUID warehouseId);

        List<Shipment> findByDestinationWarehouseId(UUID warehouseId);

        List<Shipment> findByOriginWarehouseIdAndStatus(UUID warehouseId, ShipmentStatus status);

        List<Shipment> findByDestinationWarehouseIdAndStatus(UUID warehouseId, ShipmentStatus status);

        /**
         * Find open shipments at a warehouse that still have capacity
         * (fewer than 50 parcels, status CREATED or ASSIGNED).
         */
        @Query("""
                            SELECT s FROM Shipment s
                            WHERE s.originWarehouseId = :warehouseId
                              AND s.status IN ('CREATED', 'ASSIGNED')
                              AND s.destinationWarehouseId = :destWarehouseId
                            ORDER BY s.createdAt ASC
                        """)
        List<Shipment> findOpenShipments(
                        @Param("warehouseId") UUID warehouseId,
                        @Param("destWarehouseId") UUID destWarehouseId);

        /** All shipments assigned to a specific rider that are in one of the given statuses. */
        List<Shipment> findByRiderIdAndStatusInOrderByCreatedAtDesc(
                        UUID riderId,
                        List<ShipmentStatus> statuses);
}
