package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import lombok.Builder;
import lombok.Data;

/**
 * Response for GET /api/v1/delivery/estimate
 *
 * Segment breakdown + total ETA for a shop → user delivery.
 * The Flutter app uses etaLabel directly on the shop card.
 */
@Data
@Builder
public class DeliveryEstimateResponse {

    // ── Segment distances ─────────────────────────────────────────────────────
    /** Shop → nearest source warehouse (km). */
    private double seg1Km;

    /** Source warehouse → destination warehouse, intercity (km). 0 if same city. */
    private double seg2Km;

    /** Destination warehouse → user door (km). */
    private double seg3Km;

    /** Sum of all three segments. */
    private double totalKm;

    // ── ETA ───────────────────────────────────────────────────────────────────
    /** Total estimated minutes from order placement to delivery. */
    private int etaMinutes;

    /**
     * Human-readable delivery label shown on the shop card.
     * Examples: "45 mins" | "3 hrs" | "Today" | "Tomorrow" | "2 days"
     */
    private String etaLabel;

    // ── Metadata ──────────────────────────────────────────────────────────────
    /** City of the warehouse dispatching the order. */
    private String srcWarehouseCity;

    /** City of the warehouse delivering to the user. */
    private String destWarehouseCity;

    /** True when shop and user are served by the same warehouse (no intercity leg). */
    private boolean sameCityDelivery;
}
