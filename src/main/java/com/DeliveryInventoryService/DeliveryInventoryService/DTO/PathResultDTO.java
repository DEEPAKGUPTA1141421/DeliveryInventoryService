package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PathResultDTO {
    private List<String> path;
    private long totalMinutes;
}