package com.DeliveryInventoryService.DeliveryInventoryService.Controller.admin;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.WarehouseDTO.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.ShipmentTransferSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * ShipmentTransferController
 * ──────────────────────────
 *
 * Session-based shipment handoff API.  One session covers one physical
 * transfer event in the inter-warehouse journey:
 *
 *   DISPATCH_OUT         Warehouse admin → outgoing rider          (ASSIGNED → DISPATCHED)
 *   HAND_TO_VEHICLE      Rider → vehicle/transporter at bus stand  (DISPATCHED → IN_TRANSIT)
 *   RECEIVE_FROM_VEHICLE Vehicle → destination rider               (IN_TRANSIT → AT_DESTINATION)
 *   RECEIVE_IN           Incoming rider → destination warehouse     (AT_DESTINATION → DELIVERED)
 *
 * Flow (identical to the order batch-handover session):
 *   POST   /session/start                   → OTP sent to receiving party
 *   POST   /session/{id}/verify-otp         → identity confirmed; session ACTIVE
 *   POST   /session/{id}/scan               → scan each shipment number (repeatable)
 *   DELETE /session/{id}/scan/{shipmentNo}  → undo a mistaken scan
 *   GET    /session/{id}                    → current state + scanned list
 *   POST   /session/{id}/confirm            → advance all scanned shipments' status
 *
 * Base path: /api/v1/admin/warehouse/{warehouseId}/shipment-transfer
 */
@RestController
@RequestMapping("/api/v1/admin/warehouse/{warehouseId}/shipment-transfer")
@RequiredArgsConstructor
public class ShipmentTransferController {

    private final ShipmentTransferSessionService transferService;

    /** Step 1 — start a transfer session; OTP is sent to the receiving party */
    @PostMapping("/session/start")
    public ResponseEntity<?> startSession(@PathVariable UUID warehouseId,
                                          @RequestBody StartShipmentTransferRequest req) {
        return respond(transferService.startSession(warehouseId, req));
    }

    /** Step 2 — receiving party gives their OTP to the initiator; session becomes ACTIVE */
    @PostMapping("/session/{sessionId}/verify-otp")
    public ResponseEntity<?> verifyOtp(@PathVariable UUID warehouseId,
                                       @PathVariable String sessionId,
                                       @RequestBody VerifyHandoverOtpRequest req) {
        return respond(transferService.verifyOtp(warehouseId, sessionId, req));
    }

    /** Step 3 — scan a shipment number (call once per shipment) */
    @PostMapping("/session/{sessionId}/scan")
    public ResponseEntity<?> scanShipment(@PathVariable UUID warehouseId,
                                          @PathVariable String sessionId,
                                          @RequestBody ScanShipmentRequest req) {
        return respond(transferService.scanShipment(warehouseId, sessionId, req));
    }

    /** Remove a mistakenly scanned shipment before confirming */
    @DeleteMapping("/session/{sessionId}/scan/{shipmentNo}")
    public ResponseEntity<?> removeShipment(@PathVariable UUID warehouseId,
                                             @PathVariable String sessionId,
                                             @PathVariable String shipmentNo) {
        return respond(transferService.removeShipment(warehouseId, sessionId, shipmentNo));
    }

    /** Check current session state and scanned list */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<?> getSession(@PathVariable UUID warehouseId,
                                        @PathVariable String sessionId) {
        return respond(transferService.getSession(warehouseId, sessionId));
    }

    /** Step 4 — commit all scanned shipments; advance their status in one shot */
    @PostMapping("/session/{sessionId}/confirm")
    public ResponseEntity<?> confirmTransfer(@PathVariable UUID warehouseId,
                                             @PathVariable String sessionId) {
        return respond(transferService.confirmTransfer(warehouseId, sessionId));
    }

    private ResponseEntity<?> respond(ApiResponse<?> r) {
        return ResponseEntity.status(r.statusCode()).body(r);
    }
}
