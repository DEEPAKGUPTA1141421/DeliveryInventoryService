package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import java.util.UUID;
import java.util.List;

public class AssignmentRequestDto {
    public List<UUID> parcelIds;
    public UUID originWarehouseId;
    public UUID destinationWarehouseId;
    // preference: "MIN_TIME" or "MIN_COST" or weights
    public String preference;
}
