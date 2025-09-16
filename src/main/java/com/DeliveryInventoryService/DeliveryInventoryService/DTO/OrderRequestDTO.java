package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class OrderRequestDTO {
    @NotNull
    private UUID customerId;

    @NotNull
    private UUID bookingId;

    private UUID riderId;
    @NotNull
    private Double distance;
    @NotNull
    private UUID wareHouseId;

    @NotNull
    private String originAddress;
    private double originLat;
    private double originLng;

    @NotNull
    private String destAddress;
    private double destLat;
    private double destLng;

    private double weightKg;

    private String serviceType; // STANDARD or EXPRESS
    private String status; // CREATED, PICKUP_SCHEDULED, etc.

    private String expectedDeliveryDate; // Optional (ISO string, frontend can send)
}
