package com.DeliveryInventoryService.DeliveryInventoryService.Controller;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.WarehouseDTO.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Rider;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Shipment;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Shipment.ShipmentStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.ShipmentLeg;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Vehicle;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.VehicleSchedule;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RiderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ShipmentLegRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ShipmentRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.VehicleRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.VehicleScheduleRepository;
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
        private final VehicleScheduleRepository vehicleScheduleRepository;
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
         * DISPATCHED shipments assigned to this rider, enriched with vehicle/driver
         * info so the handoff screen can auto-trigger the OTP without manual input.
         *
         * Vehicle resolution: shipment.vehicleId first, then vehicleScheduleId →
         * VehicleSchedule.vehicleId.
         */
        @GetMapping("/my-shipments")
        public ResponseEntity<ApiResponse<Object>> getMyShipments(@PathVariable UUID riderId) {
                List<ScannedShipmentItem> items = shipmentRepository
                                .findByRiderIdAndStatusInOrderByCreatedAtDesc(
                                                riderId, List.of(ShipmentStatus.DISPATCHED))
                                .stream()
                                .map(s -> {
                                        Vehicle vehicle = resolveVehicle(s);
                                        String vehicleNumber = null, vehicleType = null,
                                                        driverName = null, driverPhoneMasked = null;

                                        if (vehicle != null) {
                                                vehicleNumber = vehicle.getVehicleNumber();
                                                vehicleType = vehicle.getVehicleType() != null
                                                                ? vehicle.getVehicleType().name()
                                                                : null;
                                                Rider driver = vehicle.getRider();
                                                if (driver != null) {
                                                        driverName = driver.getName();
                                                        driverPhoneMasked = maskPhone(driver.getPhone());
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
         * GET /incoming-shipments
         *
         * IN_TRANSIT shipments headed to this rider's home warehouse.
         * Used for the RECEIVE_FROM_VEHICLE flow — the rider can see which vehicles
         * are coming, with truck number and driver, without any manual input.
         */
        @GetMapping("/incoming-shipments")
        public ResponseEntity<ApiResponse<Object>> getIncomingShipments(@PathVariable UUID riderId) {
                Rider rider = riderRepository.findById(riderId).orElse(null);
                if (rider == null || rider.getWarehouseId() == null) {
                        return ResponseEntity.ok(
                                        new ApiResponse<>(false, "Rider or home warehouse not found", List.of(), 200));
                }

                List<IncomingShipmentItem> items = shipmentRepository
                                .findByDestinationWarehouseIdAndStatus(
                                                rider.getWarehouseId(), ShipmentStatus.IN_TRANSIT)
                                .stream()
                                .map(s -> {
                                        Vehicle vehicle = resolveVehicle(s);
                                        VehicleSchedule schedule = resolveSchedule(s);
                                        ShipmentCarrierInfo carrier = buildCarrierInfo(vehicle, schedule, s);

                                        return IncomingShipmentItem.builder()
                                                        .shipmentNo(s.getShipmentNo())
                                                        .shipmentId(s.getId())
                                                        .originCity(s.getOriginCity())
                                                        .destinationCity(s.getDestinationCity())
                                                        .parcelCount(s.getParcels().size())
                                                        .carrier(carrier)
                                                        .build();
                                })
                                .collect(Collectors.toList());

                return ResponseEntity.ok(
                                new ApiResponse<>(true, items.size() + " incoming shipment(s) found", items, 200));
        }

        /**
         * GET /shipments/{shipmentNo}/track
         *
         * Full tracking for a shipment: current status, which vehicle is carrying it
         * right now (currentCarrier), and the ordered list of legs.
         */
        @GetMapping("/shipments/{shipmentNo}/track")
        public ResponseEntity<ApiResponse<Object>> trackShipment(
                        @PathVariable UUID riderId,
                        @PathVariable String shipmentNo) {

                Shipment shipment = shipmentRepository
                                .findByShipmentNo(shipmentNo.trim().toUpperCase())
                                .orElse(null);
                if (shipment == null) {
                        return ResponseEntity.status(404)
                                        .body(new ApiResponse<>(false, "Shipment not found", null, 404));
                }

                List<ShipmentLegResponse> legs = shipmentLegRepository
                                .findByShipmentIdOrderBySequenceAsc(shipment.getId())
                                .stream()
                                .map(this::toLegResponse)
                                .collect(Collectors.toList());

                ShipmentCarrierInfo carrier = null;
                if (shipment.getStatus() == ShipmentStatus.IN_TRANSIT) {
                        carrier = buildCarrierInfo(
                                        resolveVehicle(shipment),
                                        resolveSchedule(shipment),
                                        shipment);
                }

                ShipmentTrackResponse track = ShipmentTrackResponse.builder()
                                .shipmentNo(shipment.getShipmentNo())
                                .status(shipment.getStatus())
                                .originCity(shipment.getOriginCity())
                                .destinationCity(shipment.getDestinationCity())
                                .parcelCount(shipment.getParcels().size())
                                .currentCarrier(carrier)
                                .legs(legs)
                                .build();

                return ResponseEntity.ok(
                                new ApiResponse<>(true, "Tracking for " + shipment.getShipmentNo(), track, 200));
        }

        // kept for backwards compatibility — now delegates to /track
        @GetMapping("/shipments/{shipmentNo}/route")
        public ResponseEntity<ApiResponse<Object>> getShipmentRoute(
                        @PathVariable UUID riderId,
                        @PathVariable String shipmentNo) {
                return trackShipment(riderId, shipmentNo);
        }

        // ── Helpers ───────────────────────────────────────────────────────────────

        private ShipmentCarrierInfo buildCarrierInfo(Vehicle vehicle, VehicleSchedule schedule,
                        Shipment shipment) {
                if (vehicle == null && schedule == null)
                        return null;

                ShipmentCarrierInfo.ShipmentCarrierInfoBuilder b = ShipmentCarrierInfo.builder();

                if (vehicle != null) {
                        b.vehicleNumber(vehicle.getVehicleNumber());
                        b.vehicleType(vehicle.getVehicleType() != null
                                        ? vehicle.getVehicleType().name()
                                        : null);
                        Rider driver = vehicle.getRider();
                        if (driver != null) {
                                b.driverName(driver.getName());
                                b.driverPhoneMasked(maskPhone(driver.getPhone()));
                        }
                }

                if (schedule != null) {
                        b.estimatedArrival(schedule.getArrivalDateTime());
                        b.fromCity(schedule.getOriginCity());
                        b.toCity(schedule.getDestinationCity());
                } else {
                        b.estimatedArrival(shipment.getArrivalTimeEst());
                        b.fromCity(shipment.getOriginCity());
                        b.toCity(shipment.getDestinationCity());
                }

                return b.build();
        }

        /**
         * shipment.vehicleId first, then vehicleScheduleId → VehicleSchedule.vehicleId
         */
        private Vehicle resolveVehicle(Shipment shipment) {
                if (shipment.getVehicleId() != null) {
                        return vehicleRepository.findById(shipment.getVehicleId()).orElse(null);
                }
                VehicleSchedule schedule = resolveSchedule(shipment);
                if (schedule != null && schedule.getVehicleId() != null) {
                        return vehicleRepository.findById(schedule.getVehicleId()).orElse(null);
                }
                return null;
        }

        private VehicleSchedule resolveSchedule(Shipment shipment) {
                if (shipment.getVehicleScheduleId() == null)
                        return null;
                return vehicleScheduleRepository.findById(shipment.getVehicleScheduleId()).orElse(null);
        }

        private ShipmentLegResponse toLegResponse(ShipmentLeg l) {
                return ShipmentLegResponse.builder()
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
                                .build();
        }

        private static String maskPhone(String phone) {
                if (phone == null || phone.length() < 4)
                        return phone;
                int n = phone.length();
                return phone.substring(0, Math.min(3, n - 4))
                                + "*".repeat(Math.max(0, n - 7))
                                + phone.substring(n - 4);
        }
}
