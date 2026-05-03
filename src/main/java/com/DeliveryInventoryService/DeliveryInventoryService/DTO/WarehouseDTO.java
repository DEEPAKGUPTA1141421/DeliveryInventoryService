package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.OtpLog;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Shipment;
import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

public class WarehouseDTO {

    // ── Parcel creation ───────────────────────────────────────────────────

    @Data
    public static class CreateParcelRequest {
        private UUID orderId;
        private double weightKg;
        private String dimensions;
        private String description;
        private UUID originWarehouseId;
        private UUID destinationWarehouseId;
    }

    @Data
    @Builder
    public static class ParcelResponse {
        private UUID id;
        private UUID orderId;
        private double weightKg;
        private String dimensions;
        private String description;
        private UUID originWarehouseId;
        private UUID destinationWarehouseId;
        private UUID currentWarehouseId;
        private UUID shipmentId;
        private UUID pickupRiderId;
        private UUID deliveryRiderId;
        private Parcel.ParcelStatus status;
        private boolean sellerPickupOtpVerified;
        private boolean warehouseInOtpVerified;
        private boolean warehouseOutOtpVerified;
        private boolean customerDeliveryOtpVerified;
        private ZonedDateTime pickedAt;
        private ZonedDateTime arrivedAtWarehouseAt;
        private ZonedDateTime deliveredAt;
        private ZonedDateTime createdAt;
    }

    // ── Shipment creation ─────────────────────────────────────────────────

    @Data
    public static class CreateShipmentRequest {
        private String shipmentType; // LONG_HAUL, INTER_HUB, LAST_MILE
        private UUID originWarehouseId;
        private UUID destinationWarehouseId;
        private List<UUID> parcelIds; // parcels to include
        private UUID vehicleId; // optional: pre-assign vehicle
        private ZonedDateTime departureTimeEst;
        private ZonedDateTime arrivalTimeEst;
    }

    @Data
    public static class AddParcelsToShipmentRequest {
        private List<UUID> parcelIds;
    }

    @Data
    @Builder
    public static class ShipmentResponse {
        private UUID id;
        private String shipmentNo;
        private String shipmentType;
        private UUID originWarehouseId;
        private UUID destinationWarehouseId;
        private String originCity;
        private String destinationCity;
        private int parcelCount;
        private double totalWeightKg;
        private Shipment.ShipmentStatus status;
        private ZonedDateTime departureTimeEst;
        private ZonedDateTime arrivalTimeEst;
        private ZonedDateTime createdAt;
    }

    // ── Auto-assign parcel to best shipment ───────────────────────────────

    @Data
    public static class AutoAssignRequest {
        private UUID orderId; // system finds the parcel for this order
        private boolean createIfMissing; // create parcel if none exists yet
    }

    @Data
    @Builder
    public static class AutoAssignResponse {
        private UUID parcelId;
        private UUID shipmentId;
        private String shipmentNo;
        private String message;
    }

    // ── OTP flows ─────────────────────────────────────────────────────────

    @Data
    public static class AssignPickupRiderRequest {
        private UUID parcelId;
        private UUID riderId;
    }

    @Data
    public static class VerifyOtpRequest {
        private UUID parcelId;
        private String otp;
        private String performedBy; // riderId or adminId or customerId as string
    }

    @Data
    @Builder
    public static class OtpResponse {
        private boolean success;
        private String message;
        private UUID parcelId;
        private Parcel.ParcelStatus newStatus;
    }

    // ── Dashboard summary ─────────────────────────────────────────────────

    @Data
    @Builder
    public static class WarehouseDashboard {
        private UUID warehouseId;
        private String warehouseName;
        private String city;

        // Parcel counts by status
        private long parcelsCreated;
        private long parcelsAwaitingPickup;
        private long parcelsAtWarehouse;
        private long parcelsInShipment;
        private long parcelsOutForDelivery;
        private long parcelsDelivered;

        // Shipment counts
        private long shipmentsCreated;
        private long shipmentsInTransit;
        private long shipmentsArrived;

        // Active riders
        private long activeRiders;
    }

    // ── Rider handover list ───────────────────────────────────────────────

    @Data
    @Builder
    public static class RiderHandoverSummary {
        private UUID riderId;
        private String riderName;
        private String riderPhone;
        private List<ParcelResponse> parcelsToHandover;
        private int totalParcels;
    }
}