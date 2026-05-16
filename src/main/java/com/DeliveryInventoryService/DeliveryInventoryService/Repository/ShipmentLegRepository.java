package com.DeliveryInventoryService.DeliveryInventoryService.Repository;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.ShipmentLeg;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.ShipmentLeg.ShipmentLegStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ShipmentLegRepository extends JpaRepository<ShipmentLeg, UUID> {

    List<ShipmentLeg> findByShipmentIdOrderBySequenceAsc(UUID shipmentId);

    Optional<ShipmentLeg> findFirstByShipmentIdAndStatus(UUID shipmentId, ShipmentLegStatus status);
}
