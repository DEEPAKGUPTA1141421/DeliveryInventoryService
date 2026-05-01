package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * GeohashCacheService
 * ────────────────────
 * Reads and writes the two Redis caches that power the 3-segment
 * delivery distance calculation:
 *
 *  Seg2 — intercity warehouse-to-warehouse distance
 *  ─────────────────────────────────────────────────
 *  Key  : delivery:seg2:{srcCityHash4}:{dstCityHash4}
 *  Value: { "km": 342.5 }
 *  TTL  : none — city distances never change
 *
 *  Seg3 — last-mile per-neighbourhood running average
 *  ────────────────────────────────────────────────────
 *  Key  : delivery:seg3:{userCellHash6}
 *  Value: { "avgKm": 3.8, "samples": 142 }
 *  TTL  : none — updated on every completed delivery to that cell
 *
 * Uses the `etaRedisTemplate` bean (String keys, JSON values) already
 * configured in RedisConfig.java.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GeohashCacheService {

    private final RedisTemplate<String, Object> etaRedisTemplate;
    private final ObjectMapper objectMapper;

    // ── Key builders ──────────────────────────────────────────────────────────

    private static final String SEG2_PREFIX = "delivery:seg2:";
    private static final String SEG3_PREFIX = "delivery:seg3:";

    private String seg2Key(String src, String dst) {
        return SEG2_PREFIX + src + ":" + dst;
    }

    private String seg3Key(String cellHash) {
        return SEG3_PREFIX + cellHash;
    }

    // ── Seg2 — intercity ──────────────────────────────────────────────────────

    /**
     * Returns the cached intercity distance (km) between two city-level geohash cells.
     * Empty if not yet computed for this city pair.
     */
    public OptionalDouble getSeg2Km(String srcHash, String dstHash) {
        // Same city — no intercity segment
        if (srcHash.equals(dstHash)) return OptionalDouble.of(0.0);
        try {
            Object raw = etaRedisTemplate.opsForValue().get(seg2Key(srcHash, dstHash));
            if (raw == null) return OptionalDouble.empty();
            Map<?, ?> map = objectMapper.convertValue(raw, Map.class);
            Number km = (Number) map.get("km");
            return km != null ? OptionalDouble.of(km.doubleValue()) : OptionalDouble.empty();
        } catch (Exception e) {
            log.warn("Seg2 cache read failed [{}/{}]: {}", srcHash, dstHash, e.getMessage());
            return OptionalDouble.empty();
        }
    }

    /**
     * Persists the intercity distance for a city pair.
     * Called on first miss — subsequent requests hit the cache.
     */
    public void putSeg2Km(String srcHash, String dstHash, double km) {
        try {
            etaRedisTemplate.opsForValue()
                    .set(seg2Key(srcHash, dstHash), Map.of("km", km));
            // Symmetric: A→B same distance as B→A
            etaRedisTemplate.opsForValue()
                    .set(seg2Key(dstHash, srcHash), Map.of("km", km));
        } catch (Exception e) {
            log.warn("Seg2 cache write failed [{}/{}]: {}", srcHash, dstHash, e.getMessage());
        }
    }

    // ── Seg3 — last-mile per cell ─────────────────────────────────────────────

    /**
     * Returns the running-average last-mile distance (km) for a delivery cell.
     * Empty if no deliveries have been completed to this cell yet.
     */
    public OptionalDouble getSeg3AvgKm(String cellHash) {
        try {
            Object raw = etaRedisTemplate.opsForValue().get(seg3Key(cellHash));
            if (raw == null) return OptionalDouble.empty();
            Map<?, ?> map = objectMapper.convertValue(raw, Map.class);
            Number avg = (Number) map.get("avgKm");
            return avg != null ? OptionalDouble.of(avg.doubleValue()) : OptionalDouble.empty();
        } catch (Exception e) {
            log.warn("Seg3 cache read failed [{}]: {}", cellHash, e.getMessage());
            return OptionalDouble.empty();
        }
    }

    /**
     * Updates the running average for a last-mile cell after a completed delivery.
     * Called by the Kafka consumer on `delivery.completed` events (Phase 4).
     *
     * Uses cumulative moving average: newAvg = (oldAvg * n + x) / (n + 1)
     */
    public void updateSeg3(String cellHash, double actualKm) {
        try {
            Object raw = etaRedisTemplate.opsForValue().get(seg3Key(cellHash));
            double currentAvg = 0.0;
            long samples = 0;

            if (raw != null) {
                Map<?, ?> map = objectMapper.convertValue(raw, Map.class);
                currentAvg = ((Number) map.getOrDefault("avgKm", 0)).doubleValue();
                samples    = ((Number) map.getOrDefault("samples", 0)).longValue();
            }

            long newSamples = samples + 1;
            double newAvg = (currentAvg * samples + actualKm) / newSamples;

            etaRedisTemplate.opsForValue()
                    .set(seg3Key(cellHash), Map.of("avgKm", newAvg, "samples", newSamples));
        } catch (Exception e) {
            log.warn("Seg3 cache update failed [{}]: {}", cellHash, e.getMessage());
        }
    }
}
