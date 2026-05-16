package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.OtpLog;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Shipment;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.ShipmentLeg;
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
        private UUID riderId;  // optional: rider who will physically transport this shipment
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
        private UUID riderId;
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

    // ── Shipment Transfer Session (Dispatch / Receive) ────────────────────────
    //
    //  Session types and the shipment status transition each one commits:
    //    DISPATCH_OUT         ASSIGNED/CREATED → DISPATCHED
    //    HAND_TO_VEHICLE      DISPATCHED       → IN_TRANSIT
    //    RECEIVE_FROM_VEHICLE IN_TRANSIT       → AT_DESTINATION
    //    RECEIVE_IN           AT_DESTINATION   → DELIVERED

    @Data
    public static class StartShipmentTransferRequest {
        /** One of: DISPATCH_OUT | HAND_TO_VEHICLE | RECEIVE_FROM_VEHICLE | RECEIVE_IN */
        private String sessionType;
        /**
         * DISPATCH_OUT only: any shipment number from the batch being dispatched.
         * The rider assigned to that shipment is auto-resolved; OTP is sent to their phone.
         */
        private String referenceShipmentNo;
        /** RECEIVE_IN only: rider ID who is delivering the arriving shipments */
        private UUID riderId;
        /** Phone of the receiving party (driver/transporter) — for HAND_TO_VEHICLE / RECEIVE_FROM_VEHICLE */
        private String partyPhone;
        /** Human-readable name of the other party */
        private String partyName;
    }

    @Data
    @Builder
    public static class StartShipmentTransferResponse {
        private String sessionId;
        private String sessionType;
        private String partyName;
        private String partyPhoneMasked;
        private String message;
    }

    @Data
    public static class ScanShipmentRequest {
        private String shipmentNo;
    }

    @Data
    @Builder
    public static class ScannedShipmentItem {
        private String shipmentNo;
        private UUID shipmentId;
        private String originCity;
        private String destinationCity;
        private int parcelCount;
        private Shipment.ShipmentStatus currentStatus;
        /** Populated for /my-shipments — lets the rider verify they're handing to the right vehicle. */
        private String vehicleNumber;
        private String vehicleType;
        private String assignedDriverName;
        private String assignedDriverPhoneMasked;
    }

    @Data
    @Builder
    public static class ScanShipmentResponse {
        private String shipmentNo;
        private boolean accepted;
        private String reason;
        private int totalScanned;
        private List<ScannedShipmentItem> scannedShipments;
    }

    @Data
    @Builder
    public static class ShipmentTransferSessionStatus {
        private String sessionId;
        private String sessionType;
        private UUID riderId;
        private String partyName;
        private String partyPhoneMasked;
        private String status;
        private int totalScanned;
        private List<ScannedShipmentItem> scannedShipments;
    }

    @Data
    @Builder
    public static class ConfirmTransferResponse {
        private int processed;
        private int skipped;
        private List<String> processedShipmentNos;
        private List<String> skippedShipmentNos;
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

    // ── Shipment route legs ───────────────────────────────────────────────────
    //
    //  Returned by GET /api/v1/riders/{riderId}/shipments/{shipmentNo}/route
    //  Shows every physical hop the shipment takes from origin to destination,
    //  including which rider dispatched it and which rider received it at each hop.

    @Data
    @Builder
    public static class ShipmentLegResponse {
        private UUID id;
        private int sequence;
        private UUID fromWarehouseId;
        private UUID toWarehouseId;
        private String fromCity;
        private String toCity;
        private UUID dispatchingRiderId;
        private String dispatchingRiderName;
        private UUID receivingRiderId;
        private String receivingRiderName;
        private ShipmentLeg.ShipmentLegStatus status;
        private ZonedDateTime estimatedArrival;
        private ZonedDateTime actualArrival;
        private ZonedDateTime createdAt;
    }
}
