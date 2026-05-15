package com.DeliveryInventoryService.DeliveryInventoryService.Model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "route_assignments", indexes = {
        @Index(name = "idx_ra_rider_date",      columnList = "rider_id, created_at"),
        @Index(name = "idx_ra_order_id",        columnList = "order_id"),
        @Index(name = "idx_ra_batch_run",       columnList = "batch_run_id"),
        @Index(name = "idx_ra_status",          columnList = "status"),
        @Index(name = "idx_ra_warehouse_date",  columnList = "warehouse_id, created_at")
})
@Data
@NoArgsConstructor
public class RouteAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "batch_run_id", nullable = false)
    private UUID batchRunId;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "rider_id", nullable = false)
    private UUID riderId;

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "sequence_number", nullable = false)
    private int sequenceNumber;

    @Column(name = "cluster_id", nullable = false)
    private int clusterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AssignmentStatus status = AssignmentStatus.ASSIGNED;

    @Column(name = "estimated_arrival_at")
    private ZonedDateTime estimatedArrivalAt;

    @Column(name = "picked_at")
    private ZonedDateTime pickedAt;

    @Column(name = "delivered_at")
    private ZonedDateTime deliveredAt;

    @Column(name = "failure_reason")
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt;

    public RouteAssignment(UUID batchRunId, UUID warehouseId, UUID riderId, UUID orderId,
                           int sequenceNumber, int clusterId) {
        this.batchRunId = batchRunId;
        this.warehouseId = warehouseId;
        this.riderId = riderId;
        this.orderId = orderId;
        this.sequenceNumber = sequenceNumber;
        this.clusterId = clusterId;
    }

    public enum AssignmentStatus {
        ASSIGNED, PICKED, WAREHOUSE, DELIVERED, FAILED
    }
}
