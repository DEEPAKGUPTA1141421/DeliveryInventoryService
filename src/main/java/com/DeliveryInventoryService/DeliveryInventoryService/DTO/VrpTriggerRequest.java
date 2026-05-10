package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import java.util.List;
import java.util.UUID;

public record VrpTriggerRequest(
        List<UUID> warehouseIds,  // null → all warehouses with CREATED orders
        boolean dryRun
) {}
