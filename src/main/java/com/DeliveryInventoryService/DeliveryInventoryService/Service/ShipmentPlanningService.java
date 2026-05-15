package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.CityRouteEtaEntry;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ShipmentPlanDTO.*;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.WarehouseDTO.ShipmentResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel.ParcelStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Shipment;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Shipment.ShipmentStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Vehicle;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Vehicle.VehicleStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ParcelRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ShipmentRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.VehicleRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.kafka.ParcelLifecycleEvent;
import com.DeliveryInventoryService.DeliveryInventoryService.kafka.ParcelLifecycleProducer;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ShipmentPlanningService
 * ────────────────────────
 *
 * Two-phase planning pipeline:
 *
 * Phase 1 – generatePlan(warehouseId):
 * Reads all AT_WAREHOUSE unshipped parcels, groups by destinationWarehouseId,
 * determines LAST_MILE (same city) vs INTER_HUB (different city), fetches
 * inter-city ETA from Redis (via InterCityRouteService.getCached), applies
 * First-Fit-Decreasing bin-packing against available vehicle capacities, and
 * caches the resulting ShipmentPlan in Redis for 15 minutes.
 *
 * Phase 2 – executePlan(warehouseId, planId, req):
 * Validates each group's parcels are still AT_WAREHOUSE, creates Shipment
 * records, attaches parcels (→ IN_SHIPMENT), and returns the execution summary.
 *
 * Redis keys:
 * shipment:plan:{planId} – cached ShipmentPlan (15 min TTL)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShipmentPlanningService {

    private static final String ZONE = "Asia/Kolkata";
    private static final long PLAN_TTL_MINUTES = 15;
    private static final String PLAN_KEY_PREFIX = "shipment:plan:";
    private static final String PARCEL_STATUS_PREFIX = "parcel:status:";

    private final ParcelRepository parcelRepository;
    private final ShipmentRepository shipmentRepository;
    private final VehicleRepository vehicleRepository;
    private final WarehouseRepository warehouseRepository;
    private final InterCityRouteService interCityRouteService;
    private final ShipmentService shipmentService;
    private final ParcelLifecycleProducer lifecycleProducer;
    private final RedisTemplate<String, Object> etaRedisTemplate;
    private final ObjectMapper objectMapper;

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 1 — generate (read-only, cached)
    // ─────────────────────────────────────────────────────────────────────────

    public ApiResponse<Object> generatePlan(UUID warehouseId) {
        if (warehouseId == null) {
            return new ApiResponse<>(false, "warehouseId required", null, 400);
        }
        Warehouse origin = warehouseRepository.findById(warehouseId).orElse(null);
        if (origin == null) {
            return new ApiResponse<>(false, "Warehouse not found", null, 404);
        }

        List<Parcel> unshipped = parcelRepository.findUnshippedParcels(warehouseId);
        if (unshipped.isEmpty()) {
            return new ApiResponse<>(false, "No AT_WAREHOUSE unshipped parcels found", null, 404);
        }

        // Group by destination warehouse
        Map<UUID, List<Parcel>> byDest = unshipped.stream()
                .filter(p -> p.getDestinationWarehouseId() != null)
                .collect(Collectors.groupingBy(Parcel::getDestinationWarehouseId));

        // Pre-load available vehicles sorted ascending by capacity (FFD uses sorted
        // bins)
        List<Vehicle> availableVehicles = vehicleRepository
                .findByHomeWarehouseIdAndStatus(Objects.requireNonNull(warehouseId), VehicleStatus.AVAILABLE);
        availableVehicles.sort(Comparator.comparingDouble(Vehicle::getCapacityKg));

        List<PlannedShipmentGroup> groups = new ArrayList<>();
        int groupIndex = 0;

        for (Map.Entry<UUID, List<Parcel>> entry : byDest.entrySet()) {
            UUID destWhId = entry.getKey();
            List<Parcel> parcels = entry.getValue();

            Warehouse dest = warehouseRepository.findById(Objects.requireNonNull(destWhId)).orElse(null);
            if (dest == null) {
                log.warn("Destination warehouse {} not found — skipping {} parcels", destWhId, parcels.size());
                continue;
            }

            String shipmentType = origin.getCity().equalsIgnoreCase(dest.getCity())
                    ? "LAST_MILE"
                    : "INTER_HUB";

            // Resolve ETA from Redis; attempt live computation if missing
            CityRouteEtaEntry eta = resolveEta(origin.getCity(), dest.getCity());
            double distanceKm = eta != null ? eta.getTotalDistanceKm() : 0.0;
            long etaSeconds = eta != null ? eta.getTotalTravelTimeSeconds() : 0L;
            boolean etaFromCache = eta != null;

            // Bin-pack parcels by weight using First-Fit Decreasing
            List<List<Parcel>> bins = binPack(parcels, availableVehicles);

            ZonedDateTime departure = ZonedDateTime.now(ZoneId.of(ZONE)).plusMinutes(30);

            for (List<Parcel> bin : bins) {
                double binWeight = bin.stream().mapToDouble(Parcel::getWeightKg).sum();
                Vehicle suggested = pickVehicle(binWeight, availableVehicles);

                ZonedDateTime arrival = etaSeconds > 0
                        ? departure.plusSeconds(etaSeconds)
                        : departure.plusHours(8);

                groups.add(PlannedShipmentGroup.builder()
                        .groupIndex(groupIndex++)
                        .destinationWarehouseId(destWhId)
                        .destinationWarehouseName(dest.getName())
                        .destinationCity(dest.getCity())
                        .shipmentType(shipmentType)
                        .parcelIds(bin.stream().map(Parcel::getId).collect(Collectors.toList()))
                        .parcelCount(bin.size())
                        .totalWeightKg(binWeight)
                        .distanceKm(distanceKm)
                        .etaSeconds(etaSeconds)
                        .etaFromCache(etaFromCache)
                        .departureEst(departure)
                        .arrivalEst(arrival)
                        .suggestedVehicleId(suggested != null ? suggested.getId() : null)
                        .suggestedVehicleNumber(suggested != null ? suggested.getVehicleNumber() : null)
                        .suggestedVehicleType(suggested != null && suggested.getVehicleType() != null
                                ? suggested.getVehicleType().name()
                                : null)
                        .suggestedVehicleCapacityKg(suggested != null ? suggested.getCapacityKg() : 0.0)
                        .build());
            }
        }

        if (groups.isEmpty()) {
            return new ApiResponse<>(false, "Could not form any shipment groups", null, 400);
        }

        ShipmentPlan plan = ShipmentPlan.builder()
                .planId(UUID.randomUUID().toString())
                .warehouseId(warehouseId)
                .originCity(origin.getCity())
                .generatedAt(ZonedDateTime.now(ZoneId.of(ZONE)))
                .totalParcels(unshipped.size())
                .totalGroups(groups.size())
                .groups(groups)
                .build();

        cachePlan(plan);
        log.info("Shipment plan {} generated for warehouse {}: {} groups, {} parcels",
                plan.getPlanId(), warehouseId, plan.getTotalGroups(), plan.getTotalParcels());

        return new ApiResponse<>(true, "Plan generated", plan, 200);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Phase 2 — execute (writes to DB)
    // ─────────────────────────────────────────────────────────────────────────

    @Transactional
    public ApiResponse<Object> executePlan(UUID warehouseId, String planId, ExecutePlanRequest req) {
        ShipmentPlan plan = loadPlan(planId);
        if (plan == null) {
            return new ApiResponse<>(false, "Plan not found or expired (15-min TTL)", null, 404);
        }
        if (!warehouseId.equals(plan.getWarehouseId())) {
            return new ApiResponse<>(false, "Plan does not belong to this warehouse", null, 403);
        }

        // Index overrides by groupIndex for O(1) lookup
        Map<Integer, ExecuteGroupRequest> overrides = new HashMap<>();
        if (req != null && req.getGroups() != null) {
            req.getGroups().forEach(g -> overrides.put(g.getGroupIndex(), g));
        }

        // If the request specifies which groups to execute, only process those.
        // An empty / null groups list means "execute all" (backward compat for direct API calls).
        final Set<Integer> selectedIndices =
                (req != null && req.getGroups() != null && !req.getGroups().isEmpty())
                        ? req.getGroups().stream()
                                .map(ExecuteGroupRequest::getGroupIndex)
                                .collect(Collectors.toSet())
                        : null; // null → execute all

        int created = 0;
        int skipped = 0;
        List<ShipmentResponse> shipments = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (PlannedShipmentGroup group : plan.getGroups()) {
            if (selectedIndices != null && !selectedIndices.contains(group.getGroupIndex())) {
                continue; // admin deselected this group — skip silently
            }
            try {
                ExecuteGroupRequest override = overrides.get(group.getGroupIndex());

                // Re-validate parcels are still AT_WAREHOUSE and unshipped
                List<UUID> validParcelIds = new ArrayList<>();
                List<String> groupErrors = new ArrayList<>();
                for (UUID pid : group.getParcelIds()) {
                    Parcel p = pid != null ? parcelRepository.findById(pid).orElse(null) : null;
                    if (p == null) {
                        groupErrors.add("Parcel " + pid + " not found");
                        continue;
                    }
                    if (p.getStatus() != ParcelStatus.AT_WAREHOUSE) {
                        groupErrors
                                .add("Parcel " + pid + " is no longer AT_WAREHOUSE (current: " + p.getStatus() + ")");
                        continue;
                    }
                    if (p.getShipment() != null) {
                        groupErrors.add("Parcel " + pid + " already assigned to shipment");
                        continue;
                    }
                    validParcelIds.add(pid);
                }

                if (validParcelIds.isEmpty()) {
                    skipped++;
                    errors.add("Group " + group.getGroupIndex() + " skipped: no valid parcels. " +
                            String.join("; ", groupErrors));
                    continue;
                }
                errors.addAll(groupErrors);

                UUID vehicleId = override != null && override.getVehicleId() != null
                        ? override.getVehicleId()
                        : group.getSuggestedVehicleId();

                ZonedDateTime departure = override != null && override.getDepartureOverride() != null
                        ? override.getDepartureOverride()
                        : group.getDepartureEst();

                ZonedDateTime arrival = group.getArrivalEst();
                if (departure != null && group.getEtaSeconds() > 0) {
                    arrival = departure.plusSeconds(group.getEtaSeconds());
                }

                Shipment shipment = new Shipment();
                shipment.setShipmentNo(Shipment.generateShipmentNo());
                shipment.setShipmentType(group.getShipmentType());
                shipment.setOriginWarehouseId(warehouseId);
                shipment.setDestinationWarehouseId(group.getDestinationWarehouseId());

                Warehouse origin = warehouseRepository.findById(Objects.requireNonNull(warehouseId)).orElseThrow();
                Warehouse dest = warehouseRepository.findById(Objects.requireNonNull(group.getDestinationWarehouseId()))
                        .orElseThrow();
                shipment.setOriginCity(origin.getCity());
                shipment.setDestinationCity(dest.getCity());
                shipment.setVehicleId(vehicleId);
                shipment.setDepartureTimeEst(departure);
                shipment.setArrivalTimeEst(arrival);
                shipment.setStatus(vehicleId != null ? ShipmentStatus.ASSIGNED : ShipmentStatus.CREATED);

                shipment = shipmentRepository.save(shipment);

                // Attach parcels and advance their status
                final Shipment savedShipment = shipment;
                for (UUID pid : validParcelIds) {
                    Parcel p = parcelRepository.findById(Objects.requireNonNull(pid)).orElseThrow();
                    p.setShipment(savedShipment);
                    p.setStatus(ParcelStatus.IN_SHIPMENT);
                    parcelRepository.save(p);
                    etaRedisTemplate.opsForValue().set(PARCEL_STATUS_PREFIX + pid, ParcelStatus.IN_SHIPMENT.name());
                    publishAssignedEvent(p, savedShipment);
                }

                // Mark vehicle ASSIGNED if one was selected
                if (vehicleId != null) {
                    vehicleRepository.findById(vehicleId).ifPresent(v -> {
                        if (v.getStatus() == VehicleStatus.AVAILABLE) {
                            v.setStatus(VehicleStatus.ASSIGNED);
                            vehicleRepository.save(v);
                        }
                    });
                }

                shipments.add(shipmentService.toResponse(savedShipment));
                created++;
                log.info("Executed plan group {}: shipment {} with {} parcels",
                        group.getGroupIndex(), savedShipment.getShipmentNo(), validParcelIds.size());

            } catch (Exception e) {
                skipped++;
                errors.add("Group " + group.getGroupIndex() + " failed: " + e.getMessage());
                log.error("Plan {} group {} execution failed: {}", planId, group.getGroupIndex(), e.getMessage(), e);
            }
        }

        // Evict the consumed plan from Redis
        etaRedisTemplate.delete(PLAN_KEY_PREFIX + planId);

        return new ApiResponse<>(true,
                created + " shipment(s) created",
                ExecutePlanResponse.builder()
                        .created(created)
                        .skipped(skipped)
                        .shipments(shipments)
                        .errors(errors)
                        .build(),
                200);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Plan cache helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void cachePlan(ShipmentPlan plan) {
        try {
            String key = PLAN_KEY_PREFIX + plan.getPlanId();
            Object json = objectMapper.writeValueAsString(plan);
            etaRedisTemplate.opsForValue().set(key, json, PLAN_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("Failed to cache shipment plan {}: {}", plan.getPlanId(), e.getMessage());
            throw new RuntimeException("Failed to cache shipment plan", e);
        }
    }

    private ShipmentPlan loadPlan(String planId) {
        try {
            Object raw = etaRedisTemplate.opsForValue().get(PLAN_KEY_PREFIX + planId);
            if (raw == null)
                return null;
            return objectMapper.readValue(raw.toString(), ShipmentPlan.class);
        } catch (Exception e) {
            log.error("Failed to deserialise shipment plan {}: {}", planId, e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ETA resolution
    // ─────────────────────────────────────────────────────────────────────────

    private CityRouteEtaEntry resolveEta(String origin, String dest) {
        if (origin.equalsIgnoreCase(dest))
            return null;
        CityRouteEtaEntry cached = interCityRouteService.getCached(origin, dest);
        if (cached != null)
            return cached;
        // Fall back to live computation (slower, but better than null ETA)
        try {
            return interCityRouteService.computeAndCache(origin, dest);
        } catch (Exception e) {
            log.warn("ETA computation failed for {} → {}: {}", origin, dest, e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // First-Fit Decreasing bin-packing
    //
    // Input: parcels (any order), sorted list of available vehicles by capacityKg
    // Output: list of bins, each bin is a list of parcels whose total weight ≤
    // the smallest vehicle that fits them.
    //
    // If no vehicle can hold a parcel on its own it is placed in an oversized
    // bin so nothing is silently dropped.
    // ─────────────────────────────────────────────────────────────────────────

    private List<List<Parcel>> binPack(List<Parcel> parcels, List<Vehicle> vehicles) {
        if (vehicles.isEmpty()) {
            // No vehicles configured — treat every parcel as its own "bin"
            return parcels.stream()
                    .map(p -> {
                        List<Parcel> l = new ArrayList<>();
                        l.add(p);
                        return l;
                    })
                    .collect(Collectors.toList());
        }

        double maxCapacity = vehicles.stream()
                .mapToDouble(Vehicle::getCapacityKg)
                .max()
                .orElse(Double.MAX_VALUE);

        // Sort parcels heaviest-first (FFD)
        List<Parcel> sorted = parcels.stream()
                .sorted(Comparator.comparingDouble(Parcel::getWeightKg).reversed())
                .collect(Collectors.toList());

        List<List<Parcel>> bins = new ArrayList<>();
        List<Double> binWeights = new ArrayList<>();

        for (Parcel parcel : sorted) {
            double w = parcel.getWeightKg();
            boolean placed = false;

            for (int i = 0; i < bins.size(); i++) {
                if (binWeights.get(i) + w <= maxCapacity) {
                    bins.get(i).add(parcel);
                    binWeights.set(i, binWeights.get(i) + w);
                    placed = true;
                    break;
                }
            }

            if (!placed) {
                List<Parcel> newBin = new ArrayList<>();
                newBin.add(parcel);
                bins.add(newBin);
                binWeights.add(w);
            }
        }

        return bins;
    }

    /**
     * Returns the smallest vehicle whose capacity >= required weight.
     * Vehicles list must be sorted ascending by capacity.
     */
    private Vehicle pickVehicle(double requiredWeightKg, List<Vehicle> sortedVehicles) {
        return sortedVehicles.stream()
                .filter(v -> v.getCapacityKg() >= requiredWeightKg)
                .findFirst()
                .orElse(sortedVehicles.isEmpty() ? null : sortedVehicles.get(sortedVehicles.size() - 1));
    }

    private void publishAssignedEvent(Parcel p, Shipment shipment) {
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
                    .build());
        } catch (Exception e) {
            log.warn("Failed to publish ASSIGNED_TO_SHIPMENT for parcel {}: {}", p.getId(), e.getMessage());
        }
    }
}
