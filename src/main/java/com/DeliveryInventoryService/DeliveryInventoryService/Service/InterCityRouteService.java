package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.CityRouteEtaEntry;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Route;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.RoutePoint;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RouteRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.EtaRedisKeys;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.constant.Constant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Objects;

import org.springframework.transaction.annotation.Transactional;
import java.util.stream.Collectors;

/**
 * Computes scheduled travel time between every city pair and stores the result
 * in Redis under key pattern: eta:city:{origin}:{destination}
 *
 * Travel time = RoutePoint.endTime (origin departure) → RoutePoint.startTime (destination arrival).
 * Distance    = Haversine straight-line between the two stop coordinates.
 *
 * No road-routing (OSRM) is used — timing is dictated entirely by the vehicle
 * schedule stored in RoutePoint, not by road geometry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterCityRouteService {

    private final RouteRepository routeRepository;
    private final WarehouseRepository warehouseRepository;
    private final RedisTemplate<String, Object> etaRedisTemplate;

    public static final long TRANSFER_PENALTY_SECONDS = 3600L; // 1 hour loading/unloading at hub

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the cached ETA entry for a direct city pair, or null if not cached.
     */
    public CityRouteEtaEntry getCached(String originCity, String destCity) {
        String key = EtaRedisKeys.cityRouteKey(originCity, destCity);
        Object raw = etaRedisTemplate.opsForValue().get(key);
        if (raw instanceof CityRouteEtaEntry entry) {
            return entry;
        }
        return null;
    }

    /**
     * Finds the fastest scheduled route from originCity to destCity across all
     * active routes, caches it in Redis, and returns the entry.
     * Returns null if no active route covers both cities with valid timestamps.
     */
    @Transactional(readOnly = true)
    public CityRouteEtaEntry computeAndCache(String originCity, String destCity) {
        log.info("Computing city ETA: {} → {}", originCity, destCity);

        List<Route> activeRoutes = routeRepository.findByStatusWithPoints(Route.Status.ACTIVE);
        CityRouteEtaEntry best = null;

        for (Route route : activeRoutes) {
            List<RoutePoint> points = route.getPoints()
                    .stream()
                    .sorted(Comparator.comparingInt(RoutePoint::getSequence))
                    .collect(Collectors.toList());

            int originIdx = indexOfCity(points, originCity);
            int destIdx   = indexOfCity(points, destCity);

            if (originIdx < 0 || destIdx < 0 || originIdx >= destIdx) {
                continue;
            }

            List<RoutePoint> subPath = points.subList(originIdx, destIdx + 1);
            RoutePoint originPoint = subPath.get(0);
            RoutePoint destPoint   = subPath.get(subPath.size() - 1);

            if (originPoint.getEndTime() == null || destPoint.getStartTime() == null) {
                log.warn("Route {} missing timestamps for {} → {}", route.getId(), originCity, destCity);
                continue;
            }

            // Effective departure from origin + expected arrival at destination
            LocalDateTime departure         = effectiveDeparture(originPoint);
            LocalDateTime expectedArrival   = destPoint.getStartTime()
                    .plusMinutes(effectiveDelayMinutes(destPoint));
            long travelSeconds = Duration.between(departure, expectedArrival).getSeconds();

            if (travelSeconds <= 0) {
                log.warn("Route {} has invalid schedule ({}s) for {} → {}", route.getId(), travelSeconds, originCity, destCity);
                continue;
            }

            double totalKm = haversineKm(
                    originPoint.getLatitude(), originPoint.getLongitude(),
                    destPoint.getLatitude(),   destPoint.getLongitude());

            List<String> stopNames = subPath.stream()
                    .map(RoutePoint::getLocationName)
                    .collect(Collectors.toList());

            CityRouteEtaEntry candidate = new CityRouteEtaEntry(
                    originCity, destCity, stopNames, totalKm, travelSeconds, Instant.now().getEpochSecond());

            if (best == null || travelSeconds < best.getTotalTravelTimeSeconds()) {
                best = candidate;
            }
        }

        if (best != null) {
            String key = EtaRedisKeys.cityRouteKey(originCity, destCity);
            etaRedisTemplate.opsForValue().set(
                    Objects.requireNonNull(key), best, Objects.requireNonNull(Constant.TTL));
            log.info("Cached {} → {} | {:.1f} km | {}s", originCity, destCity,
                    best.getTotalDistanceKm(), best.getTotalTravelTimeSeconds());
        } else {
            log.warn("No schedulable route found: {} → {}", originCity, destCity);
        }

        return best;
    }

    /**
     * Precomputes travel time for every ordered city pair and writes all results
     * to Redis. Called by the scheduled job.
     */
    @Transactional(readOnly = true)
    public Map<String, CityRouteEtaEntry> precomputeAllCityPairs() {
        List<String> cities = warehouseRepository.findAllDistinctCities();
        log.info("Precomputing city-pair ETAs for {} cities ({} pairs)",
                cities.size(), cities.size() * (cities.size() - 1));

        Map<String, CityRouteEtaEntry> results = new LinkedHashMap<>();

        for (String origin : cities) {
            for (String dest : cities) {
                if (origin.equals(dest)) continue;
                try {
                    CityRouteEtaEntry entry = computeAndCache(origin, dest);
                    results.put(origin + "→" + dest, entry);
                } catch (Exception e) {
                    log.error("Failed computing {} → {}: {}", origin, dest, e.getMessage());
                    results.put(origin + "→" + dest, null);
                }
            }
        }
        return results;
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private int indexOfCity(List<RoutePoint> points, String cityName) {
        for (int i = 0; i < points.size(); i++) {
            if (cityName.equalsIgnoreCase(points.get(i).getLocationName())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the effective departure time for a RoutePoint:
     * Redis real-time value → DB expectedEndTime → scheduled endTime.
     */
    private LocalDateTime effectiveDeparture(RoutePoint point) {
        Object raw = etaRedisTemplate.opsForValue().get(
                Objects.requireNonNull(RouteDelayService.etaRedisKey(point.getId())));
        if (raw != null) {
            try { return LocalDateTime.parse(raw.toString()); } catch (Exception ignored) {}
        }
        return point.getExpectedEndTime() != null ? point.getExpectedEndTime() : point.getEndTime();
    }

    /** Returns accumulated delay minutes at a RoutePoint, or 0 if none recorded. */
    private long effectiveDelayMinutes(RoutePoint point) {
        return point.getDelayMinutes() != null ? point.getDelayMinutes() : 0L;
    }

    private double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
