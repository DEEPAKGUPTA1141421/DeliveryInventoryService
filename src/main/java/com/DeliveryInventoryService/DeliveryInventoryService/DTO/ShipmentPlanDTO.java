package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import lombok.Builder;
import lombok.Data;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTOs for the shipment planning pipeline.
 *
 * Flow:
 *   GET  /shipment-plan          → ShipmentPlan       (read-only plan, cached 15 min)
 *   POST /shipment-plan/{id}/execute → ExecutePlanResponse (creates actual DB records)
 */
public class ShipmentPlanDTO {

    /**
     * One bin of parcels destined for the same warehouse.
     * A single destination may produce multiple groups when parcels
     * exceed one vehicle's weight/count capacity (bin-packing output).
     */
    @Data
    @Builder
    public static class PlannedShipmentGroup {
        /** 0-based index within the plan — used to reference this group in ExecutePlanRequest */
        private int groupIndex;

        private UUID destinationWarehouseId;
        private String destinationWarehouseName;
        private String destinationCity;

        /** LAST_MILE (same city) or INTER_HUB (different city) */
        private String shipmentType;

        private List<UUID> parcelIds;
        private int parcelCount;
        private double totalWeightKg;

        /** From Redis eta:city:{origin}:{dest} */
        private double distanceKm;
        private long etaSeconds;
        private boolean etaFromCache;

        private ZonedDateTime departureEst;
        private ZonedDateTime arrivalEst;

        /** Best-fit vehicle from available fleet; null if no vehicle found */
        private UUID suggestedVehicleId;
        private String suggestedVehicleNumber;
        private String suggestedVehicleType;
        private double suggestedVehicleCapacityKg;
    }

    /** Full plan returned by generatePlan(); cached in Redis under shipment:plan:{planId} */
    @Data
    @Builder
    public static class ShipmentPlan {
        private String planId;
        private UUID warehouseId;
        private String originCity;
        private ZonedDateTime generatedAt;
        private int totalParcels;
        private int totalGroups;
        private List<PlannedShipmentGroup> groups;
    }

    /** Per-group overrides the admin may supply before executing the plan */
    @Data
    public static class ExecuteGroupRequest {
        private int groupIndex;
        /** Override suggested vehicle (optional) */
        private UUID vehicleId;
        /** Override computed departure time (optional, ISO-8601) */
        private ZonedDateTime departureOverride;
    }

    /** Request body for POST /shipment-plan/{planId}/execute */
    @Data
    public static class ExecutePlanRequest {
        /** May be null or empty — means use all suggested values from the plan */
        private List<ExecuteGroupRequest> groups;
    }

    @Data
    @Builder
    public static class ExecutePlanResponse {
        private int created;
        private int skipped;
        private List<WarehouseDTO.ShipmentResponse> shipments;
        private List<String> errors;
    }
}
