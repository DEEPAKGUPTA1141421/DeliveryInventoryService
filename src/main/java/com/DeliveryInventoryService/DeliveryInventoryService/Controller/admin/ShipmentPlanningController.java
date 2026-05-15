package com.DeliveryInventoryService.DeliveryInventoryService.Controller.admin;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ShipmentPlanDTO.ExecutePlanRequest;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.ShipmentPlanningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * ShipmentPlanningController
 * ───────────────────────────
 * Base path: /api/v1/admin/warehouse/{warehouseId}/shipment-plan
 *
 *   GET  /                   → generate & cache a shipment plan (15 min TTL)
 *   POST /{planId}/execute   → commit a cached plan to the database
 */
@RestController
@RequestMapping("/api/v1/admin/warehouse/{warehouseId}/shipment-plan")
@RequiredArgsConstructor
@Slf4j
public class ShipmentPlanningController {

    private final ShipmentPlanningService planningService;

    @GetMapping
    public ResponseEntity<?> generatePlan(@PathVariable UUID warehouseId) {
        log.info("Shipment plan requested for warehouse {}", warehouseId);
        ApiResponse<Object> resp = planningService.generatePlan(warehouseId);
        return ResponseEntity.status(resp.statusCode()).body(resp);
    }

    @PostMapping("/{planId}/execute")
    public ResponseEntity<?> executePlan(
            @PathVariable UUID warehouseId,
            @PathVariable String planId,
            @RequestBody(required = false) ExecutePlanRequest req) {
        log.info("Executing shipment plan {} for warehouse {}", planId, warehouseId);
        ApiResponse<Object> resp = planningService.executePlan(warehouseId, planId, req);
        return ResponseEntity.status(resp.statusCode()).body(resp);
    }
}
