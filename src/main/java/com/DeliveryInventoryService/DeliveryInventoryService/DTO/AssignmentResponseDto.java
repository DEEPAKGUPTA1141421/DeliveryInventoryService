package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import java.util.UUID;

public class AssignmentResponseDto {
    public UUID shipmentId;
    public UUID assignedVehicleId;
    public double estimatedCost;
    public long estimatedTimeSeconds;
    public String message;
}
