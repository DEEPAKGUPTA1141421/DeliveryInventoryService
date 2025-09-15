package com.DeliveryInventoryService.DeliveryInventoryService.Model;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

@Entity
@Table(name = "orders")
@Data

public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false)

    private UUID customerId;
    @Column(nullable = false)
    private UUID bookingId;

    private UUID riderId;
    // assigned rider
    private String originAddress;
    private double originLat;
    private double originLng;

    private String destAddress;
    private double destLat;
    private double destLng;

    private double weightKg; // in kilograms

    @Column(nullable = false)
    @Enumerated(jakarta.persistence.EnumType.STRING)
    private ServiceType serviceType = ServiceType.STANDARD; // STANDARD, EXPRESS

    public enum ServiceType {
        STANDARD, EXPRESS
    }

    private ZonedDateTime placedAt = ZonedDateTime
            .now(ZoneId.of("Asia/Kolkata"));;
    private ZonedDateTime expectedDeliveryDate;

    @Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status = Status.CREATED; // CREATED, PICKUP_SCHEDULED, IN_TRANSIT, DELIVERED

    public enum Status {
        CREATED, PICKUP_SCHEDULED, PICKED, WAREHOUSE, IN_TRANSIT, DELIVERED, CANCELLED
    }

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt = ZonedDateTime
            .now(ZoneId.of("Asia/Kolkata"));

    @UpdateTimestamp
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
}

// yuuy ruh fjhuhuri gyhu efjgygy