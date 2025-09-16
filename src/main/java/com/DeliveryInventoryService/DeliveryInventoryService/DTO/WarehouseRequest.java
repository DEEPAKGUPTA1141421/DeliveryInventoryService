package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse.Type;
import lombok.Data;

@Data
public class WarehouseRequest {
    private String name;
    private String city;
    private String state;
    private String address;
    private double lat;
    private double lng;
    private int capacityMaxParcels;
    private double capacityMaxKg;
    private Type type; // optional, defaults to LAST_MILE_DEPOT if null
}