package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class OsrmDistanceMatrix {

    private static final String OSRM_TABLE_URL  = "http://router.project-osrm.org/table/v1/driving/";
    private static final String OSRM_ROUTE_URL  = "http://router.project-osrm.org/route/v1/driving/";
    private static final int    BATCH_SIZE       = 25;  // safe size for public OSRM (25×25 = 625 elements)
    private static final String CACHE_PREFIX     = "dist:matrix:";
    private static final long   CACHE_TTL_HOURS  = 6;

    private final StringRedisTemplate redisTemplate;
    private final RestTemplate restTemplate = new RestTemplate();

    // ---------------------------------------------------------------
    // PRIMARY METHOD — Redis-cached N×N distance matrix
    // ---------------------------------------------------------------

    public double[][] buildDistanceMatrix(List<Order> orders) {
        int n = orders.size();
        if (n == 0) return new double[0][0];

        String cacheKey = CACHE_PREFIX + matrixCacheKey(orders);

        // Check cache
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            log.debug("Distance matrix cache HIT for {} orders (key={})", n, cacheKey);
            return deserializeMatrix(cached, n);
        }

        log.info("Distance matrix cache MISS — calling OSRM for {} orders", n);
        double[][] matrix = fetchFromOsrm(orders, n);

        // Store in Redis
        redisTemplate.opsForValue().set(cacheKey, serializeMatrix(matrix),
                CACHE_TTL_HOURS, TimeUnit.HOURS);

        return matrix;
    }

    // ---------------------------------------------------------------
    // OSRM FETCH WITH BATCHING
    // ---------------------------------------------------------------

    private double[][] fetchFromOsrm(List<Order> orders, int n) {
        double[][] distanceMatrix = new double[n][n];

        for (int i = 0; i < n; i += BATCH_SIZE) {
            int iEnd = Math.min(i + BATCH_SIZE, n);
            for (int j = 0; j < n; j += BATCH_SIZE) {
                int jEnd = Math.min(j + BATCH_SIZE, n);
                fetchBatch(orders, distanceMatrix, i, iEnd, j, jEnd);
            }
        }
        return distanceMatrix;
    }

    @SuppressWarnings("unchecked")
    private void fetchBatch(List<Order> orders, double[][] matrix,
                             int iStart, int iEnd, int jStart, int jEnd) {
        List<String> coordsList = new ArrayList<>();
        for (int x = iStart; x < iEnd; x++) {
            Order o = orders.get(x);
            coordsList.add(o.getOriginLng() + "," + o.getOriginLat());
        }
        // Add destination coords only if they don't overlap the source range
        if (jStart != iStart) {
            for (int y = jStart; y < jEnd; y++) {
                Order o = orders.get(y);
                coordsList.add(o.getOriginLng() + "," + o.getOriginLat());
            }
        }

        String coords = String.join(";", coordsList);
        String url = OSRM_TABLE_URL + coords + "?annotations=distance";

        try {
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response == null || !"Ok".equals(response.get("code"))) {
                log.warn("OSRM returned non-OK for batch [{}-{}][{}-{}]: {}", iStart, iEnd, jStart, jEnd, response);
                fillHaversineFallback(orders, matrix, iStart, iEnd, jStart, jEnd);
                return;
            }

            List<List<Number>> distances = (List<List<Number>>) response.get("distances");
            for (int x = 0; x < iEnd - iStart; x++) {
                for (int y = 0; y < jEnd - jStart; y++) {
                    matrix[iStart + x][jStart + y] = distances.get(x).get(y).doubleValue();
                }
            }
        } catch (Exception e) {
            log.error("OSRM call failed for batch: {} — using Haversine fallback", e.getMessage());
            fillHaversineFallback(orders, matrix, iStart, iEnd, jStart, jEnd);
        }
    }

    /** Haversine-based fallback when OSRM is unavailable. */
    private void fillHaversineFallback(List<Order> orders, double[][] matrix,
                                        int iStart, int iEnd, int jStart, int jEnd) {
        for (int x = iStart; x < iEnd; x++) {
            for (int y = jStart; y < jEnd; y++) {
                double km = GeoUtils.distanceKm(
                        orders.get(x).getOriginLat(), orders.get(x).getOriginLng(),
                        orders.get(y).getOriginLat(), orders.get(y).getOriginLng());
                matrix[x][y] = km * 1000; // convert to meters to match OSRM output
            }
        }
    }

    // ---------------------------------------------------------------
    // SECONDARY METHOD — for ETA and route services
    // ---------------------------------------------------------------

    public record MatrixResult(double[][] distances, double[][] durations) {}

    @SuppressWarnings("unchecked")
    public MatrixResult getDistanceMatrix(List<double[]> sources, List<double[]> destinations) {
        if (sources == null || sources.isEmpty() || destinations == null || destinations.isEmpty()) {
            return null;
        }
        try {
            List<double[]> allPoints = new ArrayList<>(sources);
            allPoints.addAll(destinations);

            StringBuilder coords = new StringBuilder();
            for (int i = 0; i < allPoints.size(); i++) {
                double[] p = allPoints.get(i);
                if (i > 0) coords.append(';');
                coords.append(p[0]).append(',').append(p[1]);
            }

            StringBuilder srcIdx = new StringBuilder();
            for (int i = 0; i < sources.size(); i++) {
                if (i > 0) srcIdx.append(';');
                srcIdx.append(i);
            }
            StringBuilder destIdx = new StringBuilder();
            for (int i = 0; i < destinations.size(); i++) {
                if (i > 0) destIdx.append(';');
                destIdx.append(i + sources.size());
            }

            String url = OSRM_TABLE_URL + coords
                    + "?annotations=distance,duration"
                    + "&sources=" + srcIdx
                    + "&destinations=" + destIdx;

            log.info("OSRM Table API → {}", url);
            Map<String, Object> resp = restTemplate.getForObject(url, Map.class);

            if (resp == null || !"Ok".equalsIgnoreCase((String) resp.get("code"))) {
                log.warn("OSRM table bad response: {}", resp);
                return null;
            }

            List<List<Number>> durRaw  = (List<List<Number>>) resp.get("durations");
            List<List<Number>> distRaw = (List<List<Number>>) resp.get("distances");

            if (durRaw == null || distRaw == null) return null;

            int rows = durRaw.size(), cols = durRaw.get(0).size();
            double[][] durations  = new double[rows][cols];
            double[][] distances  = new double[rows][cols];
            for (int i = 0; i < rows; i++) {
                for (int j = 0; j < cols; j++) {
                    durations[i][j]  = durRaw.get(i).get(j).doubleValue();
                    distances[i][j]  = distRaw.get(i).get(j).doubleValue();
                }
            }
            return new MatrixResult(distances, durations);
        } catch (Exception e) {
            log.error("OSRM table API failed: {}", e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------------
    // CACHE KEY + SERIALISATION
    // ---------------------------------------------------------------

    /**
     * Cache key = SHA-1 of sorted "lat,lng" strings.
     * Sorted so that order of the input list doesn't change the key.
     */
    private String matrixCacheKey(List<Order> orders) {
        String sorted = orders.stream()
                .map(o -> String.format("%.6f,%.6f", o.getOriginLat(), o.getOriginLng()))
                .sorted()
                .reduce("", (a, b) -> a + "|" + b);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            byte[] hash = sha.digest(sorted.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(sorted.hashCode());
        }
    }

    private String serializeMatrix(double[][] matrix) {
        StringBuilder sb = new StringBuilder();
        sb.append(matrix.length).append(':');
        for (double[] row : matrix) {
            for (int j = 0; j < row.length; j++) {
                if (j > 0) sb.append(',');
                sb.append((long) row[j]); // store as integer meters — no need for decimals
            }
            sb.append(';');
        }
        return sb.toString();
    }

    private double[][] deserializeMatrix(String data, int n) {
        try {
            int colonIdx = data.indexOf(':');
            String[] rows = data.substring(colonIdx + 1).split(";");
            double[][] matrix = new double[n][n];
            for (int i = 0; i < Math.min(n, rows.length); i++) {
                String[] cols = rows[i].split(",");
                for (int j = 0; j < Math.min(n, cols.length); j++) {
                    matrix[i][j] = Double.parseDouble(cols[j]);
                }
            }
            return matrix;
        } catch (Exception e) {
            log.warn("Distance matrix cache deserialization failed — will refetch: {}", e.getMessage());
            return new double[0][0]; // trigger re-fetch
        }
    }
}
