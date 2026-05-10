package com.DeliveryInventoryService.DeliveryInventoryService.Utils.cronjob;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.VrpBatchRun.TriggerType;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.VrpOrchestrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the VRP pipeline on a cron schedule.
 * Cron is configured via application.properties:
 *   myapp.cron.batch_order_from_city_and_assign_to_rider=0 * /5 * * * *
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VrpSchedulerJob {

    private final VrpOrchestrationService orchestrationService;

    @Scheduled(cron = "${myapp.cron.batch_order_from_city_and_assign_to_rider:0 */5 * * * *}")
    public void runVrpBatch() {
        log.info("VRP cron fired — triggering batch for all warehouses with CREATED orders");
        var batchRunIds = orchestrationService.triggerBatch(null, TriggerType.CRON);
        log.info("VRP cron queued {} warehouse runs", batchRunIds.size());
    }
}
