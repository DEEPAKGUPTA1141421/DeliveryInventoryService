package com.DeliveryInventoryService.DeliveryInventoryService.Model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.ZonedDateTime;
import java.util.UUID;

import java.time.ZoneId;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Parcel — a physical package created by the warehouse admin.
 *
 * Lifecycle:
 * CREATED → AWAITING_PICKUP (OTP sent to seller)
 * → PICKED_BY_RIDER (seller OTP verified, rider collected from seller)
 * → AT_WAREHOUSE (rider handover to warehouse, warehouse OTP verified)
 * → IN_SHIPMENT (assigned to a Shipment)
 * → IN_TRANSIT (shipment departed)
 * → AT_DEST_WAREHOUSE (arrived at destination warehouse)
 * → OUT_FOR_DELIVERY (rider picked from dest warehouse, customer OTP sent)
 * → DELIVERED (customer OTP verified)
 * → RETURNED / FAILED
 */
@Entity
@Table(name = "parcels")
@Data
public class Parcel {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // ── Link to Order ──────────────────────────────────────────────────────
    @Column(nullable = false)
    private UUID orderId;
    // ── Physical properties ────────────────────────────────────────────────
    private double weightKg;
    private String dimensions; // e.g. "30x20x15 cm"
    private String description; // item description

    // ── Warehouse tracking ─────────────────────────────────────────────────
    private UUID originWarehouseId;
    private UUID currentWarehouseId;
    private UUID destinationWarehouseId;

    // ── Shipment link ──────────────────────────────────────────────────────
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id")
    private Shipment shipment;

    // ── Rider assignments ──────────────────────────────────────────────────
    private UUID pickupRiderId; // rider who picks from seller
    private UUID deliveryRiderId; // rider who delivers to customer

    // ── OTP fields ─────────────────────────────────────────────────────────
    @Column(name = "seller_pickup_otp")
    private String sellerPickupOtp; // rider → seller: seller gives OTP to confirm pickup

    @Column(name = "seller_pickup_otp_verified")
    private boolean sellerPickupOtpVerified = false;

    @Column(name = "warehouse_in_otp")
    private String warehouseInOtp; // warehouse admin verifies rider handover

    @Column(name = "warehouse_in_otp_verified")
    private boolean warehouseInOtpVerified = false;

    @Column(name = "warehouse_out_otp")
    private String warehouseOutOtp; // rider picks from dest warehouse

    @Column(name = "warehouse_out_otp_verified")
    private boolean warehouseOutOtpVerified = false;

    @Column(name = "customer_delivery_otp")
    private String customerDeliveryOtp; // customer gives OTP to confirm delivery

    @Column(name = "customer_delivery_otp_verified")
    private boolean customerDeliveryOtpVerified = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ParcelStatus status = ParcelStatus.CREATED;

    public enum ParcelStatus {
        CREATED,
        AWAITING_PICKUP, // OTP sent to seller
        PICKED_BY_RIDER, // seller OTP verified — rider has parcel
        AT_WAREHOUSE, // warehouse admin OTP verified
        IN_SHIPMENT, // assigned to shipment
        IN_TRANSIT, // shipment in motion
        AT_DEST_WAREHOUSE, // arrived at destination warehouse
        OUT_FOR_DELIVERY, // delivery rider picked up
        DELIVERED, // customer OTP verified
        RETURNED,
        FAILED
    }

    // ── Timestamps ─────────────────────────────────────────────────────────
    private ZonedDateTime pickedAt;
    private ZonedDateTime arrivedAtWarehouseAt;
    private ZonedDateTime dispatchedAt;
    private ZonedDateTime deliveredAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));

    @UpdateTimestamp
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
}
