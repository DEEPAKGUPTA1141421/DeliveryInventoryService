package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import java.time.ZonedDateTime;
import java.util.List;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.VehicleSchedule;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.VehicleScheduleRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.GeoUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class EtaCalculationService {
    private final VehicleScheduleRepository scheduleRepository;
    private final WarehouseRepository warehouseRepository;
    private final RedisTemplate<String, Object> etaRedisTemplate;

    /**
     * Full ETA = Seg1 (seller→originWH) + intercity + Seg3 (destWH→user)
     *
     * All times derived from VehicleSchedule startTime/endTime.
     * No OSRM call for time prediction.
     */
    /**
     * Estimate travel time (seconds) from a vehicle's home city to a lat/lng.
     * Uses Haversine distance + vehicle type average speed.
     * No OSRM — this is your own fleet, you know the average speed.
     */
    private long estimateTravelSeconds(
            VehicleSchedule schedule, double destLat, double destLng, String city) {

        Warehouse nearestWH = warehouseRepository.findByCity(city)
                .stream().findFirst().orElse(null);
        if (nearestWH == null)
            return 30 * 60L; // default 30 min

        double distKm = GeoUtils.distanceKm(
                nearestWH.getLat(), nearestWH.getLng(), destLat, destLng);

        // Average speed by vehicle type (kph) — from your own SLA data
        double speedKph = getAverageSpeedKph(schedule);
        return Math.round((distKm / speedKph) * 3600);
    }

    private double getAverageSpeedKph(VehicleSchedule schedule) {
        // You query the vehicle type from Vehicle table via vehicleId
        // Simplified defaults per vehicle category:
        return 25.0; // auto/bike default; override with Vehicle.VehicleType lookup
    }

    public enum SegmentType {
        FIRST_MILE, INTER_CITY, LAST_MILE
    }

    public record SegmentEta(
            SegmentType type,
            VehicleSchedule vehicle,
            ZonedDateTime vehicleDeparture,
            ZonedDateTime vehicleArrivesAtSeller, // null for inter-city/last-mile
            ZonedDateTime vehicleArrivesAtDestination,
            boolean isDelayed) {
    }

    public record FullEtaResult(
            boolean available,
            SegmentEta segment1,
            SegmentEta segment2,
            SegmentEta segment3,
            ZonedDateTime requestedAt,
            ZonedDateTime estimatedDelivery,
            String unavailableReason) {
        static FullEtaResult unavailable(String reason) {
            return new FullEtaResult(false, null, null, null, null, null, reason);
        }
    }
}
