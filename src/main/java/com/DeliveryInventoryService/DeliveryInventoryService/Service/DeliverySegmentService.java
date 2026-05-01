package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.DeliveryEstimateResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.GeoUtils;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.GeohashService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * DeliverySegmentService
 * ───────────────────────
 * Orchestrates the 3-segment delivery distance and ETA calculation
 * for a shop → user delivery.
 *
 * Segment definitions
 * ───────────────────
 *  Seg1 — First Mile  : shop lat/lng → nearest ACTIVE warehouse
 *                        Always computed live (Haversine). No cache.
 *
 *  Seg2 — Intercity   : source warehouse → destination warehouse (nearest to user)
 *                        Cached in Redis forever by (srcGeohash4, dstGeohash4).
 *                        On miss: Haversine between the two warehouses, then cached.
 *
 *  Seg3 — Last Mile   : destination warehouse → user lat/lng
 *                        Cached as running average per user cell (geohash6).
 *                        On miss: live Haversine from destWarehouse to user.
 *
 * Speed assumptions (SLA-based, not OSRM)
 * ─────────────────────────────────────────
 *  First mile (urban pickup)  : 20 kph
 *  Intercity (highway)        : 60 kph
 *  Last mile (dense urban)    : 15 kph
 *
 * Processing buffers
 * ──────────────────
 *  Source warehouse processing : 120 min
 *  Dest warehouse processing   : 60 min   (skipped if same-city, seg2 < 5 km)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliverySegmentService {

    private final WarehouseRepository warehouseRepository;
    private final GeohashService geohashService;
    private final GeohashCacheService geohashCacheService;

    // ── Speed constants (kph) ─────────────────────────────────────────────────
    private static final double SPEED_FIRST_MILE_KPH  = 20.0;
    private static final double SPEED_INTERCITY_KPH   = 60.0;
    private static final double SPEED_LAST_MILE_KPH   = 15.0;

    // ── Buffer minutes ────────────────────────────────────────────────────────
    private static final int BUFFER_SOURCE_WH_MIN = 120;
    private static final int BUFFER_DEST_WH_MIN   = 60;

    // ── Same-city threshold ───────────────────────────────────────────────────
    private static final double SAME_CITY_KM = 5.0;

    // ── Main entry point ──────────────────────────────────────────────────────

    /**
     * Calculate a full 3-segment delivery estimate.
     *
     * @param shopLat  shop latitude  (from ProductClientService shop listing)
     * @param shopLng  shop longitude
     * @param userLat  user's current / delivery latitude
     * @param userLng  user's current / delivery longitude
     */
    public DeliveryEstimateResponse calculate(
            double shopLat, double shopLng,
            double userLat, double userLng) {

        List<Warehouse> activeWarehouses = warehouseRepository
                .findAll()
                .stream()
                .filter(w -> w.getStatus() == Warehouse.WarehouseStatus.ACTIVE)
                .toList();

        if (activeWarehouses.isEmpty()) {
            log.warn("No active warehouses found — returning fallback estimate");
            return fallbackEstimate();
        }

        // ── Seg1: shop → nearest warehouse ───────────────────────────────────
        Warehouse srcWarehouse = nearest(activeWarehouses, shopLat, shopLng);
        double seg1Km = GeoUtils.distanceKm(
                shopLat, shopLng, srcWarehouse.getLat(), srcWarehouse.getLng());

        // ── Seg2: srcWarehouse → destWarehouse (nearest to user) ──────────────
        Warehouse destWarehouse = nearest(activeWarehouses, userLat, userLng);
        double seg2Km = resolveIntercityKm(srcWarehouse, destWarehouse);

        // ── Seg3: destWarehouse → user ────────────────────────────────────────
        String cellHash = geohashService.cellHash(userLat, userLng);
        double seg3Km = geohashCacheService.getSeg3AvgKm(cellHash)
                .orElseGet(() -> GeoUtils.distanceKm(
                        destWarehouse.getLat(), destWarehouse.getLng(), userLat, userLng));

        // ── ETA ───────────────────────────────────────────────────────────────
        boolean sameCityDelivery = seg2Km < SAME_CITY_KM;
        int etaMinutes = computeEtaMinutes(seg1Km, seg2Km, seg3Km, sameCityDelivery);
        String etaLabel = formatEtaLabel(etaMinutes);

        return DeliveryEstimateResponse.builder()
                .seg1Km(round2(seg1Km))
                .seg2Km(round2(seg2Km))
                .seg3Km(round2(seg3Km))
                .totalKm(round2(seg1Km + seg2Km + seg3Km))
                .etaMinutes(etaMinutes)
                .etaLabel(etaLabel)
                .srcWarehouseCity(srcWarehouse.getCity())
                .destWarehouseCity(destWarehouse.getCity())
                .sameCityDelivery(sameCityDelivery)
                .build();
    }

    // ── Seg2 resolution ───────────────────────────────────────────────────────

    private double resolveIntercityKm(Warehouse src, Warehouse dest) {
        if (src.getId().equals(dest.getId())) return 0.0;

        String srcHash = geohashService.cityHash(src.getLat(), src.getLng());
        String dstHash = geohashService.cityHash(dest.getLat(), dest.getLng());

        return geohashCacheService.getSeg2Km(srcHash, dstHash)
                .orElseGet(() -> {
                    double km = GeoUtils.distanceKm(
                            src.getLat(), src.getLng(),
                            dest.getLat(), dest.getLng());
                    geohashCacheService.putSeg2Km(srcHash, dstHash, km);
                    return km;
                });
    }

    // ── ETA calculation ───────────────────────────────────────────────────────

    private int computeEtaMinutes(double seg1Km, double seg2Km, double seg3Km,
                                   boolean sameCityDelivery) {
        int seg1Min  = travelMinutes(seg1Km, SPEED_FIRST_MILE_KPH);
        int seg2Min  = travelMinutes(seg2Km, SPEED_INTERCITY_KPH);
        int seg3Min  = travelMinutes(seg3Km, SPEED_LAST_MILE_KPH);
        int bufferMin = sameCityDelivery
                ? BUFFER_SOURCE_WH_MIN                        // same city: skip dest-WH buffer
                : BUFFER_SOURCE_WH_MIN + BUFFER_DEST_WH_MIN; // intercity: both buffers

        return seg1Min + bufferMin + seg2Min + seg3Min;
    }

    private int travelMinutes(double distKm, double speedKph) {
        if (distKm <= 0) return 0;
        return (int) Math.ceil((distKm / speedKph) * 60);
    }

    // ── Label formatting ──────────────────────────────────────────────────────

    /**
     * Converts raw eta minutes into a human-readable delivery label.
     *
     * < 60 min      → "X mins"
     * 60–119 min    → "About 1 hr"
     * 120–1439 min  → "X hrs" / "Today" / "Tomorrow" depending on context
     * 1440–2879 min → "2 days"
     * ≥ 2880 min    → "X days"
     */
    private String formatEtaLabel(int minutes) {
        if (minutes < 60)   return minutes + " mins";
        if (minutes < 120)  return "About 1 hr";
        if (minutes < 480)  return (minutes / 60) + " hrs";
        if (minutes < 1440) return "Today";
        if (minutes < 2880) return "Tomorrow";
        int days = (int) Math.ceil(minutes / 1440.0);
        return days + " days";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Warehouse nearest(List<Warehouse> warehouses, double lat, double lng) {
        return warehouses.stream()
                .min(Comparator.comparingDouble(
                        w -> GeoUtils.distanceKm(w.getLat(), w.getLng(), lat, lng)))
                .orElse(warehouses.get(0));
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private DeliveryEstimateResponse fallbackEstimate() {
        return DeliveryEstimateResponse.builder()
                .seg1Km(0).seg2Km(0).seg3Km(0).totalKm(0)
                .etaMinutes(2880)
                .etaLabel("2 days")
                .sameCityDelivery(false)
                .build();
    }
}
