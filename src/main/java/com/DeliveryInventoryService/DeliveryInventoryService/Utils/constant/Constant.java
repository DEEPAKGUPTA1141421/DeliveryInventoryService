package com.DeliveryInventoryService.DeliveryInventoryService.Utils.constant;

import java.time.Duration;

public class Constant {
    /** Geohash precision 6 → cell ≈ 1.2 km × 0.6 km */
    public static final int GEOHASH_PRECISION = 6;

    /**
     * TTL is 35 min — slightly longer than the 30-min cron cadence.
     * This guarantees no window where a lookup finds an expired key before
     * the next run has finished writing.
     */
    public static final Duration TTL = Duration.ofMinutes(35);

    /**
     * Padding added to each edge of the derived bounding box (in degrees).
     * ~0.045° ≈ 5 km — covers sellers/users just outside the warehouse cluster.
     */
    public static final double BBOX_PADDING_DEG = 0.045;

    public static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";
    public static final int[] BITS = { 16, 8, 4, 2, 1 };
    public static final String OK = "Ok";
}
