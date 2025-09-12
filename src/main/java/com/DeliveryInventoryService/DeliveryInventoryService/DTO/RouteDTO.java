package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import java.time.LocalDateTime;
import java.util.List;

public record RouteDTO(
        String startLocation,
        String destinationLocation,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String direction,
        List<RoutePointDTO> points) {
}