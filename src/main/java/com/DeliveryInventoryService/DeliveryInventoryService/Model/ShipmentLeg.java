package com.DeliveryInventoryService.DeliveryInventoryService.Model;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.*;
import lombok.Data;

/**
 * Tracks every physical hop a shipment makes from origin to final destination.
 *
 * One leg = one warehouse-to-warehouse segment:
 *   sequence 0 → origin warehouse → destination warehouse (for simple single-hop)
 *   sequence 0, 1, 2 … → multi-hub long-haul routes
 *
 * Leg lifecycle mirrors the transfer session types:
 *   PENDING       → shipment created, not yet dispatched
 *   DISPATCHED    → DISPATCH_OUT session confirmed (rider picked up from warehouse)
 *   IN_TRANSIT    → HAND_TO_VEHICLE session confirmed (handed to long-haul vehicle)
 *   COMPLETED     → RECEIVE_FROM_VEHICLE session confirmed (received at destination)
 */
@Entity
@Table(name = "shipment_legs", indexes = {
    @Index(name = "idx_shipment_legs_shipment_id", columnList = "shipment_id"),
    @Index(name = "idx_shipment_legs_status",      columnList = "status")
})
@Data
public class ShipmentLeg {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** FK to shipments.id — not a JPA association to avoid circular loading. */
    @Column(name = "shipment_id", nullable = false)
    private UUID shipmentId;

    /** 0-based position in the shipment's full route. */
    @Column(nullable = false)
    private int sequence;

    @Column(name = "from_warehouse_id")
    private UUID fromWarehouseId;

    @Column(name = "to_warehouse_id")
    private UUID toWarehouseId;

    @Column(name = "from_city")
    private String fromCity;

    @Column(name = "to_city")
    private String toCity;

    /** Rider who physically carried the shipment out from the origin warehouse. */
    @Column(name = "dispatching_rider_id")
    private UUID dispatchingRiderId;

    @Column(name = "dispatching_rider_name")
    private String dispatchingRiderName;

    /** Rider who collected the shipment at the destination (set on RECEIVE_FROM_VEHICLE). */
    @Column(name = "receiving_rider_id")
    private UUID receivingRiderId;

    @Column(name = "receiving_rider_name")
    private String receivingRiderName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShipmentLegStatus status = ShipmentLegStatus.PENDING;

    public enum ShipmentLegStatus {
        PENDING, DISPATCHED, IN_TRANSIT, COMPLETED
    }

    /** Copied from shipment.arrivalTimeEst at creation; may be refined later. */
    @Column(name = "estimated_arrival")
    private ZonedDateTime estimatedArrival;

    /** Set when RECEIVE_FROM_VEHICLE session is confirmed. */
    @Column(name = "actual_arrival")
    private ZonedDateTime actualArrival;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
}
