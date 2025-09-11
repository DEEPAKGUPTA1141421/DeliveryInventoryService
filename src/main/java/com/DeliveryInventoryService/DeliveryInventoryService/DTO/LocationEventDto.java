package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import java.util.UUID;

public class LocationEventDto {
    public UUID vehicleId;
    public UUID driverId;
    public double lat;
    public double lng;

    public double speed;
    public double bearing;
    public double accuracy;
    public long timestampMs;
}
