package com.DeliveryInventoryService.DeliveryInventoryService.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel.ParcelStatus;

import java.util.UUID;
import java.util.List;
import java.util.Optional;

@Repository

public interface ParcelRepository extends JpaRepository<Parcel, UUID> {
    Optional<Parcel> findByOrderId(UUID orderId);

    List<Parcel> findByStatus(ParcelStatus status);

    List<Parcel> findByCurrentWarehouseIdAndStatus(UUID warehouseId, ParcelStatus status);

    List<Parcel> findByCurrentWarehouseId(UUID warehouseId);

    List<Parcel> findByOriginWarehouseId(UUID warehouseId);

    List<Parcel> findByPickupRiderIdAndStatus(UUID riderId, ParcelStatus status);

    List<Parcel> findByDeliveryRiderIdAndStatus(UUID riderId, ParcelStatus status);

    /**
     * Finds parcels that are physically at this warehouse and ready for their next
     * shipment leg. Uses currentWarehouseId so both:
     *   - parcels arriving here from a seller (originWarehouseId == warehouseId), and
     *   - parcels in transit whose NEXT hop starts here (intermediate hub scenario)
     * are included. shipment IS NULL guards against double-booking.
     */
    @Query("SELECT p FROM Parcel p WHERE p.shipment IS NULL AND p.status = 'AT_WAREHOUSE' AND p.currentWarehouseId = :warehouseId")
    List<Parcel> findUnshippedParcels(@Param("warehouseId") UUID warehouseId);

    @Query("SELECT p FROM Parcel p WHERE p.shipment.id = :shipmentId")
    List<Parcel> findByShipmentId(@Param("shipmentId") UUID shipmentId);

    @Query("SELECT COUNT(p) FROM Parcel p WHERE p.currentWarehouseId = :warehouseId AND p.status = :status")
    long countByWarehouseAndStatus(@Param("warehouseId") UUID warehouseId, @Param("status") ParcelStatus status);
}
