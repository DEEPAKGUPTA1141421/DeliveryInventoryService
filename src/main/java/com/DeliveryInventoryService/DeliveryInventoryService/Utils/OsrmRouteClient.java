package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

import static com.DeliveryInventoryService.DeliveryInventoryService.Utils.constant.Constant.OK;

/**
 * Thin
 * wrapper
 * around
 * the
 * OSRM
 * /route/v1/driving
 * endpoint.
 *
 * Returns
 * road
 * distance
 * (metres)
 * and
 * duration
 * (seconds)
 * between
 * two
 * points.
 */
@Component
@Slf4j
public class OsrmRouteClient {

    private static final String OSRM_BASE = "http://router.project-osrm.org/route/v1/driving/";

    private final RestTemplate restTemplate = new RestTemplate();

    public record RouteResult(double distanceMetres, long durationSeconds) {
    }

    /**
     * @param fromLng origin longitude
     * @param fromLat origin latitude
     * @param toLng   destination longitude
     * @param toLat   destination latitude
     * @return RouteResult with real road distance and time, or null on failure
     */
    public RouteResult getRoute(double fromLng, double fromLat,
            double toLng, double toLat) {
        // OSRM format: lng,lat;lng,lat
        String url = OSRM_BASE
                + fromLng + "," + fromLat + ";"
                + toLng + "," + toLat
                + "?overview=false";

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.getForObject(url, Map.class);

            if (resp == null || !OK.equals(resp.get("code"))) {
                log.warn("OSRM bad response: {}", resp);
                return null;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> routes = (List<Map<String, Object>>) resp.get("routes");
            if (routes == null || routes.isEmpty())
                return null;

            Map<String, Object> route = routes.get(0);
            double distance = ((Number) route.get("distance")).doubleValue();
            long duration = ((Number) route.get("duration")).longValue();

            return new RouteResult(distance, duration);

        } catch (

        Exception e) {
            log.error("OSRM route call failed [{},{} -> {},{}]: {}",
                    fromLat, fromLng, toLat, toLng, e.getMessage());
            return null;
        }
    }

    /**
     * Multi-stop route: returns total distance and duration across all waypoints.
     * Waypoints: list of [lng, lat] pairs.
     */
    public RouteResult getMultiStopRoute(List<double[]> waypoints) {
        if (waypoints == null || waypoints.size() < 2)
            return null;

        StringBuilder coords = new StringBuilder();
        for (int i = 0; i < waypoints.size(); i++) {
            double[] wp = waypoints.get(i);
            coords.append(wp[0]).append(",").append(wp[1]); // lng,lat
            if (i < waypoints.size() - 1)
                coords.append(";");
        }

        String url = OSRM_BASE + coords + "?overview=false";

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.getForObject(url, Map.class);

            if (resp == null || !"Ok".equals(resp.get("code"))) {
                log.warn("OSRM multi-stop bad response: {}", resp);
                return null;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> routes = (List<Map<String, Object>>) resp.get("routes");
            if (routes == null || routes.isEmpty())
                return null;

            Map<String, Object> route = routes.get(0);
            double distance = ((Number) route.get("distance")).doubleValue();
            long duration = ((Number) route.get("duration")).longValue();

            return new RouteResult(distance, duration);

        } catch (Exception e) {
            log.error("OSRM multi-stop call failed: {}", e.getMessage());
            return null;
        }
    }
}