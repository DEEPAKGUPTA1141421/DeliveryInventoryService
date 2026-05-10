package com.DeliveryInventoryService.DeliveryInventoryService.Controller;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.VrpTriggerRequest;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.VrpTriggerResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.VrpBatchRun;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.VrpBatchRun.TriggerType;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.VrpBatchRunRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.VrpOrchestrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/vrp")
@RequiredArgsConstructor
public class VrpTriggerController {

    private final VrpOrchestrationService orchestrationService;
    private final VrpBatchRunRepository batchRunRepository;

    /**
     * POST /api/v1/admin/vrp/trigger
     * Manually triggers VRP for the given cities (or all cities if omitted).
     */
    @PostMapping("/trigger")
    public ResponseEntity<ApiResponse<VrpTriggerResponse>> trigger(
            @RequestBody(required = false) VrpTriggerRequest request) {

        List<UUID> warehouseIds = request != null ? request.warehouseIds() : null;

        List<UUID> runIds = orchestrationService.triggerBatch(warehouseIds, TriggerType.MANUAL);

        if (runIds.isEmpty()) {
            return ResponseEntity.ok(new ApiResponse<>(false, "No warehouses with CREATED orders found", null, 200));
        }

        VrpTriggerResponse body = new VrpTriggerResponse(runIds, runIds.size(), 30);
        return ResponseEntity.accepted()
                .body(new ApiResponse<>(true, "VRP batch queued", body, 202));
    }

    /**
     * GET /api/v1/admin/vrp/runs/{batchRunId}
     * Returns the status of a specific batch run.
     */
    @GetMapping("/runs/{batchRunId}")
    public ResponseEntity<ApiResponse<VrpBatchRun>> getRunStatus(@PathVariable UUID batchRunId) {
        return batchRunRepository.findById(batchRunId)
                .map(run -> ResponseEntity.ok(new ApiResponse<>(true, "ok", run, 200)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/v1/admin/vrp/runs
     * Returns all currently running batch runs.
     */
    @GetMapping("/runs")
    public ResponseEntity<ApiResponse<List<VrpBatchRun>>> getRunningRuns() {
        List<VrpBatchRun> running = batchRunRepository
                .findByStatusOrderByStartedAtDesc(VrpBatchRun.RunStatus.RUNNING);
        return ResponseEntity.ok(new ApiResponse<>(true, "ok", running, 200));
    }
}
