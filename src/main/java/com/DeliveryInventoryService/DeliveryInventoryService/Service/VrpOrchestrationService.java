package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order.OrderStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Rider;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Rider.RiderStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.RouteAssignment;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.VrpBatchRun;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.VrpBatchRun.RunStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.VrpBatchRun.TriggerType;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.OrderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RiderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RouteAssignmentRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.VrpBatchRunRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.KMeansClustering;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.OsrmDistanceMatrix;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class VrpOrchestrationService {

    private static final int RIDER_CAPACITY_KG = 50;
    private static final String ZONE_ID = "Asia/Kolkata";

    private final OrderRepository orderRepository;
    private final RiderRepository riderRepository;
    private final RouteAssignmentRepository assignmentRepository;
    private final VrpBatchRunRepository batchRunRepository;
    private final KMeansClustering kMeans;
    private final OsrmDistanceMatrix osrmDistanceMatrix;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Entry point — called by cron job or manual trigger.
     * Accepts explicit warehouseIds or auto-discovers all warehouses that have
     * CREATED orders.
     * Returns one batchRunId per warehouse queued.
     */
    public List<UUID> triggerBatch(List<UUID> warehouseIds, TriggerType triggeredBy) {
        List<UUID> targets = resolveWarehouses(warehouseIds);
        if (targets.isEmpty()) {
            log.info("VRP trigger: no CREATED orders found in any warehouse");
            return List.of();
        }

        List<UUID> batchRunIds = new ArrayList<>();
        for (UUID warehouseId : targets) {
            List<Order> orders = orderRepository.findByWareHouseIdAndStatusIn(
                    warehouseId, List.of(OrderStatus.CREATED));

            if (orders.isEmpty())
                continue;

            if (batchRunRepository.existsByStatusAndWarehouseId(RunStatus.RUNNING, warehouseId)) {
                log.warn("VRP already running for warehouseId={} — skipping", warehouseId);
                continue;
            }

            VrpBatchRun run = batchRunRepository.save(
                    new VrpBatchRun(triggeredBy, warehouseId, orders.size()));
            batchRunIds.add(run.getId());

            runWarehouseAsync(run.getId(), warehouseId, orders);
        }
        return batchRunIds;
    }

    @Async("vrpTaskExecutor")
    public void runWarehouseAsync(UUID batchRunId, UUID warehouseId, List<Order> orders) {
        log.info("VRP [{}] starting: warehouseId={}, orders={}", batchRunId, warehouseId, orders.size());
        try {
            processWarehouse(batchRunId, warehouseId, orders);
        } catch (Exception e) {
            log.error("VRP [{}] failed for warehouseId={}: {}", batchRunId, warehouseId, e.getMessage(), e);
            markFailed(batchRunId, e.getMessage());
        }
    }

    @Transactional
    protected void processWarehouse(UUID batchRunId, UUID warehouseId, List<Order> orders) throws Exception {
        // 1. Build distance matrix (Redis-cached inside OsrmDistanceMatrix)
        double[][] distMatrix = osrmDistanceMatrix.buildDistanceMatrix(orders);
        if (distMatrix.length == 0) {
            throw new IllegalStateException("Empty distance matrix for warehouseId=" + warehouseId);
        }

        // 2. Load ACTIVE riders for this warehouse, sorted by ID for stable assignment
        List<UUID> riderPool = riderRepository
                .findByWarehouseIdAndStatus(warehouseId, RiderStatus.ACTIVE)
                .stream()
                .map(Rider::getId)
                .sorted()
                .collect(Collectors.toList());

        if (riderPool.isEmpty()) {
            throw new IllegalStateException(
                    "No ACTIVE riders assigned to warehouseId=" + warehouseId + " — cannot run VRP");
        }

        // 3. Cluster + VRP solve — city is derived from the first order's originCity
        String city = orders.get(0).getOriginCity();
        Map<Integer, Map<Integer, List<Order>>> clusterRoutes = kMeans.clusterAndSolveVRP(orders, distMatrix,
                RIDER_CAPACITY_KG, city);

        // 4. Persist assignments + update order statuses
        int totalRiders = 0;
        List<RouteAssignment> toSave = new ArrayList<>();

        // globalSlot increments across all clusters — each slot maps to one rider in
        // the pool
        int globalSlot = 0;
        for (Map.Entry<Integer, Map<Integer, List<Order>>> clusterEntry : clusterRoutes.entrySet()) {
            int clusterId = clusterEntry.getKey();

            for (Map.Entry<Integer, List<Order>> riderEntry : clusterEntry.getValue().entrySet()) {
                List<Order> route = riderEntry.getValue();

                if (globalSlot >= riderPool.size()) {
                    throw new IllegalStateException(
                            "Rider pool exhausted: need slot=" + globalSlot +
                            " but warehouseId=" + warehouseId + " only has " + riderPool.size() + " ACTIVE riders");
                }
                UUID riderId = riderPool.get(globalSlot);
                globalSlot++;

                for (int seq = 0; seq < route.size(); seq++) {
                    Order order = route.get(seq);
                    toSave.add(new RouteAssignment(batchRunId, riderId, order.getId(), seq + 1, clusterId));
                    order.setStatus(OrderStatus.ASSIGNED);
                    order.setRiderId(riderId);
                }
                totalRiders++;
            }
        }

        assignmentRepository.saveAll(toSave);
        orderRepository.saveAll(orders);

        // 5. Mark run complete
        VrpBatchRun run = batchRunRepository.findById(batchRunId).orElseThrow();
        run.setStatus(RunStatus.COMPLETE);
        run.setRidersAssigned(totalRiders);
        run.setCompletedAt(ZonedDateTime.now(ZoneId.of(ZONE_ID)));
        batchRunRepository.save(run);

        // 6. Broadcast to warehouse dashboard via WebSocket
        messagingTemplate.convertAndSend(
                "/topic/warehouse/" + warehouseId + "/live",
                Map.of(
                        "type", "VRP_COMPLETE",
                        "batchRunId", batchRunId.toString(),
                        "warehouseId", warehouseId.toString(),
                        "ridersAssigned", totalRiders,
                        "ordersAssigned", toSave.size()));

        log.info("VRP [{}] complete: warehouseId={}, riders={}, stops={}",
                batchRunId, warehouseId, totalRiders, toSave.size());
    }

    // ---------------------------------------------------------------
    // HELPERS
    // ---------------------------------------------------------------

    private List<UUID> resolveWarehouses(List<UUID> requested) {
        if (requested != null && !requested.isEmpty())
            return requested;
        return orderRepository.findDistinctWareHouseIdsByStatus(OrderStatus.CREATED);
    }

    private void markFailed(UUID batchRunId, String reason) {
        batchRunRepository.findById(batchRunId).ifPresent(run -> {
            run.setStatus(RunStatus.FAILED);
            run.setErrorMessage(reason);
            run.setCompletedAt(ZonedDateTime.now(ZoneId.of(ZONE_ID)));
            batchRunRepository.save(run);
        });
    }

}
// huihdbjhjd