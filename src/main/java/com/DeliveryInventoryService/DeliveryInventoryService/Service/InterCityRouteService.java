package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.CityRouteEtaEntry;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Route;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.RoutePoint;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Vehicle;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RouteRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.EtaRedisKeys;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.OsrmRouteClient;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.OsrmRouteClient.RouteResult;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.constant.Constant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes road travel time between every city pair and stores the result
 * in Redis under key pattern: eta:city:{origin}:{destination}
 *
 * Travel time formula:
 * travelTimeSeconds = roadDistanceMetres / vehicleSpeedMs
 *
 * Vehicle speed hierarchy (fastest → slowest):
 * TRAIN > BUS > FOUR_WHEELER > PICKUP > AUTO > MOTORCYCLE
 *
 * For each city pair the service:
 * 1. Finds all Route objects whose RoutePoint sequence covers both cities
 * 2. For each matching route, computes total road distance via OSRM
 * 3. Applies the speed of the vehicle assigned to that route
 * 4. Stores the minimum-time option in Redis with the full stop list
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterCityRouteService {

    private final RouteRepository routeRepository;
    private final WarehouseRepository warehouseRepository;
    private final OsrmRouteClient osrmRouteClient;
    private final RedisTemplate<String, Object> etaRedisTemplate;

    // ── Vehicle speed constants (metres per second) ─────────────────────────
    // These are conservative averages; tune per business SLA
    private static final Map<Vehicle.VehicleType, Double> SPEED_MS;
    static {
        SPEED_MS = new EnumMap<>(Vehicle.VehicleType.class);
        SPEED_MS.put(Vehicle.VehicleType.TRAIN, 27.8); // ~100 km/h
        SPEED_MS.put(Vehicle.VehicleType.BUS, 19.4); // ~70 km/h
        SPEED_MS.put(Vehicle.VehicleType.FOUR_WHEELER, 16.7); // ~60 km/h
        SPEED_MS.put(Vehicle.VehicleType.PICKUP, 13.9); // ~50 km/h
        SPEED_MS.put(Vehicle.VehicleType.AUTO, 8.3); // ~30 km/h
        SPEED_MS.put(Vehicle.VehicleType.MOTORCYCLE, 11.1); // ~40 km/h
    }

    // Transfer penalty added when a parcel must change vehicles at an
    // intermediate hub (loading/unloading + wait time)
    public static final long TRANSFER_PENALTY_SECONDS = 3600L; // 1 hour

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the cached ETA entry for a direct city pair, or null if not cached.
     * Callers should fall back to {@link #computeAndCache} on cache miss.
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
     * Computes the fastest direct route from originCity to destCity across all
     * active routes, stores the result in Redis, and returns the entry.
     * Returns null if no route covers both cities.
     */
    public CityRouteEtaEntry computeAndCache(String originCity, String destCity) {
        log.info("Computing city ETA: {} → {}", originCity, destCity);

        List<Route> activeRoutes = routeRepository.findByStatus(Route.Status.ACTIVE);
        CityRouteEtaEntry best = null;

        for (Route route : activeRoutes) {
            // Sort route points by sequence
            List<RoutePoint> points = route.getPoints()
                    .stream()
                    .sorted(Comparator.comparingInt(RoutePoint::getSequence))
                    .collect(Collectors.toList());

            // Find origin and destination indices within this route
            int originIdx = indexOfCity(points, originCity);
            int destIdx = indexOfCity(points, destCity);

            // Both cities must exist and origin must come before destination
            if (originIdx < 0 || destIdx < 0 || originIdx >= destIdx) {
                continue;
            }

            // Sub-path: from origin stop to destination stop
            List<RoutePoint> subPath = points.subList(originIdx, destIdx + 1);

            // Compute road distance along the sub-path via OSRM
            double totalRoadMetres = computeRoadDistance(subPath);
            if (totalRoadMetres <= 0) {
                log.warn("OSRM returned 0 distance for route {} ({} → {})", route.getId(), originCity, destCity);
                continue;
            }

            // Determine vehicle speed for this route
            Vehicle.VehicleType vType = route.getVehicle() != null && route.getVehicle().getVehicleType() != null
                    ? route.getVehicle().getVehicleType()
                    : Vehicle.VehicleType.FOUR_WHEELER;
            double speedMs = SPEED_MS.getOrDefault(vType, SPEED_MS.get(Vehicle.VehicleType.FOUR_WHEELER));

            long travelSeconds = Math.round(totalRoadMetres / speedMs);
            double totalKm = totalRoadMetres / 1000.0;

            List<String> stopNames = subPath.stream()
                    .map(RoutePoint::getLocationName)
                    .collect(Collectors.toList());

            CityRouteEtaEntry candidate = new CityRouteEtaEntry(
                    originCity,
                    destCity,
                    stopNames,
                    totalKm,
                    travelSeconds,
                    Instant.now().getEpochSecond());

            if (best == null || travelSeconds < best.getTotalTravelTimeSeconds()) {
                best = candidate;
            }
        }

        if (best != null) {
            String key = EtaRedisKeys.cityRouteKey(originCity, destCity);
            // etaRedisTemplate.opsForValue().set(key, best, Constant.TTL);
            log.info("Cached {} → {} | {} km | {}s", originCity, destCity,
                    String.format("%.1f", best.getTotalDistanceKm()),
                    best.getTotalTravelTimeSeconds());
        } else {
            log.warn("No direct route found: {} → {}", originCity, destCity);
        }

        return best;
    }

    /**
     * Precomputes travel time for EVERY ordered city pair and writes all results
     * to Redis. Called by the scheduled job.
     *
     * @return map of "originCity→destCity" → computed entry (or null on failure)
     */
    public Map<String, CityRouteEtaEntry> precomputeAllCityPairs() {
        List<String> cities = warehouseRepository.findAllDistinctCities();
        log.info("Precomputing city-pair ETAs for {} cities ({} pairs)",
                cities.size(), cities.size() * (cities.size() - 1));

        Map<String, CityRouteEtaEntry> results = new LinkedHashMap<>();

        for (String origin : cities) {
            for (String dest : cities) {
                if (origin.equals(dest))
                    continue;
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

    /**
     * Returns the speed (m/s) for a given vehicle type.
     * Exposed so the routing engine can compute edge weights.
     */
    public static double getSpeedMs(Vehicle.VehicleType type) {
        return SPEED_MS.getOrDefault(type, SPEED_MS.get(Vehicle.VehicleType.FOUR_WHEELER));
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Find the first RoutePoint in the list whose locationName equals cityName
     * (case-insensitive). Returns -1 if not found.
     */
    private int indexOfCity(List<RoutePoint> points, String cityName) {
        for (int i = 0; i < points.size(); i++) {
            if (cityName.equalsIgnoreCase(points.get(i).getLocationName())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Calls OSRM to compute the total road distance (metres) along a sequence
     * of route points. Uses multi-stop routing for accuracy.
     */
    private double computeRoadDistance(List<RoutePoint> points) {
        if (points.size() < 2)
            return 0;

        List<double[]> waypoints = points.stream()
                .map(p -> new double[] { p.getLongitude(), p.getLatitude() })
                .collect(Collectors.toList());

        RouteResult result = osrmRouteClient.getMultiStopRoute(waypoints);
        return result != null ? result.distanceMetres() : 0;
    }
}
// ikoojoiooookokookjik jimkkkol kolokolklop