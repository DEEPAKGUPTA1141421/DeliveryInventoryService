package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
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
        private UUID vehicleId;
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
        private ZonedDateTime updatedAt;
        /** Full parcel list — populated so the frontend can show OTP checkpoints and route tracking. */
        private List<ParcelResponse> parcels;
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

    // ── Warehouse Ops: Unassigned orders grouped by destination city ──────

    @Data
    @Builder
    public static class UnassignedOrderItem {
        private UUID id;
        private String orderNo;
        private String destAddress;
        private String destCity;
        private double weightKg;
        private Double destLat;
        private Double destLng;
    }

    @Data
    @Builder
    public static class UnassignedOrderGroup {
        private String destCity;
        private int count;
        private double totalWeightKg;
        private List<UnassignedOrderItem> orders;
    }

    @Data
    public static class BulkCreateParcelsRequest {
        private List<UUID> orderIds;
    }

    @Data
    @Builder
    public static class BulkCreateParcelsResponse {
        private int created;
        private int skipped;
        private List<ParcelResponse> parcels;
        private List<String> errors;
    }

    // ── Warehouse Ops: Shipment suggestions ───────────────────────────────

    @Data
    @Builder
    public static class ShipmentSuggestion {
        private UUID destWarehouseId;
        private String destCity;
        private int parcelCount;
        private double totalWeightKg;
        private List<UUID> parcelIds;
    }

    @Data
    public static class BulkCreateShipmentRequest {
        private UUID destWarehouseId;
        private List<UUID> parcelIds;
        private UUID vehicleId;          // optional
        private String shipmentType;     // optional, default LAST_MILE / INTER_HUB
    }

    // ── Warehouse Ops: Vehicles ───────────────────────────────────────────

    @Data
    @Builder
    public static class VehicleSummary {
        private UUID id;
        private String vehicleType;
        private String vehicleNumber;
        private double capacityKg;
        private int maxParcels;
        private String status;
    }

    @Data
    public static class AssignVehicleRequest {
        private UUID vehicleId;
    }

    @Data
    @Builder
    public static class AssignVehicleResponse {
        private UUID shipmentId;
        private UUID vehicleId;
        private UUID routeId;
        private ShipmentResponse shipment;
    }

    // ── Warehouse Ops: Route Assignments grouped by rider ─────────────────

    @Data
    @Builder
    public static class RouteAssignmentStop {
        private UUID assignmentId;
        private UUID orderId;
        private int sequenceNumber;
        private String status;
        private String destAddress;
        private String destCity;
        private Double destLat;
        private Double destLng;
        private double weightKg;
    }

    @Data
    @Builder
    public static class RiderAssignmentsBundle {
        private UUID riderId;
        private String riderName;
        private String riderPhone;
        private double currentLat;
        private double currentLng;
        private int totalStops;
        private int completed;
        private List<RouteAssignmentStop> assignments;
    }

    // ── Receive Order at Warehouse (rider handover, OTP-secured) ──────────

    @Data
    @Builder
    public static class ReceiveOrderLookup {
        private UUID orderId;
        private String orderNo;
        private Order.OrderStatus orderStatus;
        private UUID parcelId;
        private Parcel.ParcelStatus parcelStatus;
        private double weightKg;
        private String originCity;
        private String destCity;
        private UUID currentWarehouseId;
        private UUID destinationWarehouseId;

        // Rider currently holding the parcel
        private UUID riderId;
        private String riderName;
        private String riderPhoneMasked; // "+91******3210"

        private boolean canReceive;       // true → admin may initiate warehouse-in
        private boolean alreadyReceived;  // already AT_WAREHOUSE here
        private String reason;            // human-readable reason if !canReceive
    }

    @Data
    public static class InitiateReceiveRequest {
        private String orderNo;
    }

    @Data
    public static class VerifyReceiveRequest {
        private String orderNo;
        private String otp;
        private String performedBy;
    }

    // ── Orders at warehouse ───────────────────────────────────────────────

    @Data
    @Builder
    public static class OrderSummary {
        private UUID id;
        private String orderNo;
        private String originAddress;
        private String originCity;
        private String destAddress;
        private String destCity;
        private double weightKg;
        private Order.OrderStatus status;
        private Order.ServiceType serviceType;
        private Order.OrderPriority priority;
        private UUID wareHouseId;
        private ZonedDateTime placedAt;
        private ZonedDateTime createdAt;
        // Rider info — populated when riderId is set
        private UUID riderId;
        private String riderName;
        private String riderPhone;
        private String riderCity;
        private String riderStatus;
        private Double riderLat;
        private Double riderLng;
    }

    // ── Batch handover session (one OTP per rider, scan per order) ───────

    @Data
    public static class StartHandoverSessionRequest {
        private UUID riderId;
    }

    @Data
    @Builder
    public static class StartHandoverSessionResponse {
        private String sessionId;
        private String riderName;
        private String riderPhoneMasked;
        private String message;
    }

    @Data
    public static class VerifyHandoverOtpRequest {
        private String otp;
    }

    @Data
    public static class ScanOrderRequest {
        private String orderNo;
    }

    @Data
    @Builder
    public static class ScannedOrderItem {
        private String orderNo;
        private UUID orderId;
        private String destCity;
        private double weightKg;
    }

    @Data
    @Builder
    public static class ScanOrderResponse {
        private String orderNo;
        private UUID orderId;
        private boolean accepted;
        private String reason;
        private int totalScanned;
        private List<ScannedOrderItem> scannedOrders;
    }

    @Data
    @Builder
    public static class HandoverSessionStatus {
        private String sessionId;
        private UUID riderId;
        private String riderName;
        private String status;
        private int totalScanned;
        private List<ScannedOrderItem> scannedOrders;
    }

    @Data
    @Builder
    public static class ConfirmHandoverResponse {
        private int accepted;
        private int skipped;
        private List<String> acceptedOrderNos;
        private List<String> skippedOrderNos;
        private List<String> errors;
    }

    // ── Update payloads ────────────────────────────────────────────────────

    @Data
    public static class UpdateParcelRequest {
        private Double weightKg;
        private String dimensions;
        private String description;
        private UUID destinationWarehouseId;
    }

    @Data
    public static class UpdateShipmentRequest {
        private UUID vehicleId;
        private String shipmentType;       // LONG_HAUL / INTER_HUB / LAST_MILE
        private String departureTimeEst;   // ISO-8601
        private String arrivalTimeEst;
        private Double costEstimate;
    }
}