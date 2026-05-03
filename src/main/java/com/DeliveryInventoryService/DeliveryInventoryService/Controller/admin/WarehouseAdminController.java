package com.DeliveryInventoryService.DeliveryInventoryService.Controller.admin;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.WarehouseDTO.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.ParcelService;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.ShipmentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * WarehouseAdminController
 * ─────────────────────────
 *
 * Base path: /api/v1/admin/warehouse
 *
 * ── Parcels ────────────────────────────────────────────────────────
 * POST /parcels Create parcel from order
 * GET /parcels/{parcelId} Get parcel details
 * GET /parcels/order/{orderId} Get parcel by order
 * GET /{warehouseId}/parcels List parcels at a warehouse
 * GET /riders/{riderId}/parcels List rider's active parcels
 *
 * ── Pickup flow ────────────────────────────────────────────────────
 * POST /parcels/assign-pickup-rider Assign rider; send OTP to seller
 * POST /parcels/verify-seller-otp Rider verifies seller OTP
 * POST /parcels/{id}/initiate-warehouse-in Initiate warehouse-in OTP
 * POST /parcels/verify-warehouse-in Admin verifies warehouse-in OTP
 *
 * ── Delivery flow ──────────────────────────────────────────────────
 * POST /parcels/assign-delivery-rider Assign delivery rider; send OTPs
 * POST /parcels/verify-warehouse-out Rider verifies warehouse-out OTP
 * POST /parcels/verify-customer-otp Rider verifies customer OTP
 *
 * ── Shipments ──────────────────────────────────────────────────────
 * POST /shipments Create shipment
 * POST /shipments/{id}/parcels Add parcels to shipment
 * POST /shipments/auto-assign Auto-assign parcel → best shipment
 * PATCH /shipments/{id}/status Update shipment status
 * GET /{warehouseId}/shipments List shipments at warehouse
 * GET /shipments/{id} Get shipment details
 *
 * ── Dashboard ──────────────────────────────────────────────────────
 * GET /{warehouseId}/dashboard Stats for admin UI
 */
@RestController
@RequestMapping("/api/v1/admin/warehouse")
@RequiredArgsConstructor
@Slf4j
public class WarehouseAdminController {

    private final ParcelService parcelService;
    private final ShipmentService shipmentService;

    // ═══════════════════════════════════════════════════════════════
    // PARCEL CRUD
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/parcels")
    public ResponseEntity<?> createParcel(@RequestBody CreateParcelRequest req) {
        return respond(parcelService.createParcel(req));
    }

    @GetMapping("/parcels/{parcelId}")
    public ResponseEntity<?> getParcel(@PathVariable UUID parcelId) {
        return respond(parcelService.getParcel(parcelId));
    }

    @GetMapping("/parcels/order/{orderId}")
    public ResponseEntity<?> getParcelByOrder(@PathVariable UUID orderId) {
        return respond(parcelService.getParcelByOrder(orderId));
    }

    @GetMapping("/{warehouseId}/parcels")
    public ResponseEntity<?> listWarehouseParcels(
            @PathVariable UUID warehouseId,
            @RequestParam(required = false) String status) {
        List<ParcelResponse> parcels = parcelService.getWarehouseParcels(warehouseId, status);
        return ResponseEntity.ok(new ApiResponse<>(true, "OK", parcels, 200));
    }

    @GetMapping("/riders/{riderId}/parcels")
    public ResponseEntity<?> listRiderParcels(
            @PathVariable UUID riderId,
            @RequestParam(defaultValue = "pickup") String role) {
        List<ParcelResponse> parcels = parcelService.getRiderParcels(riderId, role);
        return ResponseEntity.ok(new ApiResponse<>(true, "OK", parcels, 200));
    }

    // ═══════════════════════════════════════════════════════════════
    // PICKUP FLOW (Seller → Rider → Warehouse)
    // ═══════════════════════════════════════════════════════════════

    /** Step 1: Admin assigns a pickup rider. OTP sent to seller via SMS. */
    @PostMapping("/parcels/assign-pickup-rider")
    public ResponseEntity<?> assignPickupRider(@RequestBody AssignPickupRiderRequest req) {
        return respond(parcelService.assignPickupRider(req));
    }

    /** Step 2: Rider at seller — enters OTP seller gave them. */
    @PostMapping("/parcels/verify-seller-otp")
    public ResponseEntity<?> verifySellerOtp(@RequestBody VerifyOtpRequest req) {
        return respond(parcelService.verifySellerOtp(req));
    }

