package com.DeliveryInventoryService.DeliveryInventoryService.Model;

import java.security.SecureRandom;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import jakarta.persistence.EnumType;

@Entity
@Table(name = "orders")

@Data

public class Order {
    private static final SecureRandom random = new SecureRandom();

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(nullable = false)

    private UUID customerId;
    @Column(nullable = false)
    private UUID bookingId;

    private UUID wareHouseId;

    private Double distance;

    private UUID riderId;
    // assigned rider
    private String originAddress;
    private double originLat;
    private double originLng;
    private String originCity;

    private String destAddress;
    private double destLat;
    private double destLng;
    private String destCity;

    private double weightKg; // in kilograms
    @Column(unique = true, updatable = false)

    private String orderNo; // Human readable order number

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id")
    private Shipment shipment;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority")
    private OrderPriority priority = OrderPriority.NORMAL;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ServiceType serviceType = ServiceType.STANDARD; // STANDARD, EXPRESS

    public enum ServiceType {
        STANDARD, EXPRESS
    }

    @Column(name = "volume_cubic_meter")
    private Double volumeCubicMeter;

    private ZonedDateTime placedAt = ZonedDateTime
            .now(ZoneId.of("Asia/Kolkata"));;
    private ZonedDateTime expectedDeliveryDate;

    @Enumerated(EnumType.STRING)
    private OrderStatus status = OrderStatus.CREATED; // CREATED, PICKUP_SCHEDULED, IN_TRANSIT, DELIVERED

    public enum OrderStatus {
        CREATED, PICKUP_SCHEDULED, PICKED, WAREHOUSE, IN_TRANSIT, DELIVERED, CANCELLED, PENDING
    }

    public enum OrderPriority {
        LOW, NORMAL, HIGH, URGENT
    }

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt = ZonedDateTime
            .now(ZoneId.of("Asia/Kolkata"));

    @UpdateTimestamp
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));

    public static String generateOrderNo() {
        int number = 100000 + random.nextInt(900000); // always 6 digits
        return "OR" + number;
    }
}

// yuuy ruh fjhuhuri gyhu efjgygy

// nyiyuyi ygu7uyy yu7uiy ihuhihunjkhuihyuii8u yuu8yuiyi8y

// hy78y8gtu7tk7 guyuk8y7u8y y78tyyu