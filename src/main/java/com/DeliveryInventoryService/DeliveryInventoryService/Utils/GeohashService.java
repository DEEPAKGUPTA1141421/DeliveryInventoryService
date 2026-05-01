package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import ch.hsr.geohash.BoundingBox;
import ch.hsr.geohash.GeoHash;
import org.springframework.stereotype.Component;

/**
 * GeohashService
 * ───────────────
 * Encodes lat/lng coordinates into geohash strings at two precisions:
 *
 *   cityHash  (precision 4) ≈ 40 × 20 km cell  — intercity segment (Seg2)
 *   cellHash  (precision 6) ≈ 1.2 × 0.6 km cell — last-mile segment (Seg3)
 *
 * These hashes are used as keys in the Redis delivery distance cache:
 *   delivery:seg2:{srcHash4}:{dstHash4}
 *   delivery:seg3:{userHash6}
 */
@Component
public class GeohashService {

    /** Precision for city-level bucketing (~40 km cell). Used for Seg2 intercity cache. */
    public static final int CITY_PRECISION = 4;

    /** Precision for neighbourhood-level bucketing (~1.2 km cell). Used for Seg3 last-mile cache. */
    public static final int CELL_PRECISION = 6;

    /**
     * Returns the city-level geohash (precision 4) for a given coordinate.
     * Two points in the same city will typically share this hash.
     */
    public String cityHash(double lat, double lon) {
        return GeoHash.geoHashStringWithCharacterPrecision(lat, lon, CITY_PRECISION);
    }

    /**
     * Returns the neighbourhood-level geohash (precision 6) for a given coordinate.
     * Used to average last-mile distances per delivery cell.
     */
    public String cellHash(double lat, double lon) {
        return GeoHash.geoHashStringWithCharacterPrecision(lat, lon, CELL_PRECISION);
    }

    /**
     * Decodes a geohash string back to its centroid [lat, lon].
     * Used to compute Haversine distance between two city hashes.
     *
     * @return double[]{lat, lon}
     */
    public double[] decode(String hash) {
        BoundingBox bb = GeoHash.fromGeohashString(hash).getBoundingBox();
        return new double[]{
                (bb.getMinLat() + bb.getMaxLat()) / 2.0,
                (bb.getMinLon() + bb.getMaxLon()) / 2.0
        };
    }
}
