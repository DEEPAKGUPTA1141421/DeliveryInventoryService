package com.DeliveryInventoryService.DeliveryInventoryService.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.LocationEvent;

import java.util.UUID;
import java.util.List;

@Repository
public interface LocationEventRepository extends JpaRepository<LocationEvent, UUID> {
    List<LocationEvent> findTop100ByVehicleIdOrderByTimestampMsDesc(UUID vehicleId);
}
