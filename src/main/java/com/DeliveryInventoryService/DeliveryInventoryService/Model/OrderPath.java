package com.DeliveryInventoryService.DeliveryInventoryService.Model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "order_paths")
@Data
public class OrderPath {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID orderId;   // Link to the order

    @ElementCollection
    @CollectionTable(name = "order_path_points", joinColumns = @JoinColumn(name = "order_path_id"))
    @Column(name = "city_name")
    private List<String> path;  // ordered list of cities from start → end

    private long totalTimeSeconds;   // total travel time for the path

    private String status = "PLANNED"; // PLANNED, IN_PROGRESS, COMPLETED

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));

    @UpdateTimestamp
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
}
