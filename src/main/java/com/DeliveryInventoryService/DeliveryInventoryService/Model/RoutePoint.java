package com.DeliveryInventoryService.DeliveryInventoryService.Model;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "route_points")
@Data
public class RoutePoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "route_id", nullable = false)
    @JsonBackReference
    private Route route;

    @Column(nullable = false)
    private Integer sequence; // order of stop in the route

    @Column(nullable = false)
    private String locationName;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    private String City;

    @Enumerated(EnumType.STRING)
    @Column(name = "point_type")
    private PointType pointType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private PointStatus status = PointStatus.PENDING;

    public enum PointType {
        PICKUP, DELIVERY, WAREHOUSE
    }

    public enum PointStatus {
        PENDING, IN_TRANSIT, COMPLETED, FAILED
    }

    @CreationTimestamp
    @Column(name = "created_at", nullable = true, updatable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));

    @UpdateTimestamp
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
}
/// hg bhhud hgyiud hihidbhjheygbheyhe nhh jguytgybhjguyg