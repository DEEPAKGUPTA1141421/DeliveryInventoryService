package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import java.util.List;
import java.util.UUID;

public record VrpTriggerResponse(
        List<UUID> batchRunIds,
        int warehousesQueued,
        int estimatedCompletionSec
) {}
