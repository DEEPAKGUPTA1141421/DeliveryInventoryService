package com.DeliveryInventoryService.DeliveryInventoryService.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.VehicleSchedule;

import java.util.UUID;
import java.util.List;

@Repository

public interface VehicleScheduleRepository extends JpaRepository<VehicleSchedule, UUID> {
    List<VehicleSchedule> findByDepartureDateTimeBetween(java.time.ZonedDateTime start, java.time.ZonedDateTime end);
}
