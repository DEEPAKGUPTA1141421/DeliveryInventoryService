package com.DeliveryInventoryService.DeliveryInventoryService.Model;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "shipments")
@Data
public class Shipment {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Human-readable shipment number, e.g. SH-204851 */
    @Column(unique = true, updatable = false)
    private String shipmentNo;

    private String shipmentType; // LONG_HAUL, INTER_HUB, LAST_MILE

    /** Physical vehicle currently transporting this shipment. Null until dispatched; updated as shipment transfers between vehicles. */
    private UUID vehicleId;

    /** Rider assigned to physically transport this shipment (set at creation time). */
    private UUID riderId;

    /** Planned schedule this shipment is booked on. Always set at creation/scheduling time. */
    private UUID vehicleScheduleId;

    private UUID originWarehouseId;

    private UUID destinationWarehouseId;

    private String originCity;

    private String destinationCity;

    private ZonedDateTime departureTimeEst;

    private ZonedDateTime arrivalTimeEst;

    private double costEstimate;

    private long timeEstimateSeconds;

    /** Admin who created this shipment */
    private UUID createdByAdminId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private ShipmentStatus status = ShipmentStatus.CREATED;

    public enum ShipmentStatus {
        CREATED, ASSIGNED, DISPATCHED, IN_TRANSIT, AT_DESTINATION, DELIVERED, CANCELLED
    }

    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Parcel> parcels = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));

    @UpdateTimestamp
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));

    public static String generateShipmentNo() {
        int n = 100000 + new java.util.Random().nextInt(900000);
        return "SH-" + n;
    }
}