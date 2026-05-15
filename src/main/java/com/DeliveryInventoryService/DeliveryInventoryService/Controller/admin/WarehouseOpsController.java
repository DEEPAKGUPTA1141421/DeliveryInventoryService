package com.DeliveryInventoryService.DeliveryInventoryService.Controller.admin;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.WarehouseDTO.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.WarehouseOpsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * WarehouseOpsController — endpoints for the warehouse operations dashboard.
 *
 * Base: /api/v1/admin/warehouse
 *
 *  GET   /{warehouseId}/orders/unassigned                     — Tab: Orders
 *  POST  /{warehouseId}/orders/bulk-create-parcels            — Tab: Orders
 *  GET   /{warehouseId}/shipments/suggestions                 — Tab: Parcels→Shipments
 *  POST  /{warehouseId}/shipments/bulk-create                 — Tab: Parcels→Shipments
 *  GET   /{warehouseId}/vehicles/available                    — Tab: Shipments
 *  POST  /shipments/{shipmentId}/assign-vehicle               — Tab: Shipments
 *  GET   /{warehouseId}/route-assignments                     — Tab: Riders
 */
@RestController
@RequestMapping("/api/v1/admin/warehouse")
@RequiredArgsConstructor
@Slf4j
public class WarehouseOpsController {

    private final WarehouseOpsService ops;

    /**
     * GET /{warehouseId}/orders
     *
     * Returns all orders linked to this warehouse, optionally filtered by status.
     * Status values: CREATED, PICKUP_SCHEDULED, PICKED, WAREHOUSE, IN_TRANSIT, DELIVERED, CANCELLED, PENDING, ASSIGNED
     *
     * Example: GET /api/v1/admin/warehouse/5c44.../orders?status=WAREHOUSE
     */
    @GetMapping("/{warehouseId}/orders")
    public ResponseEntity<?> ordersAtWarehouse(
            @PathVariable UUID warehouseId,
            @RequestParam(required = false) String status) {
        return respond(ops.ordersAtWarehouse(warehouseId, status));
    }

    @GetMapping("/{warehouseId}/orders/unassigned")
    public ResponseEntity<?> unassignedOrders(@PathVariable UUID warehouseId) {
        return respond(ops.unassignedOrdersByCity(warehouseId));
    }

    @PostMapping("/{warehouseId}/orders/bulk-create-parcels")
    public ResponseEntity<?> bulkCreateParcels(@PathVariable UUID warehouseId,
                                               @RequestBody BulkCreateParcelsRequest req) {
        return respond(ops.bulkCreateParcels(warehouseId, req));
    }

    @GetMapping("/{warehouseId}/shipments/suggestions")
    public ResponseEntity<?> shipmentSuggestions(@PathVariable UUID warehouseId) {
        return respond(ops.shipmentSuggestions(warehouseId));
    }

    @PostMapping("/{warehouseId}/shipments/bulk-create")
    public ResponseEntity<?> bulkCreateShipment(@PathVariable UUID warehouseId,
                                                @RequestBody BulkCreateShipmentRequest req,
                                                HttpServletRequest httpReq) {
        return respond(ops.bulkCreateShipment(warehouseId, req, extractAdminId(httpReq)));
    }

    @GetMapping("/{warehouseId}/vehicles/available")
    public ResponseEntity<?> availableVehicles(@PathVariable UUID warehouseId) {
        return respond(ops.availableVehicles(warehouseId));
    }

    @PostMapping("/shipments/{shipmentId}/assign-vehicle")
    public ResponseEntity<?> assignVehicle(@PathVariable UUID shipmentId,
                                           @RequestBody AssignVehicleRequest req) {
        return respond(ops.assignVehicle(shipmentId, req));
    }

    @GetMapping("/{warehouseId}/route-assignments")
    public ResponseEntity<?> routeAssignments(@PathVariable UUID warehouseId) {
        return respond(ops.routeAssignmentsAtWarehouse(warehouseId));
    }

    // ── helpers ──────────────────────────────────────────────────────────
    private ResponseEntity<?> respond(ApiResponse<?> response) {
        return ResponseEntity.status(response.statusCode()).body(response);
    }

    private UUID extractAdminId(HttpServletRequest req) {
        Object id = req.getAttribute("id");
        if (id == null) return UUID.fromString("00000000-0000-0000-0000-000000000000");
        return id instanceof UUID u ? u : UUID.fromString(id.toString());
    }
}
