package com.DeliveryInventoryService.DeliveryInventoryService.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.VehicleSchedule;

import java.util.UUID;
import java.time.ZonedDateTime;
import java.util.List;

@Repository

public interface VehicleScheduleRepository extends JpaRepository<VehicleSchedule, UUID> {
    List<VehicleSchedule> findByDepartureDateTimeBetween(java.time.ZonedDateTime start, java.time.ZonedDateTime end);

    List<VehicleSchedule> findByOriginCityAndScheduleTypeAndDepartureDateTimeAfterAndStatusOrderByDepartureDateTimeAsc(
            String originCity,
            VehicleSchedule.ScheduleType type,
            ZonedDateTime after,
            VehicleSchedule.ScheduleStatus status);

    // Find available ON_DEMAND vehicles in a city
    @Query("""
                SELECT vs FROM VehicleSchedule vs
                JOIN Vehicle v ON v.id = vs.vehicleId
                WHERE vs.originCity = :city
                  AND vs.scheduleType = 'ON_DEMAND'
                  AND vs.status = 'SCHEDULED'
                  AND vs.capacityRemainingKg >= :requiredKg
                ORDER BY vs.departureDateTime ASC
            """)
    List<VehicleSchedule> findAvailableOnDemandInCity(
            @Param("city") String city,
            @Param("requiredKg") double requiredKg);

}
