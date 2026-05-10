package com.DeliveryInventoryService.DeliveryInventoryService.DTO.zone;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.ServiceZone;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class ServiceZoneRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String city;

    private String description;

    @NotNull
    private ServiceZone.ZoneShapeType shapeType;

    @NotNull
    private ServiceZone.ZoneTarget target;

    // Required when shapeType = CIRCLE
    private Double centerLat;
    private Double centerLng;
    private Double radiusMeters;

    // Required when shapeType = POLYGON (minimum 3 points)
    private List<LatLngPoint> polygonPoints;
}
