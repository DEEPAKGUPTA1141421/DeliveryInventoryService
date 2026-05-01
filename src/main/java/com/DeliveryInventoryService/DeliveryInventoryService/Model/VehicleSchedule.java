package com.DeliveryInventoryService.DeliveryInventoryService.Model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "vehicle_schedules")
@Data
public class VehicleSchedule {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private UUID vehicleId; // optional external carrier might not have vehicleId

    @Enumerated(EnumType.STRING)
    private ScheduleType scheduleType;
    private String originCity;
    private String destinationCity;

    // Only for FIXED (bus/train): known departure/arrival
    private ZonedDateTime departureDateTime;
    private ZonedDateTime arrivalDateTime;

    private double originLat;
    private double originLng;
    private double destLat;
    private double destLng;

    private double capacityRemainingKg;
    private int capacityRemainingParcels;

    @Enumerated(EnumType.STRING)
    private ScheduleStatus status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));

    @UpdateTimestamp
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));

    public enum ScheduleType {
        FIXED,
        ON_DEMAND
    }

    public enum ScheduleStatus {
        SCHEDULED,
        DEPARTED,
        ARRIVED,
        CANCELLED
    }
}
