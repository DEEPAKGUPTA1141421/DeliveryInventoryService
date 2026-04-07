package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Cached city-to-city route info.
 * Key: eta:city:{origin}:{destination}
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CityRouteEtaEntry implements Serializable {

    /** Origin city name */
    private String originCity;

    /** Destination city name */
    private String destinationCity;

    /** Ordered list of intermediate cities (including origin & dest) */
    private List<String> stops;

    /** Total road distance in km */
    private double totalDistanceKm;

    /** Total travel time in seconds */
    private long totalTravelTimeSeconds;

    /** When cached (epoch seconds) */
    private long cachedAt;
}