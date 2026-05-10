package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
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

    @NotBlank
    private String originAddress;

    @NotNull
    @DecimalMin(value = "-90.0",  message = "originLat must be >= -90")
    @DecimalMax(value = "90.0",   message = "originLat must be <= 90")
    private Double originLat;

    @NotNull
    @DecimalMin(value = "-180.0", message = "originLng must be >= -180")
    @DecimalMax(value = "180.0",  message = "originLng must be <= 180")
    private Double originLng;

    @NotBlank
    private String originCity;

    @NotBlank
    private String destAddress;

    @NotNull
    @DecimalMin(value = "-90.0",  message = "destLat must be >= -90")
    @DecimalMax(value = "90.0",   message = "destLat must be <= 90")
    private Double destLat;

    @NotNull
    @DecimalMin(value = "-180.0", message = "destLng must be >= -180")
    @DecimalMax(value = "180.0",  message = "destLng must be <= 180")
    private Double destLng;

    @NotBlank
    private String destCity;

    private double weightKg;

    private String serviceType; // STANDARD or EXPRESS
    private String status; // CREATED, PICKUP_SCHEDULED, etc.

    private String expectedDeliveryDate; // Optional (ISO string, frontend can send)
}
