package com.DeliveryInventoryService.DeliveryInventoryService.DTO.zone;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.ServiceZone;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
public class ServiceZoneResponse {
    private UUID id;
    private String name;
    private String city;
    private String description;
    private ServiceZone.ZoneShapeType shapeType;
    private ServiceZone.ZoneTarget target;
    private ServiceZone.ZoneStatus status;

    // CIRCLE
    private Double centerLat;
    private Double centerLng;
    private Double radiusMeters;

    // POLYGON
    private List<LatLngPoint> polygonPoints;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
