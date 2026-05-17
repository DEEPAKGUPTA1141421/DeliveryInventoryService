package com.DeliveryInventoryService.DeliveryInventoryService.Utils.cronjob;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.CityRouteEtaEntry;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel.ParcelStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Shipment;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Shipment.ShipmentStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Vehicle;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Vehicle.VehicleStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.VehicleSchedule;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ParcelRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ShipmentRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.VehicleRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.VehicleScheduleRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.InterCityRouteService;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.ShipmentService;
import com.DeliveryInventoryService.DeliveryInventoryService.kafka.ParcelLifecycleEvent;
import com.DeliveryInventoryService.DeliveryInventoryService.kafka.ParcelLifecycleProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ShipmentAutoCreationJob
 * ───────────────────────
 *
 * Runs on a configurable cron schedule (default: every 30 minutes).
 * For every ACTIVE warehouse it:
 *
 * 1. Finds all AT_WAREHOUSE parcels that have no shipment assigned yet
 * (findUnshippedParcels uses currentWarehouseId so intermediate-hub
 * parcels are also picked up for their next leg).
 *
 * 2. Groups them by destinationWarehouseId (the parcel's FINAL destination).
 * If a direct vehicle route exists to the final dest, we ship directly.
 * If not, the shipment is created without ETA and vehicle assignment
 * so the warehouse operator can manually assign later.
 *
 * 3. Bin-packs each group using First-Fit Decreasing against the warehouse's
 * AVAILABLE vehicle fleet. One bin = one Shipment.
 *
 * 4. Persists Shipment + updates Parcel.status = IN_SHIPMENT + publishes
 * ParcelLifecycleEvent(ASSIGNED_TO_SHIPMENT) to Kafka per parcel.
 *
 * 5. Marks used vehicles ASSIGNED.
 *
 * Each warehouse is processed in its own DB transaction so a failure in one
 * does not roll back work done for others.
 *
 * Cron is configurable via:
 * myapp.cron.shipment-auto-creation=0 * /30 * * * *
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ShipmentAutoCreationJob {

    private static final String ZONE = "Asia/Kolkata";
    private static final long DEPARTURE_LAG_MINUTES = 30;
    private static final String PARCEL_STATUS_KEY = "parcel:status:";

    private final WarehouseRepository warehouseRepository;
    private final ParcelRepository parcelRepository;
    private final ShipmentRepository shipmentRepository;
    private final VehicleRepository vehicleRepository;
    private final VehicleScheduleRepository vehicleScheduleRepository;
    private final InterCityRouteService interCityRouteService;
    private final ShipmentService shipmentService;
    private final ParcelLifecycleProducer lifecycleProducer;
    private final RedisTemplate<String, Object> redisTemplate;
    private final TransactionTemplate txTemplate;

    // ── Entry point ───────────────────────────────────────────────────────────

    @Scheduled(cron = "${myapp.cron.shipment-auto-creation:0 */30 * * * *}")
    public void run() {
        log.info("[AutoShipment] Cron fired — scanning all active warehouses");

        List<Warehouse> active = warehouseRepository.findByStatus(Warehouse.WarehouseStatus.ACTIVE);
        int totalCreated = 0;

        for (Warehouse warehouse : active) {
            try {
                Integer created = txTemplate.execute(status -> processWarehouse(warehouse));
                totalCreated += (created != null ? created : 0);
            } catch (Exception ex) {
                log.error("[AutoShipment] warehouse={} failed: {}", warehouse.getId(), ex.getMessage(), ex);
            }
        }

        log.info("[AutoShipment] Done — {} shipment(s) created across {} warehouse(s)",
                totalCreated, active.size());
    }

    // ── Per-warehouse logic (runs inside its own transaction) ─────────────────

    private int processWarehouse(Warehouse warehouse) {
        UUID warehouseId = warehouse.getId();

        List<Parcel> unshipped = parcelRepository.findUnshippedParcels(warehouseId);
        if (unshipped.isEmpty()) {
            log.debug("[AutoShipment] warehouse={} city={} — no unshipped parcels",
                    warehouseId, warehouse.getCity());
            return 0;
        }

        log.info("[AutoShipment] warehouse={} city={} — {} unshipped parcel(s) found",
                warehouseId, warehouse.getCity(), unshipped.size());

        // Group by final destination warehouse
        Map<UUID, List<Parcel>> byDest = unshipped.stream()
                .filter(p -> p.getDestinationWarehouseId() != null)
                .collect(Collectors.groupingBy(Parcel::getDestinationWarehouseId));

        // Mutable list so we can remove vehicles as they are claimed
        List<Vehicle> availableVehicles = new ArrayList<>(
                vehicleRepository.findByHomeWarehouseIdAndStatus(warehouseId, VehicleStatus.AVAILABLE));
        availableVehicles.sort(Comparator.comparingDouble(Vehicle::getCapacityKg));

        int created = 0;

        for (Map.Entry<UUID, List<Parcel>> entry : byDest.entrySet()) {
            UUID destId = entry.getKey();
            List<Parcel> parcels = entry.getValue();

            Warehouse dest = warehouseRepository.findById(destId).orElse(null);
            if (dest == null) {
                log.warn("[AutoShipment] Destination warehouse {} not found — skipping {} parcel(s)",
                        destId, parcels.size());
                continue;
            }

            // Resolve inter-city ETA (from Redis cache; falls back to live computation)
            CityRouteEtaEntry eta = resolveEta(warehouse.getCity(), dest.getCity());

            // Bin-pack into vehicle-sized loads
            List<List<Parcel>> bins = binPack(parcels, availableVehicles);

            for (List<Parcel> bin : bins) {
                double binWeight = bin.stream().mapToDouble(Parcel::getWeightKg).sum();
                Vehicle vehicle = pickVehicle(binWeight, availableVehicles);

                VehicleSchedule schedule = createSchedule(vehicle, warehouse, dest, binWeight, bin.size(), eta);
                Shipment shipment = buildShipment(warehouse, dest, schedule, eta);
                shipment = shipmentRepository.save(shipment);
                shipmentService.createShipmentLegs(shipment, warehouse, dest, eta);

                for (Parcel p : bin) {
                    p.setShipment(shipment);
                    p.setStatus(ParcelStatus.IN_SHIPMENT);
                    parcelRepository.save(p);
                    redisTemplate.opsForValue().set(PARCEL_STATUS_KEY + p.getId(),
                            ParcelStatus.IN_SHIPMENT.name());
                    publishAssigned(p, shipment);
                }

                if (vehicle != null) {
                    vehicle.setStatus(VehicleStatus.ASSIGNED);
                    vehicleRepository.save(vehicle);
                    availableVehicles.remove(vehicle); // don't reuse in subsequent bins
                }

                log.info("[AutoShipment] {} → {} | shipment={} | {} parcel(s) | {}kg | vehicle={}",
                        warehouse.getCity(), dest.getCity(),
                        shipment.getShipmentNo(), bin.size(), String.format("%.1f", binWeight),
                        vehicle != null ? vehicle.getVehicleNumber() : "none");

                created++;
            }
        }

        return created;
    }

    // ── VehicleSchedule creator ───────────────────────────────────────────────

    private VehicleSchedule createSchedule(Vehicle vehicle, Warehouse origin, Warehouse dest,
            double loadKg, int parcelCount, CityRouteEtaEntry eta) {
        if (vehicle == null) return null;

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(ZONE));
        ZonedDateTime departure = now.plusMinutes(DEPARTURE_LAG_MINUTES);
        ZonedDateTime arrival = eta != null
                ? departure.plusSeconds(eta.getTotalTravelTimeSeconds())
                : departure.plusHours(8);

        VehicleSchedule schedule = new VehicleSchedule();
        schedule.setVehicleId(vehicle.getId());
        schedule.setScheduleType(VehicleSchedule.ScheduleType.ON_DEMAND);
        schedule.setOriginCity(origin.getCity());
        schedule.setDestinationCity(dest.getCity());
        schedule.setOriginLat(origin.getLat());
        schedule.setOriginLng(origin.getLng());
        schedule.setDestLat(dest.getLat());
        schedule.setDestLng(dest.getLng());
        schedule.setDepartureDateTime(departure);
        schedule.setArrivalDateTime(arrival);
        schedule.setCapacityRemainingKg(vehicle.getCapacityKg() - loadKg);
        schedule.setCapacityRemainingParcels(0);
        schedule.setStatus(VehicleSchedule.ScheduleStatus.SCHEDULED);
        return vehicleScheduleRepository.save(schedule);
    }

    // ── Shipment builder ──────────────────────────────────────────────────────

    private Shipment buildShipment(Warehouse origin, Warehouse dest,
            VehicleSchedule schedule, CityRouteEtaEntry eta) {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(ZONE));
        ZonedDateTime departure = schedule != null ? schedule.getDepartureDateTime() : now.plusMinutes(DEPARTURE_LAG_MINUTES);
        ZonedDateTime arrival = schedule != null ? schedule.getArrivalDateTime()
                : (eta != null ? now.plusMinutes(DEPARTURE_LAG_MINUTES).plusSeconds(eta.getTotalTravelTimeSeconds())
                        : now.plusMinutes(DEPARTURE_LAG_MINUTES).plusHours(8));

        String shipmentType = origin.getCity().equalsIgnoreCase(dest.getCity()) ? "LAST_MILE" : "INTER_HUB";

        Shipment s = new Shipment();
        s.setShipmentNo(Shipment.generateShipmentNo());
        s.setShipmentType(shipmentType);
        s.setOriginWarehouseId(origin.getId());
        s.setDestinationWarehouseId(dest.getId());
        s.setOriginCity(origin.getCity());
        s.setDestinationCity(dest.getCity());
        s.setVehicleScheduleId(schedule != null ? schedule.getId() : null);
        s.setDepartureTimeEst(departure);
        s.setArrivalTimeEst(arrival);
        if (eta != null) {
            s.setTimeEstimateSeconds(eta.getTotalTravelTimeSeconds());
            s.setCostEstimate(eta.getTotalDistanceKm() * 12); // ₹12/km placeholder
        }
        s.setStatus(schedule != null ? ShipmentStatus.ASSIGNED : ShipmentStatus.CREATED);
        return s;
    }

    // ── ETA resolution ────────────────────────────────────────────────────────

    private CityRouteEtaEntry resolveEta(String origin, String dest) {
        if (origin.equalsIgnoreCase(dest))
            return null;
        CityRouteEtaEntry cached = interCityRouteService.getCached(origin, dest);
        if (cached != null)
            return cached;
        try {
            return interCityRouteService.computeAndCache(origin, dest);
        } catch (Exception e) {
            log.warn("[AutoShipment] ETA computation failed {} → {}: {}", origin, dest, e.getMessage());
            return null;
        }
    }

    // ── First-Fit Decreasing bin-packing ─────────────────────────────────────
    //
    // Sorts parcels heaviest-first, then places each into the first bin that
    // can still accommodate it without exceeding the largest available vehicle's
    // capacity. Parcels that exceed every vehicle are placed in their own
    // oversized bin so nothing is silently dropped.

    private List<List<Parcel>> binPack(List<Parcel> parcels, List<Vehicle> vehicles) {
        double maxCapacity = vehicles.stream()
                .mapToDouble(Vehicle::getCapacityKg)
                .max()
                .orElse(Double.MAX_VALUE);

        List<Parcel> sorted = parcels.stream()
                .sorted(Comparator.comparingDouble(Parcel::getWeightKg).reversed())
                .collect(Collectors.toList());

        List<List<Parcel>> bins = new ArrayList<>();
        List<Double> totals = new ArrayList<>();

        for (Parcel p : sorted) {
            double w = p.getWeightKg();
            boolean placed = false;
            for (int i = 0; i < bins.size(); i++) {
                if (totals.get(i) + w <= maxCapacity) {
                    bins.get(i).add(p);
                    totals.set(i, totals.get(i) + w);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                List<Parcel> newBin = new ArrayList<>();
                newBin.add(p);
                bins.add(newBin);
                totals.add(w);
            }
        }

        return bins;
    }

    /** Smallest vehicle with capacity >= required, or largest if none fits. */
    private Vehicle pickVehicle(double requiredKg, List<Vehicle> sortedVehicles) {
        if (sortedVehicles.isEmpty())
            return null;
        return sortedVehicles.stream()
                .filter(v -> v.getCapacityKg() >= requiredKg)
                .findFirst()
                .orElse(sortedVehicles.get(sortedVehicles.size() - 1));
    }

    // ── Kafka event ───────────────────────────────────────────────────────────

    private void publishAssigned(Parcel p, Shipment shipment) {
        try {
            lifecycleProducer.publish(ParcelLifecycleEvent.builder()
                    .parcelId(p.getId())
                    .orderId(p.getOrderId())
                    .eventType(ParcelLifecycleEvent.EventType.ASSIGNED_TO_SHIPMENT)
                    .newStatus(ParcelStatus.IN_SHIPMENT)
                    .warehouseId(shipment.getOriginWarehouseId())
                    .shipmentId(shipment.getId())
                    .shipmentNo(shipment.getShipmentNo())
                    .occurredAt(ZonedDateTime.now(ZoneId.of(ZONE)))
                    .notes("Auto-created by ShipmentAutoCreationJob")
                    .build());
        } catch (Exception e) {
            log.warn("[AutoShipment] Failed to publish event for parcel {}: {}", p.getId(), e.getMessage());
        }
    }
}
