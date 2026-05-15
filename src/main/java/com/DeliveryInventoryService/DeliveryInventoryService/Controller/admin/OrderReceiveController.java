package com.DeliveryInventoryService.DeliveryInventoryService.Controller.admin;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.WarehouseDTO.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.HandoverSessionService;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.OrderReceiveService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * OrderReceiveController
 * ──────────────────────
 * Two modes for receiving orders at a warehouse:
 *
 * A) Legacy per-order OTP (kept for backward compat):
 *   GET  /receive/lookup?orderNo=OR123456
 *   POST /receive/initiate   { orderNo }
 *   POST /receive/verify     { orderNo, otp, performedBy }
 *
 * B) Batch handover session (industry standard — one OTP per rider):
 *   POST   /receive/session/start                   { riderId }
 *   POST   /receive/session/{sessionId}/verify-otp  { otp }
 *   POST   /receive/session/{sessionId}/scan         { orderNo }
 *   DELETE /receive/session/{sessionId}/scan/{orderNo}
 *   GET    /receive/session/{sessionId}
 *   POST   /receive/session/{sessionId}/confirm
 */
@RestController
@RequestMapping("/api/v1/admin/warehouse/{warehouseId}/receive")
@RequiredArgsConstructor
public class OrderReceiveController {

    private final OrderReceiveService orderReceiveService;
    private final HandoverSessionService handoverSessionService;

    // ── A) Legacy per-order OTP ───────────────────────────────────────────

    @GetMapping("/lookup")
    public ResponseEntity<?> lookup(@PathVariable UUID warehouseId,
                                    @RequestParam String orderNo) {
        return respond(orderReceiveService.lookup(warehouseId, orderNo));
    }

    @PostMapping("/initiate")
    public ResponseEntity<?> initiate(@PathVariable UUID warehouseId,
                                      @RequestBody InitiateReceiveRequest req) {
        return respond(orderReceiveService.initiate(warehouseId, req == null ? null : req.getOrderNo()));
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@PathVariable UUID warehouseId,
                                    @RequestBody VerifyReceiveRequest req) {
        return respond(orderReceiveService.verify(warehouseId, req));
    }

    // ── B) Batch handover session ─────────────────────────────────────────

    /** Step 1 — admin starts a session for a rider; OTP is SMS'd to rider */
    @PostMapping("/session/start")
    public ResponseEntity<?> startSession(@PathVariable UUID warehouseId,
                                          @RequestBody StartHandoverSessionRequest req) {
        return respond(handoverSessionService.startSession(warehouseId, req));
    }

    /** Step 2 — rider gives OTP to admin; session becomes ACTIVE */
    @PostMapping("/session/{sessionId}/verify-otp")
    public ResponseEntity<?> verifyRiderOtp(@PathVariable UUID warehouseId,
                                             @PathVariable String sessionId,
                                             @RequestBody VerifyHandoverOtpRequest req) {
        return respond(handoverSessionService.verifyRiderOtp(warehouseId, sessionId, req));
    }

    /** Step 3 — admin scans each order barcode (call once per package) */
    @PostMapping("/session/{sessionId}/scan")
    public ResponseEntity<?> scanOrder(@PathVariable UUID warehouseId,
                                       @PathVariable String sessionId,
                                       @RequestBody ScanOrderRequest req) {
        return respond(handoverSessionService.scanOrder(warehouseId, sessionId, req));
    }

    /** Remove a mistakenly scanned order before confirming */
    @DeleteMapping("/session/{sessionId}/scan/{orderNo}")
    public ResponseEntity<?> removeOrder(@PathVariable UUID warehouseId,
                                          @PathVariable String sessionId,
                                          @PathVariable String orderNo) {
        return respond(handoverSessionService.removeOrder(warehouseId, sessionId, orderNo));
    }

    /** Check current session state + scanned list */
    @GetMapping("/session/{sessionId}")
    public ResponseEntity<?> getSession(@PathVariable UUID warehouseId,
                                         @PathVariable String sessionId) {
        return respond(handoverSessionService.getSessionStatus(warehouseId, sessionId));
    }

    /** Step 4 — commit all scanned orders to WAREHOUSE status in one shot */
    @PostMapping("/session/{sessionId}/confirm")
    public ResponseEntity<?> confirmHandover(@PathVariable UUID warehouseId,
                                              @PathVariable String sessionId) {
        return respond(handoverSessionService.confirmHandover(warehouseId, sessionId));
    }

    private ResponseEntity<?> respond(ApiResponse<?> r) {
        return ResponseEntity.status(r.statusCode()).body(r);
    }
}
