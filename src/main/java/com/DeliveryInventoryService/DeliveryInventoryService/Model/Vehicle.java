package com.DeliveryInventoryService.DeliveryInventoryService.Model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "vehicles")
@Data
public class Vehicle {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String licenseNumber;

    private String vehicleNumber;

    private List<String> images = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private VehicleType vehicleType;

    @OneToOne
    @JoinColumn(name = "rider_id", referencedColumnName = "id")
    @JsonIgnore
    private Rider rider;

    private double capacityKg;
    private int maxParcels;

    @Enumerated(EnumType.STRING)
    private Status status = Status.ACTIVE;

    @Column(nullable = false)
    private String ratePerKm; // business config

    // home warehouse id (nullable)
    private UUID homeWarehouseId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));

    @UpdateTimestamp
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));

    public enum VehicleType {
        FOUR_WHEELER,
        AUTO,
        PICKUP,
        TRAIN,
        BUS,
        MOTORCYCLE
    }

    public enum Status {
        ACTIVE,
        IN_MAINTENANCE,
        OFFLINE
    }
}

// huihiu gyuyuhbhjbh hhh bh bhh hhhhbhhh

// nju huuhuuuuuhyuuyuuh hkhuk ygyy gtg ftt tf ftft