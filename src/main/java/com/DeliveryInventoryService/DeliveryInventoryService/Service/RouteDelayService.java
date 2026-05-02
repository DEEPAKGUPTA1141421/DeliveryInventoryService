package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.RoutePoint;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RoutePointRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.tracking.VehicleLocationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Core delay computation engine.
 *
 * For a vehicle currently at (lat, lon) traveling leg fromSeq → toSeq:
 *
 *   inferredSpeed  = scheduledDistance / scheduledDuration
 *   remainingDist  = haversine(currentPos, toPoint)
 *   expectedArrival = now + (remainingDist / inferredSpeed)
 *   delaySeconds   = expectedArrival − toPoint.startTime
 *
 * Results written to:
 *   Redis  → route:point:{toPointId}:expected_eta  (TTL 2 h, hot reads)
 *   DB     → RoutePoint.expectedEndTime / currentLat / currentLon / delayMinutes
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RouteDelayService {

    private static final String ETA_KEY_PREFIX = "route:point:";
    private static final String ETA_KEY_SUFFIX = ":expected_eta";
    private static final long   ETA_TTL_HOURS  = 2;

    private final RoutePointRepository routePointRepository;
    private final RedisTemplate<String, Object> etaRedisTemplate;

    @Transactional
    public void processLocationUpdate(VehicleLocationEvent event) {
        List<RoutePoint> points = routePointRepository
                .findPointsByRouteIdOrdered(event.getRouteId());

        if (points.size() < 2) return;

        int fromSeq = event.getCurrentFromSequence();

        RoutePoint fromPoint = points.stream()
                .filter(p -> p.getSequence() == fromSeq)
                .findFirst().orElse(null);
        RoutePoint toPoint = points.stream()
                .filter(p -> p.getSequence() == fromSeq + 1)
                .findFirst().orElse(null);

        if (fromPoint == null || toPoint == null) {
            log.debug("Route {} seq {}: no matching leg", event.getRouteId(), fromSeq);
            return;
        }

        // ── Update current location on the FROM point ──────────────────────
        fromPoint.setCurrentLat(event.getLat());
        fromPoint.setCurrentLon(event.getLon());

        // ── Compute expected arrival at the TO point ───────────────────────
        LocalDateTime expectedArrival = computeExpectedArrival(
                event.getLat(), event.getLon(),
                fromPoint, toPoint,
                event.getTimestamp());

        if (expectedArrival == null) return;

        long delayMinutes = Duration.between(toPoint.getStartTime(), expectedArrival).toMinutes();

        // ── Update TO point ─────────────────────────────────────────────────
        toPoint.setExpectedEndTime(toPoint.getEndTime().plusMinutes(Math.max(0, delayMinutes)));
        toPoint.setDelayMinutes(Math.max(0, delayMinutes));

        routePointRepository.save(fromPoint);
        routePointRepository.save(toPoint);

        // ── Write to Redis for hot reads ────────────────────────────────────
        String redisKey = ETA_KEY_PREFIX + toPoint.getId() + ETA_KEY_SUFFIX;
        etaRedisTemplate.opsForValue().set(
                redisKey,
                toPoint.getExpectedEndTime().toString(),
                ETA_TTL_HOURS, TimeUnit.HOURS);

        if (delayMinutes > 0) {
            log.info("Route {} | {} → {} | delay {}m | expectedETA {}",
                    event.getRouteId(),
                    fromPoint.getLocationName(), toPoint.getLocationName(),
                    delayMinutes, toPoint.getExpectedEndTime());
        }
    }

    /**
     * Infers vehicle speed from the scheduled leg, then projects current position
     * forward to compute when the vehicle will reach toPoint.
     */
    private LocalDateTime computeExpectedArrival(
            double currentLat, double currentLon,
            RoutePoint fromPoint, RoutePoint toPoint,
            LocalDateTime now) {

        if (fromPoint.getEndTime() == null || toPoint.getStartTime() == null) return null;

        double scheduledDistKm = haversineKm(
                fromPoint.getLatitude(), fromPoint.getLongitude(),
                toPoint.getLatitude(),   toPoint.getLongitude());

        long scheduledSeconds = Duration.between(fromPoint.getEndTime(), toPoint.getStartTime()).getSeconds();

        if (scheduledSeconds <= 0 || scheduledDistKm <= 0) return null;

        double speedKmPerSec = scheduledDistKm / scheduledSeconds;
        double remainingKm   = haversineKm(currentLat, currentLon,
                toPoint.getLatitude(), toPoint.getLongitude());

        long remainingSeconds = (long) (remainingKm / speedKmPerSec);
        return now.plusSeconds(remainingSeconds);
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Redis key for a given RoutePoint ID — used by routing services to read. */
    public static String etaRedisKey(java.util.UUID routePointId) {
        return ETA_KEY_PREFIX + routePointId + ETA_KEY_SUFFIX;
    }
}
