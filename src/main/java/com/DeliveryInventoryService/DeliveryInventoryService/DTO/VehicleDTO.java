package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import java.util.UUID;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Vehicle.VehicleType;

public class VehicleDTO {
    public UUID id;
    public VehicleType vehicleType;
    public String registrationNumber;
    public double capacityKg;
    public int maxParcels;
    public String status;
    public double ratePerKm;
}
