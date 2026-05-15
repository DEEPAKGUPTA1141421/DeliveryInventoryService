package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.WarehouseDTO.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order.OrderStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel.ParcelStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Vehicle.VehicleStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * WarehouseOpsService — operations dashboard backend logic.
 *
 *   1. unassignedOrdersByCity  — Step 2 of production flow
 *   2. bulkCreateParcels       — Step 2 → Step 3 transition
 *   3. shipmentSuggestions     — Step 3 (system suggests groupings)
 *   4. bulkCreateShipment      — Step 3 commit
 *   5. availableVehicles       — Step 4 picker
 *   6. assignVehicleToShipment — Step 4 commit (also creates Route)
 *   7. routeAssignmentsAtWarehouse — Step 7-8 last-mile dispatch overview
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WarehouseOpsService {

    private final OrderRepository orderRepository;
    private final ParcelRepository parcelRepository;
    private final ShipmentRepository shipmentRepository;
    private final VehicleRepository vehicleRepository;
    private final WarehouseRepository warehouseRepository;
    private final RiderRepository riderRepository;
    private final RouteAssignmentRepository routeAssignmentRepository;
    private final RouteRepository routeRepository;
    private final ParcelService parcelService;
    private final ShipmentService shipmentService;

    // ═════════════════════════════════════════════════════════════════════
    // 1. Unassigned orders grouped by destination city
    // ═════════════════════════════════════════════════════════════════════

    public ApiResponse<Object> unassignedOrdersByCity(UUID warehouseId) {
        List<Order> orders = orderRepository.findUnassignedByWarehouse(warehouseId, OrderStatus.CREATED);

        Map<String, List<Order>> byCity = orders.stream()
                .collect(Collectors.groupingBy(
                        o -> Optional.ofNullable(o.getDestCity()).orElse("UNKNOWN"),
                        LinkedHashMap::new, Collectors.toList()));

        List<UnassignedOrderGroup> groups = byCity.entrySet().stream()
                .map(e -> UnassignedOrderGroup.builder()
                        .destCity(e.getKey())
                        .count(e.getValue().size())
                        .totalWeightKg(e.getValue().stream().mapToDouble(Order::getWeightKg).sum())
                        .orders(e.getValue().stream().map(this::toUnassignedItem).toList())
                        .build())
                .toList();

        return new ApiResponse<>(true, "OK", groups, 200);
    }

    private UnassignedOrderItem toUnassignedItem(Order o) {
        return UnassignedOrderItem.builder()
                .id(o.getId())
                .orderNo(o.getOrderNo())
                .destAddress(o.getDestAddress())
                .destCity(o.getDestCity())
                .weightKg(o.getWeightKg())
                .destLat(o.getDestLat())
                .destLng(o.getDestLng())
                .build();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. Bulk create parcels from selected orders
    // ═════════════════════════════════════════════════════════════════════

    @Transactional
    public ApiResponse<Object> bulkCreateParcels(UUID warehouseId, BulkCreateParcelsRequest req) {
        if (req.getOrderIds() == null || req.getOrderIds().isEmpty()) {
            return new ApiResponse<>(false, "orderIds required", null, 400);
        }

        List<ParcelResponse> created = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int skipped = 0;

        for (UUID orderId : req.getOrderIds()) {
            try {
                Order order = orderRepository.findById(orderId).orElse(null);
                if (order == null) { errors.add("Order " + orderId + " not found"); skipped++; continue; }

                if (parcelRepository.findByOrderId(orderId).isPresent()) {
                    errors.add("Order " + orderId + " already has a parcel");
                    skipped++;
                    continue;
                }

                UUID destWh = findNearestWarehouse(order.getDestLat(), order.getDestLng());
                if (destWh == null) {
                    errors.add("No active warehouse near destination of order " + order.getOrderNo());
                    skipped++;
                    continue;
                }

                CreateParcelRequest cpr = new CreateParcelRequest();
                cpr.setOrderId(orderId);
                cpr.setWeightKg(order.getWeightKg());
                cpr.setOriginWarehouseId(warehouseId);
                cpr.setDestinationWarehouseId(destWh);
                cpr.setDescription("Auto-created from order " + order.getOrderNo());

                ApiResponse<Object> resp = parcelService.createParcel(cpr);
                if (resp.success() && resp.data() instanceof ParcelResponse pr) {
                    created.add(pr);
                } else {
                    errors.add("Order " + order.getOrderNo() + ": " + resp.message());
                    skipped++;
                }
            } catch (Exception ex) {
                errors.add("Order " + orderId + ": " + ex.getMessage());
                skipped++;
            }
        }

        BulkCreateParcelsResponse data = BulkCreateParcelsResponse.builder()
                .created(created.size())
                .skipped(skipped)
                .parcels(created)
                .errors(errors)
                .build();
        return new ApiResponse<>(true, created.size() + " parcel(s) created", data, 200);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. Shipment suggestions: group AT_WAREHOUSE parcels by destination wh
    // ═════════════════════════════════════════════════════════════════════

    public ApiResponse<Object> shipmentSuggestions(UUID warehouseId) {
        List<Parcel> parcels = parcelRepository.findUnshippedParcels(warehouseId);

        Map<UUID, List<Parcel>> byDest = parcels.stream()
                .filter(p -> p.getDestinationWarehouseId() != null)
                .collect(Collectors.groupingBy(Parcel::getDestinationWarehouseId));

        List<ShipmentSuggestion> suggestions = byDest.entrySet().stream()
                .map(e -> {
                    Warehouse dw = warehouseRepository.findById(e.getKey()).orElse(null);
                    return ShipmentSuggestion.builder()
                            .destWarehouseId(e.getKey())
                            .destCity(dw != null ? dw.getCity() : "UNKNOWN")
                            .parcelCount(e.getValue().size())
                            .totalWeightKg(e.getValue().stream().mapToDouble(Parcel::getWeightKg).sum())
                            .parcelIds(e.getValue().stream().map(Parcel::getId).toList())
                            .build();
                })
                .sorted(Comparator.comparingInt(ShipmentSuggestion::getParcelCount).reversed())
                .toList();

        return new ApiResponse<>(true, "OK", suggestions, 200);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. Bulk create shipment (delegates to ShipmentService.createShipment)
    // ═════════════════════════════════════════════════════════════════════

    @Transactional
    public ApiResponse<Object> bulkCreateShipment(UUID warehouseId,
                                                  BulkCreateShipmentRequest req,
                                                  UUID adminId) {
        if (req.getDestWarehouseId() == null) {
            return new ApiResponse<>(false, "destWarehouseId required", null, 400);
        }
        if (req.getParcelIds() == null || req.getParcelIds().isEmpty()) {
            return new ApiResponse<>(false, "parcelIds required", null, 400);
        }

        CreateShipmentRequest csr = new CreateShipmentRequest();
        csr.setOriginWarehouseId(warehouseId);
        csr.setDestinationWarehouseId(req.getDestWarehouseId());
        csr.setParcelIds(req.getParcelIds());
        csr.setVehicleId(req.getVehicleId());
        csr.setShipmentType(req.getShipmentType() != null ? req.getShipmentType() : "INTER_HUB");

        ApiResponse<Object> resp = shipmentService.createShipment(csr, adminId);

        // If a vehicle was supplied at create-time, also create the Route now.
        if (resp.success() && req.getVehicleId() != null && resp.data() instanceof ShipmentResponse sr) {
            try {
                createRouteForShipment(sr.getId(), req.getVehicleId());
            } catch (Exception e) {
                log.warn("Shipment created but route creation failed: {}", e.getMessage());
            }
        }
        return resp;
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. Available vehicles at this warehouse
    // ═════════════════════════════════════════════════════════════════════

    public ApiResponse<Object> availableVehicles(UUID warehouseId) {
        List<VehicleSummary> vs = vehicleRepository
                .findByHomeWarehouseIdAndStatus(warehouseId, VehicleStatus.AVAILABLE)
                .stream()
                .map(v -> VehicleSummary.builder()
                        .id(v.getId())
                        .vehicleType(v.getVehicleType() != null ? v.getVehicleType().name() : null)
                        .vehicleNumber(v.getVehicleNumber())
                        .capacityKg(v.getCapacityKg())
                        .maxParcels(v.getMaxParcels())
                        .status(v.getStatus().name())
                        .build())
                .toList();
        return new ApiResponse<>(true, "OK", vs, 200);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. Assign vehicle to shipment + create Route
    // ═════════════════════════════════════════════════════════════════════

    @Transactional
    public ApiResponse<Object> assignVehicle(UUID shipmentId, AssignVehicleRequest req) {
        if (req.getVehicleId() == null) {
            return new ApiResponse<>(false, "vehicleId required", null, 400);
        }

        Shipment shipment = shipmentRepository.findById(shipmentId).orElse(null);
        if (shipment == null) return new ApiResponse<>(false, "Shipment not found", null, 404);

        Vehicle vehicle = vehicleRepository.findById(req.getVehicleId()).orElse(null);
        if (vehicle == null) return new ApiResponse<>(false, "Vehicle not found", null, 404);

        if (vehicle.getStatus() != VehicleStatus.AVAILABLE) {
            return new ApiResponse<>(false,
                    "Vehicle not AVAILABLE (current: " + vehicle.getStatus() + ")", null, 409);
        }

        shipment.setVehicleId(vehicle.getId());
        if (shipment.getStatus() == Shipment.ShipmentStatus.CREATED) {
            shipment.setStatus(Shipment.ShipmentStatus.ASSIGNED);
        }
        shipmentRepository.save(shipment);

        vehicle.setStatus(VehicleStatus.ASSIGNED);
        vehicleRepository.save(vehicle);

        UUID routeId = createRouteForShipment(shipment.getId(), vehicle.getId());

        return new ApiResponse<>(true, "Vehicle assigned + route created",
                AssignVehicleResponse.builder()
                        .shipmentId(shipment.getId())
                        .vehicleId(vehicle.getId())
                        .routeId(routeId)
                        .shipment(shipmentService.toResponse(shipment))
                        .build(),
                200);
    }

    /**
     * Builds a Route for a shipment:
     *   point[0]   = origin warehouse
     *   point[1..] = each parcel destination address (LAST_MILE) — skipped for INTER_HUB
     *   point[N]   = destination warehouse
     */
    private UUID createRouteForShipment(UUID shipmentId, UUID vehicleId) {
        Shipment shipment = shipmentRepository.findById(shipmentId).orElseThrow();
        Vehicle vehicle = vehicleRepository.findById(vehicleId).orElseThrow();
        Warehouse origin = warehouseRepository.findById(shipment.getOriginWarehouseId()).orElseThrow();
        Warehouse dest = warehouseRepository.findById(shipment.getDestinationWarehouseId()).orElseThrow();

        Route route = new Route();
        route.setVehicle(vehicle);
        route.setRouteName("SHP-" + shipment.getShipmentNo());
        route.setStartLocation(origin.getCity());
        route.setDestinationLocation(dest.getCity());
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        route.setStartTime(now);
        route.setEndTime(now.plusHours(8));

        int seq = 0;
        route.getPoints().add(buildPoint(route, seq++, origin.getName(),
                origin.getLat(), origin.getLng(), origin.getCity(), RoutePoint.PointType.WAREHOUSE, now, now.plusMinutes(15)));

        boolean lastMile = "LAST_MILE".equalsIgnoreCase(shipment.getShipmentType());
        if (lastMile) {
            for (Parcel p : shipment.getParcels()) {
                Order o = orderRepository.findById(p.getOrderId()).orElse(null);
                if (o == null || o.getDestLat() == null || o.getDestLng() == null) continue;
                route.getPoints().add(buildPoint(route, seq++, o.getDestAddress(),
                        o.getDestLat(), o.getDestLng(), o.getDestCity(),
                        RoutePoint.PointType.DELIVERY, now, now.plusHours(seq)));
            }
        }

        route.getPoints().add(buildPoint(route, seq, dest.getName(),
                dest.getLat(), dest.getLng(), dest.getCity(),
                RoutePoint.PointType.WAREHOUSE, now.plusHours(seq + 1L), now.plusHours(seq + 2L)));

        Route saved = routeRepository.save(route);
        log.info("Route {} created for shipment {} with {} stops", saved.getId(), shipment.getShipmentNo(), saved.getPoints().size());
        return saved.getId();
    }

    private RoutePoint buildPoint(Route route, int seq, String name, double lat, double lng,
                                  String city, RoutePoint.PointType type,
                                  LocalDateTime start, LocalDateTime end) {
        RoutePoint rp = new RoutePoint();
        rp.setRoute(route);
        rp.setSequence(seq);
        rp.setLocationName(name != null ? name : "Stop " + seq);
        rp.setLatitude(lat);
        rp.setLongitude(lng);
        rp.setCity(city);
        rp.setPointType(type);
        rp.setStartTime(start);
        rp.setEndTime(end);
        return rp;
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. Today's RouteAssignments grouped by rider
    // ═════════════════════════════════════════════════════════════════════

    public ApiResponse<Object> routeAssignmentsAtWarehouse(UUID warehouseId) {
        ZonedDateTime startOfDay = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
                .toLocalDate().atStartOfDay(ZoneId.of("Asia/Kolkata"));

        List<RouteAssignment> all = routeAssignmentRepository.findTodayByWarehouse(warehouseId, startOfDay);

        Map<UUID, List<RouteAssignment>> byRider = all.stream()
                .collect(Collectors.groupingBy(RouteAssignment::getRiderId, LinkedHashMap::new, Collectors.toList()));

        List<RiderAssignmentsBundle> bundles = new ArrayList<>();
        for (var e : byRider.entrySet()) {
            Rider rider = riderRepository.findById(e.getKey()).orElse(null);
            List<RouteAssignmentStop> stops = e.getValue().stream().map(this::toStop).toList();
            int completed = (int) stops.stream()
                    .filter(s -> "DELIVERED".equals(s.getStatus()) || "FAILED".equals(s.getStatus())).count();

            bundles.add(RiderAssignmentsBundle.builder()
                    .riderId(e.getKey())
                    .riderName(rider != null ? rider.getName() : "Rider " + e.getKey())
                    .riderPhone(rider != null ? rider.getPhone() : null)
                    .currentLat(rider != null ? rider.getCurrentLat() : 0.0)
                    .currentLng(rider != null ? rider.getCurrentLng() : 0.0)
                    .totalStops(stops.size())
                    .completed(completed)
                    .assignments(stops)
                    .build());
        }
        return new ApiResponse<>(true, "OK", bundles, 200);
    }

    private RouteAssignmentStop toStop(RouteAssignment ra) {
        Order o = orderRepository.findById(ra.getOrderId()).orElse(null);
        return RouteAssignmentStop.builder()
                .assignmentId(ra.getId())
                .orderId(ra.getOrderId())
                .sequenceNumber(ra.getSequenceNumber())
                .status(ra.getStatus() != null ? ra.getStatus().name() : "ASSIGNED")
                .destAddress(o != null ? o.getDestAddress() : null)
                .destCity(o != null ? o.getDestCity() : null)
                .destLat(o != null ? o.getDestLat() : null)
                .destLng(o != null ? o.getDestLng() : null)
                .weightKg(o != null ? o.getWeightKg() : 0.0)
                .build();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. Orders at a warehouse (any status, or filtered by status)
    // ═════════════════════════════════════════════════════════════════════

    public ApiResponse<Object> ordersAtWarehouse(UUID warehouseId, String statusParam) {
        List<Order> orders;
        if (statusParam != null && !statusParam.isBlank()) {
            try {
                OrderStatus status = OrderStatus.valueOf(statusParam.toUpperCase());
                orders = orderRepository.findByWareHouseIdAndStatus(warehouseId, status);
            } catch (IllegalArgumentException e) {
                return new ApiResponse<>(false, "Unknown status: " + statusParam, null, 400);
            }
        } else {
            orders = orderRepository.findByWareHouseId(warehouseId);
        }

        List<OrderSummary> summaries = orders.stream().map(this::toOrderSummary).toList();
        return new ApiResponse<>(true, "OK", summaries, 200);
    }

    private OrderSummary toOrderSummary(Order o) {
        OrderSummary.OrderSummaryBuilder b = OrderSummary.builder()
                .id(o.getId())
                .orderNo(o.getOrderNo())
                .originAddress(o.getOriginAddress())
                .originCity(o.getOriginCity())
                .destAddress(o.getDestAddress())
                .destCity(o.getDestCity())
                .weightKg(o.getWeightKg())
                .status(o.getStatus())
                .serviceType(o.getServiceType())
                .priority(o.getPriority())
                .riderId(o.getRiderId())
                .wareHouseId(o.getWareHouseId())
                .placedAt(o.getPlacedAt())
                .createdAt(o.getCreatedAt());

        if (o.getRiderId() != null) {
            riderRepository.findById(o.getRiderId()).ifPresent(r -> b
                    .riderName(r.getName())
                    .riderPhone(r.getPhone())
                    .riderCity(r.getCity())
                    .riderStatus(r.getStatus() != null ? r.getStatus().name() : null)
                    .riderLat(r.getCurrentLat())
                    .riderLng(r.getCurrentLng()));
        }

        return b.build();
    }

    // ═════════════════════════════════════════════════════════════════════
    // helpers
    // ═════════════════════════════════════════════════════════════════════

    private UUID findNearestWarehouse(double lat, double lng) {
        return warehouseRepository.findAll().stream()
                .filter(w -> w.getStatus() == Warehouse.WarehouseStatus.ACTIVE)
                .min(Comparator.comparingDouble(w -> GeoUtils.distanceKm(w.getLat(), w.getLng(), lat, lng)))
                .map(Warehouse::getId)
                .orElse(null);
    }
}