    /**
     * Step 3: Rider arrives at warehouse — initiates handover OTP (sent to rider).
     */
    @PostMapping("/parcels/{parcelId}/initiate-warehouse-in")
    public ResponseEntity<?> initiateWarehouseIn(@PathVariable UUID parcelId) {
        return respond(parcelService.initiateWarehouseIn(parcelId));
    }

    /** Step 4: Admin enters OTP rider provided — parcel accepted AT_WAREHOUSE. */
    @PostMapping("/parcels/verify-warehouse-in")
    public ResponseEntity<?> verifyWarehouseIn(@RequestBody VerifyOtpRequest req) {
        return respond(parcelService.verifyWarehouseInOtp(req));
    }

    // ═══════════════════════════════════════════════════════════════
    // DELIVERY FLOW (Warehouse → Rider → Customer)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Step 5: Admin assigns delivery rider. Two OTPs sent: one to rider
     * (warehouse-out), one to customer.
     */
    @PostMapping("/parcels/assign-delivery-rider")
    public ResponseEntity<?> assignDeliveryRider(@RequestBody AssignPickupRiderRequest req) {
        return respond(parcelService.assignDeliveryRider(req));
    }

    /**
     * Step 6: Admin enters OTP rider presents — rider takes parcel
     * OUT_FOR_DELIVERY.
     */
    @PostMapping("/parcels/verify-warehouse-out")
    public ResponseEntity<?> verifyWarehouseOut(@RequestBody VerifyOtpRequest req) {
        return respond(parcelService.verifyWarehouseOutOtp(req));
    }

    /** Step 7: Rider at customer — enters OTP customer gives → DELIVERED. */
    @PostMapping("/parcels/verify-customer-otp")
    public ResponseEntity<?> verifyCustomerOtp(@RequestBody VerifyOtpRequest req) {
        return respond(parcelService.verifyCustomerDeliveryOtp(req));
    }

    // ═══════════════════════════════════════════════════════════════
    // SHIPMENT MANAGEMENT
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/shipments")
    public ResponseEntity<?> createShipment(
            @RequestBody CreateShipmentRequest req,
            HttpServletRequest httpReq) {
        UUID adminId = extractAdminId(httpReq);
        return respond(shipmentService.createShipment(req, adminId));
    }

    @PostMapping("/shipments/{shipmentId}/parcels")
    public ResponseEntity<?> addParcelsToShipment(
            @PathVariable UUID shipmentId,
            @RequestBody AddParcelsToShipmentRequest req) {
        return respond(shipmentService.addParcelsToShipment(shipmentId, req));
    }

    /**
     * Smart auto-assign: given an orderId, find the parcel (or create it)
     * and place it in the best available open shipment.
     */
    @PostMapping("/shipments/auto-assign")
    public ResponseEntity<?> autoAssign(@RequestBody AutoAssignRequest req) {
        return respond(shipmentService.autoAssign(req));
    }

    @PatchMapping("/shipments/{shipmentId}/status")
    public ResponseEntity<?> updateShipmentStatus(
            @PathVariable UUID shipmentId,
            @RequestParam String status) {
        return respond(shipmentService.updateShipmentStatus(shipmentId, status));
    }

    @GetMapping("/{warehouseId}/shipments")
    public ResponseEntity<?> listWarehouseShipments(
            @PathVariable UUID warehouseId,
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(new ApiResponse<>(true, "OK",
                shipmentService.getWarehouseShipments(warehouseId, status), 200));
    }

    @GetMapping("/shipments/{shipmentId}")
    public ResponseEntity<?> getShipment(@PathVariable UUID shipmentId) {
        return shipmentService.findShipmentById(shipmentId)
                .<ResponseEntity<?>>map(s -> ResponseEntity.ok(new ApiResponse<>(true, "OK", s, 200)))
                .orElse(ResponseEntity.status(404)
                        .body(new ApiResponse<>(false, "Shipment not found", null, 404)));
    }

    // ═══════════════════════════════════════════════════════════════
    // DASHBOARD
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/{warehouseId}/dashboard")
    public ResponseEntity<?> getDashboard(@PathVariable UUID warehouseId) {
        return respond(shipmentService.getDashboard(warehouseId));
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    private ResponseEntity<?> respond(ApiResponse<?> response) {
        return ResponseEntity.status(response.statusCode()).body(response);
    }

    private UUID extractAdminId(HttpServletRequest req) {
        Object id = req.getAttribute("id");
        if (id == null)
            return UUID.fromString("00000000-0000-0000-0000-000000000000");
        return id instanceof UUID u ? u : UUID.fromString(id.toString());
    }
}