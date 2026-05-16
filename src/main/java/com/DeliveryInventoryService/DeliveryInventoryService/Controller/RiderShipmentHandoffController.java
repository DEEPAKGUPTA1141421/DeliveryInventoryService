package com.DeliveryInventoryService.DeliveryInventoryService.Controller;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.WarehouseDTO.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Rider;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Shipment.ShipmentStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.ShipmentLeg;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Vehicle;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RiderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ShipmentLegRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ShipmentRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.VehicleRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.ShipmentTransferSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Rider-facing endpoints for inter-vehicle shipment handoffs.
 *
 * Base path: /api/v1/riders/{riderId}/shipment-handoff
 */
@RestController
@RequestMapping("/api/v1/riders/{riderId}/shipment-handoff")
@RequiredArgsConstructor
public class RiderShipmentHandoffController {

    private final ShipmentTransferSessionService sessionService;
    private final ShipmentRepository shipmentRepository;
    private final ShipmentLegRepository shipmentLegRepository;
    private final VehicleRepository vehicleRepository;
    private final RiderRepository riderRepository;

    /** POST /session/start */
    @PostMapping("/session/start")
    public ResponseEntity<ApiResponse<Object>> startSession(
            @PathVariable UUID riderId,
            @RequestBody StartShipmentTransferRequest req) {
        ApiResponse<Object> res = sessionService.startRiderSession(riderId, req);
        return ResponseEntity.status(res.statusCode()).body(res);
    }

    /** POST /session/{sessionId}/verify-otp */
    @PostMapping("/session/{sessionId}/verify-otp")
    public ResponseEntity<ApiResponse<Object>> verifyOtp(
            @PathVariable UUID riderId,
            @PathVariable String sessionId,
            @RequestBody VerifyHandoverOtpRequest req) {
        ApiResponse<Object> res = sessionService.verifyOtpForRider(riderId, sessionId, req);
        return ResponseEntity.status(res.statusCode()).body(res);
    }

    /** POST /session/{sessionId}/scan */
    @PostMapping("/session/{sessionId}/scan")
    public ResponseEntity<ApiResponse<Object>> scan(
            @PathVariable UUID riderId,
            @PathVariable String sessionId,
            @RequestBody ScanShipmentRequest req) {
        ApiResponse<Object> res = sessionService.scanShipmentForRider(riderId, sessionId, req);
        return ResponseEntity.status(res.statusCode()).body(res);
    }

    /** DELETE /session/{sessionId}/scan/{shipmentNo} */
    @DeleteMapping("/session/{sessionId}/scan/{shipmentNo}")
    public ResponseEntity<ApiResponse<Object>> removeScan(
            @PathVariable UUID riderId,
            @PathVariable String sessionId,
            @PathVariable String shipmentNo) {
        ApiResponse<Object> res = sessionService.removeShipmentForRider(riderId, sessionId, shipmentNo);
        return ResponseEntity.status(res.statusCode()).body(res);
    }

    /** GET /session/{sessionId} */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<ApiResponse<Object>> getSession(
            @PathVariable UUID riderId,
            @PathVariable String sessionId) {
        ApiResponse<Object> res = sessionService.getSessionForRider(riderId, sessionId);
        return ResponseEntity.status(res.statusCode()).body(res);
    }

    /** POST /session/{sessionId}/confirm */
    @PostMapping("/session/{sessionId}/confirm")
    public ResponseEntity<ApiResponse<Object>> confirm(
            @PathVariable UUID riderId,
            @PathVariable String sessionId) {
        ApiResponse<Object> res = sessionService.confirmTransferForRider(riderId, sessionId);
        return ResponseEntity.status(res.statusCode()).body(res);
    }

    /**
     * GET /my-shipments
     *
     * Returns DISPATCHED shipments assigned to this rider, enriched with
     * the assigned vehicle and driver info so the rider can validate the
     * handoff target without manual input.
     */
    @GetMapping("/my-shipments")
    public ResponseEntity<ApiResponse<Object>> getMyShipments(@PathVariable UUID riderId) {
        List<ScannedShipmentItem> items = shipmentRepository
                .findByRiderIdAndStatusInOrderByCreatedAtDesc(
                        riderId,
                        List.of(ShipmentStatus.DISPATCHED))
                .stream()
                .map(s -> {
                    String vehicleNumber = null;
                    String vehicleType = null;
                    String driverName = null;
                    String driverPhoneMasked = null;

                    if (s.getVehicleId() != null) {
                        Vehicle vehicle = vehicleRepository.findById(s.getVehicleId()).orElse(null);
                        if (vehicle != null) {
                            vehicleNumber = vehicle.getVehicleNumber();
                            vehicleType = vehicle.getVehicleType() != null
                                    ? vehicle.getVehicleType().name() : null;
                            Rider driver = vehicle.getRider();
                            if (driver != null) {
                                driverName = driver.getName();
                                driverPhoneMasked = maskPhone(driver.getPhone());
                            }
                        }
                    }

                    return ScannedShipmentItem.builder()
                            .shipmentNo(s.getShipmentNo())
                            .shipmentId(s.getId())
                            .originCity(s.getOriginCity())
                            .destinationCity(s.getDestinationCity())
                            .parcelCount(s.getParcels().size())
                            .currentStatus(s.getStatus())
                            .vehicleNumber(vehicleNumber)
                            .vehicleType(vehicleType)
                            .assignedDriverName(driverName)
                            .assignedDriverPhoneMasked(driverPhoneMasked)
                            .build();
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(
                new ApiResponse<>(true, items.size() + " shipment(s) found", items, 200));
    }

    /**
     * GET /shipments/{shipmentNo}/route
     *
     * Returns the ordered list of legs so the rider can see the full
     * origin → (hubs) → destination journey.
     */
    @GetMapping("/shipments/{shipmentNo}/route")
    public ResponseEntity<ApiResponse<Object>> getShipmentRoute(
            @PathVariable UUID riderId,
            @PathVariable String shipmentNo) {

        var shipment = shipmentRepository
                .findByShipmentNo(shipmentNo.trim().toUpperCase())
                .orElse(null);
        if (shipment == null) {
            return ResponseEntity.status(404)
                    .body(new ApiResponse<>(false, "Shipment not found", null, 404));
        }

        List<ShipmentLegResponse> legs = shipmentLegRepository
                .findByShipmentIdOrderBySequenceAsc(shipment.getId())
                .stream()
                .map(l -> ShipmentLegResponse.builder()
                        .id(l.getId())
                        .sequence(l.getSequence())
                        .fromWarehouseId(l.getFromWarehouseId())
                        .toWarehouseId(l.getToWarehouseId())
                        .fromCity(l.getFromCity())
                        .toCity(l.getToCity())
                        .dispatchingRiderId(l.getDispatchingRiderId())
                        .dispatchingRiderName(l.getDispatchingRiderName())
                        .receivingRiderId(l.getReceivingRiderId())
                        .receivingRiderName(l.getReceivingRiderName())
                        .status(l.getStatus())
                        .estimatedArrival(l.getEstimatedArrival())
                        .actualArrival(l.getActualArrival())
                        .createdAt(l.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        return ResponseEntity.ok(
                new ApiResponse<>(true, "Route for " + shipment.getShipmentNo(), legs, 200));
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return phone;
        int n = phone.length();
        return phone.substring(0, Math.min(3, n - 4))
                + "*".repeat(Math.max(0, n - 7))
                + phone.substring(n - 4);
    }
}
