package com.DeliveryInventoryService.DeliveryInventoryService.Model;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ephemeral handover session stored in Redis (not a DB entity).
 * Lives for 30 minutes — enough for a rider to hand over their batch.
 *
 * Flow:
 *   PENDING_OTP  → admin starts session, OTP sent to rider
 *   ACTIVE       → rider gives OTP to admin, rider identity confirmed
 *   COMPLETED    → admin confirms batch, all scanned orders committed
 */
@Data
public class HandoverSession {

    private String sessionId;
    private UUID riderId;
    private UUID warehouseId;
    private String otp;
    private Status status = Status.PENDING_OTP;
    private List<String> scannedOrderNos = new ArrayList<>();
    private Instant createdAt = Instant.now();

    public enum Status {
        PENDING_OTP,
        ACTIVE,
        COMPLETED
    }
}
