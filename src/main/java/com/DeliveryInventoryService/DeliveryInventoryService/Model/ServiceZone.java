package com.DeliveryInventoryService.DeliveryInventoryService.Model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "service_zones")
@Data
public class ServiceZone {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String city;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ZoneShapeType shapeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ZoneTarget target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ZoneStatus status = ZoneStatus.ACTIVE;

    // CIRCLE fields
    private Double centerLat;
    private Double centerLng;
    private Double radiusMeters;

    // POLYGON fields — JSON array: [{"lat":28.5,"lng":77.1}, ...]
    @Column(columnDefinition = "TEXT")
    private String polygonPointsJson;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    public enum ZoneShapeType {
        CIRCLE, POLYGON
    }

    public enum ZoneTarget {
        USER, SELLER, BOTH
    }

    public enum ZoneStatus {
        ACTIVE, INACTIVE
    }
}
