package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Edge {
    private String toCity;
    private long timeSeconds;
    private UUID routeId;
}
