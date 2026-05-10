package com.DeliveryInventoryService.DeliveryInventoryService.Model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "vrp_batch_runs", indexes = {
        @Index(name = "idx_vbr_status",      columnList = "status"),
        @Index(name = "idx_vbr_warehouse",   columnList = "warehouse_id"),
        @Index(name = "idx_vbr_started_at",  columnList = "started_at")
})
@Data
@NoArgsConstructor
public class VrpBatchRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TriggerType triggeredBy;

    @Column(name = "warehouse_id", nullable = false)
    private UUID warehouseId;

    @Column(name = "orders_input")
    private int ordersInput;

    @Column(name = "riders_assigned")
    private int ridersAssigned;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status = RunStatus.RUNNING;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "started_at")
    private ZonedDateTime startedAt;

    @Column(name = "completed_at")
    private ZonedDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt;

    public VrpBatchRun(TriggerType triggeredBy, UUID warehouseId, int ordersInput) {
        this.triggeredBy = triggeredBy;
        this.warehouseId = warehouseId;
        this.ordersInput = ordersInput;
        this.startedAt = ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
    }

    public enum TriggerType {
        CRON, MANUAL
    }

    public enum RunStatus {
        RUNNING, COMPLETE, FAILED
    }
}
