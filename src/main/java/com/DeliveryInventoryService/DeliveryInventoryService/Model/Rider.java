package com.DeliveryInventoryService.DeliveryInventoryService.Model;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import lombok.Data;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "riders")
@Data
public class Rider {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = true)
    private String name;

    @Column(nullable = false, unique = true) // phone must be provided and unique
    private String phone;

    @Column(unique = true) // phone must be provided and unique
    private String email;

    @Column(name = "profile_image")
    private String profileImage;

    @OneToOne(mappedBy = "rider", cascade = CascadeType.ALL)
    private Vehicle vehicle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiderStatus status = RiderStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiderStep step = RiderStep.PHONE; // default step = PHONE

    @CreationTimestamp
    @Column(name = "created_at", nullable = true)
    private ZonedDateTime createdAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));

    @UpdateTimestamp
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));

    public enum RiderStep {
        PHONE, // Phone collected
        NAME, // Name collected
        LICENSE, // License collected
        VEHICLE,
        PROFILE_IMAGE,
        VEHICLE_TYPE,
        VEHICLE_IMAGE,
        COMPLETED // Fully completed (optional, same as VEHICLE + ACTIVE)
    }

    public enum RiderStatus {
        PENDING,
        ACTIVE,
        INACTIVE,
        BLACKLISTED
    }
}

// nhu hhhj ji jj jhhuu hyyiby hyhy hiuygjg hiuyy
// gyuyigyugu gyugyu yuyybhjghjgu gyghh giygggug ggyu