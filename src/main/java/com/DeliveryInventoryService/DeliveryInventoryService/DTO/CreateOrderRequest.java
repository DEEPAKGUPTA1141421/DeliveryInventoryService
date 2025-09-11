package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

public class CreateOrderRequest {
    public String originAddress;
    public double originLat;
    public double originLng;
    public String destAddress;
    public double destLat;
    public double destLng;
    public double weightKg;
    public String serviceType;
}
