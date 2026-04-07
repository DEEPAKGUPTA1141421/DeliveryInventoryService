package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.UUID;

/**
 * Value stored in Redis for every geohash cell.
 *
 * Key pattern (seller side):
 * eta:seller:{geohash} → GeohashEtaEntry
 *
 * Key pattern (user / delivery side):
 * eta:user:{geohash} → GeohashEtaEntry
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class GeohashEtaEntry implements Serializable {

    /** Nearest warehouse id */
    private UUID warehouseId;

    /** Human-readable warehouse name (for logging / debug) */
    private String warehouseName;

    /** Straight-line distance in km (Haversine) */
    private double distanceKm;

    /** Road travel time in seconds (from OSRM) */
    private long travelTimeSeconds;

    /** Road distance in metres (from OSRM) */
    private double roadDistanceMetres;

    /** When this entry was last refreshed (epoch seconds) */
    private long cachedAt;
}