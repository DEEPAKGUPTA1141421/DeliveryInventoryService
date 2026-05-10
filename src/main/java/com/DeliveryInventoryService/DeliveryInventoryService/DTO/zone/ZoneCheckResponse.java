package com.DeliveryInventoryService.DeliveryInventoryService.DTO.zone;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class ZoneCheckResponse {
    private boolean serviceable;
    private UUID zoneId;
    private String zoneName;
    private String message;
}
