package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.WarehouseDTO.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel.ParcelStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Shipment.ShipmentStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.GeoUtils;
import com.DeliveryInventoryService.DeliveryInventoryService.kafka.ParcelLifecycleEvent;
import com.DeliveryInventoryService.DeliveryInventoryService.kafka.ParcelLifecycleProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ShipmentService
 * ───────────────
 *
 * 1. createShipment — admin explicitly creates a shipment
 * 2. addParcelsToShipment — attach parcels to an existing shipment
 * 3. autoAssignParcel — given an orderId, find/create the parcel and pick
 * the best open shipment (same src→dest, most full)
 * 4. updateShipmentStatus — state machine transitions
 * 5. getDashboard — counts for warehouse admin UI
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShipmentService {

    private static final int MAX_PARCELS_PER_SHIPMENT = 50;

    private final ShipmentRepository shipmentRepository;
    private final ParcelRepository parcelRepository;
    private final OrderRepository orderRepository;
    private final WarehouseRepository warehouseRepository;
    private final ParcelService parcelService;
    private final RiderRepository riderRepository;
    private final ParcelLifecycleProducer lifecycleProducer;
    private final RedisTemplate<String, Object> etaRedisTemplate;

    // ── Cache keys ────────────────────────────────────────────────────────
    private static final String SHIPMENT_CACHE_PREFIX = "shipment:status:";

    // ─────────────────────────────────────────────────────────────────────
    // 1. CREATE SHIPMENT
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public ApiResponse<Object> createShipment(CreateShipmentRequest req, UUID adminId) {
        Warehouse origin = warehouseRepository.findById(req.getOriginWarehouseId()).orElse(null);
        Warehouse dest = warehouseRepository.findById(req.getDestinationWarehouseId()).orElse(null);

        if (origin == null || dest == null) {
            return new ApiResponse<>(false, "Warehouse not found", null, 404);
        }

        Shipment shipment = new Shipment();
        shipment.setShipmentNo(Shipment.generateShipmentNo());
        shipment.setShipmentType(req.getShipmentType());
        shipment.setOriginWarehouseId(req.getOriginWarehouseId());
        shipment.setDestinationWarehouseId(req.getDestinationWarehouseId());
        shipment.setOriginCity(origin.getCity());
        shipment.setDestinationCity(dest.getCity());
        shipment.setVehicleId(req.getVehicleId());
        shipment.setDepartureTimeEst(req.getDepartureTimeEst());
        shipment.setArrivalTimeEst(req.getArrivalTimeEst());
        shipment.setCreatedByAdminId(adminId);
        shipment.setStatus(ShipmentStatus.CREATED);

        shipment = shipmentRepository.save(shipment);
        cacheShipmentStatus(shipment);

        // Attach parcels if provided
        if (req.getParcelIds() != null && !req.getParcelIds().isEmpty()) {
            attachParcelsToShipment(shipment, req.getParcelIds());
        }

        log.info("Shipment {} created by admin {}", shipment.getShipmentNo(), adminId);
        return new ApiResponse<>(true, "Shipment created", toResponse(shipment), 201);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. ADD PARCELS TO EXISTING SHIPMENT
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public ApiResponse<Object> addParcelsToShipment(UUID shipmentId, AddParcelsToShipmentRequest req) {
        Shipment shipment = shipmentRepository.findById(shipmentId).orElse(null);
        if (shipment == null)
            return new ApiResponse<>(false, "Shipment not found", null, 404);

        if (shipment.getStatus() == ShipmentStatus.IN_TRANSIT
                || shipment.getStatus() == ShipmentStatus.DELIVERED) {
            return new ApiResponse<>(false, "Cannot add parcels to a shipment in transit", null, 400);
        }

        int added = attachParcelsToShipment(shipment, req.getParcelIds());
        return new ApiResponse<>(true, added + " parcel(s) added to shipment", toResponse(shipment), 200);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. AUTO-ASSIGN PARCEL TO BEST SHIPMENT
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public ApiResponse<Object> autoAssign(AutoAssignRequest req) {
        // Step 1: resolve parcel
        Parcel parcel = parcelRepository.findByOrderId(req.getOrderId()).orElse(null);

        if (parcel == null) {
            if (!req.isCreateIfMissing()) {
                return new ApiResponse<>(false,
                        "No parcel found for this order. Set createIfMissing=true to auto-create.", null, 404);
            }
            // Auto-create parcel from order
            Order order = orderRepository.findById(req.getOrderId()).orElse(null);
            if (order == null)
                return new ApiResponse<>(false, "Order not found", null, 404);

            UUID srcWh = findNearestWarehouse(order.getOriginLat(), order.getOriginLng());
            UUID dstWh = findNearestWarehouse(order.getDestLat(), order.getDestLng());

            CreateParcelRequest cpr = new CreateParcelRequest();
            cpr.setOrderId(req.getOrderId());
            cpr.setWeightKg(order.getWeightKg());
            cpr.setOriginWarehouseId(srcWh);
            cpr.setDestinationWarehouseId(dstWh);
            ApiResponse<Object> created = parcelService.createParcel(cpr);
            parcel = parcelRepository.findByOrderId(req.getOrderId()).orElseThrow();
        }

        if (parcel.getStatus() != ParcelStatus.AT_WAREHOUSE) {
            return new ApiResponse<>(false,
                    "Parcel is not AT_WAREHOUSE (current: " + parcel.getStatus() + ")", null, 400);
        }

        if (parcel.getShipment() != null) {
            return new ApiResponse<>(false, "Parcel already assigned to shipment "
                    + parcel.getShipment().getShipmentNo(), null, 409);
        }

        // Step 2: find best open shipment (same route, most parcels = most full)
        List<Shipment> openShipments = shipmentRepository.findOpenShipments(
                parcel.getOriginWarehouseId(), parcel.getDestinationWarehouseId());

        Shipment best = openShipments.stream()
                .filter(s -> s.getParcels().size() < MAX_PARCELS_PER_SHIPMENT)
                .max(Comparator.comparingInt(s -> s.getParcels().size()))
                .orElse(null);

        // Step 3: create shipment if none available
        if (best == null) {
            Warehouse origin = warehouseRepository.findById(parcel.getOriginWarehouseId()).orElseThrow();
            Warehouse dest = warehouseRepository.findById(parcel.getDestinationWarehouseId()).orElseThrow();

            best = new Shipment();
            best.setShipmentNo(Shipment.generateShipmentNo());
            best.setShipmentType("LAST_MILE");
            best.setOriginWarehouseId(origin.getId());
            best.setDestinationWarehouseId(dest.getId());
            best.setOriginCity(origin.getCity());
            best.setDestinationCity(dest.getCity());
            best.setStatus(ShipmentStatus.CREATED);
            best = shipmentRepository.save(best);
            cacheShipmentStatus(best);
            log.info("Auto-created shipment {} for parcel {}", best.getShipmentNo(), parcel.getId());
        }

        // Step 4: link
        final Shipment finalBest = best;
        parcel.setShipment(finalBest);
        parcel.setStatus(ParcelStatus.IN_SHIPMENT);
        parcelRepository.save(parcel);

        // Update cache
        etaRedisTemplate.opsForValue().set("parcel:status:" + parcel.getId(), parcel.getStatus().name());

        return new ApiResponse<>(true, "Parcel assigned to shipment " + best.getShipmentNo(),
                AutoAssignResponse.builder()
                        .parcelId(parcel.getId())
                        .shipmentId(best.getId())
                        .shipmentNo(best.getShipmentNo())
                        .message("Assigned to " + best.getShipmentNo())
                        .build(),
                200);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 4. SHIPMENT STATUS TRANSITIONS
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public ApiResponse<Object> updateShipmentStatus(UUID shipmentId, String newStatus) {
        Shipment shipment = shipmentRepository.findById(shipmentId).orElse(null);
        if (shipment == null)
            return new ApiResponse<>(false, "Shipment not found", null, 404);

        ShipmentStatus next;
        try {
            next = ShipmentStatus.valueOf(newStatus.toUpperCase());
        } catch (IllegalArgumentException e) {
            return new ApiResponse<>(false, "Invalid status: " + newStatus, null, 400);
        }

        if (!isValidTransition(shipment.getStatus(), next)) {
            return new ApiResponse<>(false,
                    "Invalid transition: " + shipment.getStatus() + " → " + next, null, 400);
        }

        shipment.setStatus(next);
        shipmentRepository.save(shipment);
        cacheShipmentStatus(shipment);

        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));

        if (next == ShipmentStatus.IN_TRANSIT) {
            parcelRepository.findByShipmentId(shipmentId).forEach(p -> {
                p.setStatus(ParcelStatus.IN_TRANSIT);
                p.setDispatchedAt(now);
                parcelRepository.save(p);
                etaRedisTemplate.opsForValue().set("parcel:status:" + p.getId(), ParcelStatus.IN_TRANSIT.name());
                publishEvent(p, ParcelLifecycleEvent.EventType.SHIPMENT_DEPARTED,
                        ParcelStatus.IN_TRANSIT, shipment, null);
            });
        }

        if (next == ShipmentStatus.ARRIVED) {
            UUID shipmentDestId = shipment.getDestinationWarehouseId();
            parcelRepository.findByShipmentId(shipmentId).forEach(p -> {
                p.setCurrentWarehouseId(shipmentDestId);
                p.setArrivedAtWarehouseAt(now);

                boolean atFinalDest = shipmentDestId.equals(p.getDestinationWarehouseId());
                if (atFinalDest) {
                    // Last leg: ready for last-mile dispatch
                    p.setStatus(ParcelStatus.AT_DEST_WAREHOUSE);
                } else {
                    // Intermediate hub: clear shipment link so cron/planner can re-bag
                    // for the next leg. Setting status back to AT_WAREHOUSE makes it
                    // visible to findUnshippedParcels at the hub warehouse.
                    p.setStatus(ParcelStatus.AT_WAREHOUSE);
                    p.setShipment(null);
                }

                parcelRepository.save(p);
                etaRedisTemplate.opsForValue().set("parcel:status:" + p.getId(), p.getStatus().name());
                publishEvent(p, ParcelLifecycleEvent.EventType.SHIPMENT_ARRIVED,
                        p.getStatus(), shipment, atFinalDest ? null : "Intermediate hub — re-queued for next leg");
            });
        }

        return new ApiResponse<>(true, "Shipment status updated to " + next, toResponse(shipment), 200);
    }

    private void publishEvent(Parcel p, ParcelLifecycleEvent.EventType type,
                              ParcelStatus newStatus, Shipment shipment, String notes) {
        try {
            lifecycleProducer.publish(ParcelLifecycleEvent.builder()
                    .parcelId(p.getId())
                    .orderId(p.getOrderId())
                    .eventType(type)
                    .newStatus(newStatus)
                    .warehouseId(shipment.getDestinationWarehouseId())
                    .shipmentId(shipment.getId())
                    .shipmentNo(shipment.getShipmentNo())
                    .occurredAt(ZonedDateTime.now(ZoneId.of("Asia/Kolkata")))
                    .notes(notes)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to publish lifecycle event {} for parcel {}: {}", type, p.getId(), e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 5. DASHBOARD
    // ─────────────────────────────────────────────────────────────────────

    public ApiResponse<Object> getDashboard(UUID warehouseId) {
        Warehouse wh = warehouseRepository.findById(warehouseId).orElse(null);
        if (wh == null)
            return new ApiResponse<>(false, "Warehouse not found", null, 404);

        long activeRiders = riderRepository.findByCity(wh.getCity()).stream()
                .filter(r -> r.getStatus() == Rider.RiderStatus.ACTIVE)
                .count();

        WarehouseDashboard dashboard = WarehouseDashboard.builder()
                .warehouseId(warehouseId)
                .warehouseName(wh.getName())
                .city(wh.getCity())
                .parcelsCreated(parcelRepository.countByWarehouseAndStatus(warehouseId, ParcelStatus.CREATED))
                .parcelsAwaitingPickup(
                        parcelRepository.countByWarehouseAndStatus(warehouseId, ParcelStatus.AWAITING_PICKUP))
                .parcelsAtWarehouse(parcelRepository.countByWarehouseAndStatus(warehouseId, ParcelStatus.AT_WAREHOUSE))
                .parcelsInShipment(parcelRepository.countByWarehouseAndStatus(warehouseId, ParcelStatus.IN_SHIPMENT))
                .parcelsOutForDelivery(
                        parcelRepository.countByWarehouseAndStatus(warehouseId, ParcelStatus.OUT_FOR_DELIVERY))
                .parcelsDelivered(parcelRepository.countByWarehouseAndStatus(warehouseId, ParcelStatus.DELIVERED))
                .shipmentsCreated(
                        shipmentRepository.findByOriginWarehouseIdAndStatus(warehouseId, ShipmentStatus.CREATED).size())
                .shipmentsInTransit(shipmentRepository
                        .findByOriginWarehouseIdAndStatus(warehouseId, ShipmentStatus.IN_TRANSIT).size())
                .shipmentsArrived(shipmentRepository
                        .findByDestinationWarehouseIdAndStatus(warehouseId, ShipmentStatus.ARRIVED).size())
                .activeRiders(activeRiders)
                .build();

        return new ApiResponse<>(true, "Dashboard", dashboard, 200);
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private int attachParcelsToShipment(Shipment shipment, List<UUID> parcelIds) {
        int added = 0;
        for (UUID pid : parcelIds) {
            Parcel parcel = parcelRepository.findById(pid).orElse(null);
            if (parcel == null) {
                log.warn("Parcel {} not found — skipping", pid);
                continue;
            }
            if (parcel.getShipment() != null) {
                log.warn("Parcel {} already in shipment {} — skipping", pid, parcel.getShipment().getShipmentNo());
                continue;
            }
            if (parcel.getStatus() != ParcelStatus.AT_WAREHOUSE) {
                log.warn("Parcel {} not AT_WAREHOUSE — skipping", pid);
                continue;
            }
            parcel.setShipment(shipment);
            parcel.setStatus(ParcelStatus.IN_SHIPMENT);
            parcelRepository.save(parcel);

            // Update Redis cache
            etaRedisTemplate.opsForValue().set("parcel:status:" + parcel.getId(), parcel.getStatus().name());
            added++;
        }
        return added;
    }

    private UUID findNearestWarehouse(double lat, double lng) {
        return warehouseRepository.findAll().stream()
                .filter(w -> w.getStatus() == Warehouse.WarehouseStatus.ACTIVE)
                .min(Comparator.comparingDouble(w -> GeoUtils.distanceKm(w.getLat(), w.getLng(), lat, lng)))
                .map(Warehouse::getId)
                .orElse(null);
    }

    private boolean isValidTransition(ShipmentStatus from, ShipmentStatus to) {
        return switch (from) {
            case CREATED -> to == ShipmentStatus.ASSIGNED || to == ShipmentStatus.CANCELLED;
            case ASSIGNED -> to == ShipmentStatus.PICKED_UP || to == ShipmentStatus.CANCELLED;
            case PICKED_UP -> to == ShipmentStatus.IN_TRANSIT;
            case IN_TRANSIT -> to == ShipmentStatus.ARRIVED;
            case ARRIVED -> to == ShipmentStatus.DELIVERED;
            default -> false;
        };
    }

    private void cacheShipmentStatus(Shipment shipment) {
        etaRedisTemplate.opsForValue().set(
                SHIPMENT_CACHE_PREFIX + shipment.getId(), shipment.getStatus().name());
    }

    /**
     * Returns all shipments where this warehouse is origin OR destination,
     * deduplicated and sorted newest-first. Includes arriving shipments so
     * the warehouse UI can track inbound deliveries for next-hop re-planning.
     */
    @Transactional(readOnly = true)
    public List<ShipmentResponse> getWarehouseShipments(UUID warehouseId, String status) {
        LinkedHashSet<Shipment> merged = new LinkedHashSet<>();
        if (status != null) {
            ShipmentStatus st = ShipmentStatus.valueOf(status.toUpperCase());
            merged.addAll(shipmentRepository.findByOriginWarehouseIdAndStatus(warehouseId, st));
            merged.addAll(shipmentRepository.findByDestinationWarehouseIdAndStatus(warehouseId, st));
        } else {
            merged.addAll(shipmentRepository.findByOriginWarehouseId(warehouseId));
            merged.addAll(shipmentRepository.findByDestinationWarehouseId(warehouseId));
        }
        return merged.stream()
                .sorted(Comparator.comparing(Shipment::getCreatedAt).reversed())
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<ShipmentResponse> findShipmentById(UUID shipmentId) {
        return shipmentRepository.findById(shipmentId).map(this::toResponse);
    }

    // ─────────────────────────────────────────────────────────────────────
    // UPDATE / DELETE
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public ApiResponse<Object> updateShipment(UUID shipmentId, UpdateShipmentRequest req) {
        Shipment shipment = shipmentRepository.findById(shipmentId).orElse(null);
        if (shipment == null) return new ApiResponse<>(false, "Shipment not found", null, 404);

        if (shipment.getStatus() != ShipmentStatus.CREATED
                && shipment.getStatus() != ShipmentStatus.ASSIGNED) {
            return new ApiResponse<>(false,
                    "Cannot edit shipment in " + shipment.getStatus() + " state", null, 409);
        }

        if (req.getVehicleId() != null) shipment.setVehicleId(req.getVehicleId());
        if (req.getShipmentType() != null) shipment.setShipmentType(req.getShipmentType());
        if (req.getCostEstimate() != null) shipment.setCostEstimate(req.getCostEstimate());
        if (req.getDepartureTimeEst() != null && !req.getDepartureTimeEst().isBlank()) {
            shipment.setDepartureTimeEst(java.time.ZonedDateTime.parse(req.getDepartureTimeEst()));
        }
        if (req.getArrivalTimeEst() != null && !req.getArrivalTimeEst().isBlank()) {
            shipment.setArrivalTimeEst(java.time.ZonedDateTime.parse(req.getArrivalTimeEst()));
        }

        shipmentRepository.save(shipment);
        return new ApiResponse<>(true, "Shipment updated", toResponse(shipment), 200);
    }

    /**
     * Delete a shipment that hasn't moved. Detaches any parcels back to
     * AT_WAREHOUSE so they can be re-bagged into a new shipment.
     */
    @Transactional
    public ApiResponse<Object> deleteShipment(UUID shipmentId) {
        Shipment shipment = shipmentRepository.findById(shipmentId).orElse(null);
        if (shipment == null) return new ApiResponse<>(false, "Shipment not found", null, 404);

        if (shipment.getStatus() != ShipmentStatus.CREATED
                && shipment.getStatus() != ShipmentStatus.CANCELLED) {
            return new ApiResponse<>(false,
                    "Cannot delete shipment in " + shipment.getStatus() + " — cancel it first",
                    null, 409);
        }

        parcelRepository.findByShipmentId(shipmentId).forEach(p -> {
            p.setShipment(null);
            p.setStatus(ParcelStatus.AT_WAREHOUSE);
            parcelRepository.save(p);
            etaRedisTemplate.opsForValue().set("parcel:status:" + p.getId(), p.getStatus().name());
        });

        shipmentRepository.delete(shipment);
        etaRedisTemplate.delete(SHIPMENT_CACHE_PREFIX + shipmentId);
        log.info("Shipment {} deleted; parcels released back to AT_WAREHOUSE", shipmentId);
        return new ApiResponse<>(true, "Shipment deleted", null, 200);
    }

    public ShipmentResponse toResponse(Shipment s) {
        List<Parcel> parcelList = s.getParcels();
        double totalWt = parcelList.stream().mapToDouble(Parcel::getWeightKg).sum();
        return ShipmentResponse.builder()
                .id(s.getId())
                .shipmentNo(s.getShipmentNo())
                .shipmentType(s.getShipmentType())
                .vehicleId(s.getVehicleId())
                .originWarehouseId(s.getOriginWarehouseId())
                .destinationWarehouseId(s.getDestinationWarehouseId())
                .originCity(s.getOriginCity())
                .destinationCity(s.getDestinationCity())
                .parcelCount(parcelList.size())
                .totalWeightKg(totalWt)
                .status(s.getStatus())
                .departureTimeEst(s.getDepartureTimeEst())
                .arrivalTimeEst(s.getArrivalTimeEst())
                .createdAt(s.getCreatedAt())
                .updatedAt(s.getUpdatedAt())
                .parcels(parcelList.stream().map(parcelService::toResponse).collect(Collectors.toList()))
                .build();
    }
}