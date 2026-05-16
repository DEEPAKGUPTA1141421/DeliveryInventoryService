package com.DeliveryInventoryService.DeliveryInventoryService.Model;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ephemeral shipment-transfer session stored in Redis (not a DB entity).
 * TTL: 2 hours — long enough for inter-warehouse handovers at a transport hub.
 *
 * A single session covers one physical handoff event in the shipment lifecycle:
 *
 *   DISPATCH_OUT       Warehouse admin hands shipments to outgoing rider.
 *                      Status transition: ASSIGNED/CREATED → DISPATCHED
 *
 *   HAND_TO_VEHICLE    Rider hands shipments to vehicle/transporter at bus stand.
 *                      Status transition: DISPATCHED → IN_TRANSIT
 *
 *   RECEIVE_FROM_VEHICLE  Rider receives shipments from vehicle at destination city.
 *                         Status transition: IN_TRANSIT → AT_DESTINATION  (triggers multi-hop parcel logic)
 *
 *   RECEIVE_IN         Warehouse admin receives shipments from incoming rider.
 *                      Status transition: AT_DESTINATION → DELIVERED
 *
 * Because the same shipment travels multiple legs (A → B → C), a single shipment
 * participates in multiple sessions over its lifetime — one per handoff.
 *
 * Session lifecycle (mirrors HandoverSession):
 *   PENDING_OTP  → initiator starts session, OTP sent to the receiving party
 *   ACTIVE       → receiving party gives OTP to initiator; scan shipment numbers
 *   COMPLETED    → confirmed; all scanned shipments status-advanced atomically
 */
@Data
public class ShipmentTransferSession {

    private String sessionId;
    private SessionType sessionType;
    private UUID warehouseId;       // context warehouse (admin's location)

    // Rider identity — set for DISPATCH_OUT / RECEIVE_IN
    private UUID riderId;
    private String riderName;

    // Set for rider-initiated sessions (HAND_TO_VEHICLE / RECEIVE_FROM_VEHICLE).
    // Ownership is checked against this field instead of warehouseId.
    private UUID ownerRiderId;

    // Other-party info — set for HAND_TO_VEHICLE / RECEIVE_FROM_VEHICLE (vehicle driver),
    // also used as fallback display name for rider sessions
    private String partyName;
    private String partyPhone;      // raw phone (stored so OTP resend is possible)

    private String otp;
    private Status status = Status.PENDING_OTP;
    private List<String> scannedShipmentNos = new ArrayList<>();
    private Instant createdAt = Instant.now();

    public enum SessionType {
        DISPATCH_OUT,
        HAND_TO_VEHICLE,
        RECEIVE_FROM_VEHICLE,
        RECEIVE_IN
    }

    public enum Status {
        PENDING_OTP,
        ACTIVE,
        COMPLETED
    }
}
