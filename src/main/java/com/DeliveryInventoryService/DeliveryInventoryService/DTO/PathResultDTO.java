package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import java.time.LocalDateTime;
import java.util.List;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.RoutePoint;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PathResultDTO {
    private List<RoutePoint> path;
    private LocalDateTime totalMinutes;
}