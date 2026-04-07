package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;

import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class OsrmDistanceMatrix {

    private static final String OSRM_URL = "http://router.project-osrm.org/table/v1/driving/";
    private static final String OSRM_URL_DISTANCE = "http://router.project-osrm.org/route/v1/driving/";
    private static final int MAX_COORDS = 50; // safe batching size for OSRM

    private final RestTemplate restTemplate = new RestTemplate();

    public double[][] buildDistanceMatrix(List<Order> orders) {
        int n = orders.size();
        double[][] distanceMatrix = new double[n][n];

        // Process in batches
        for (int i = 0; i < n; i += MAX_COORDS) {
            int iEnd = Math.min(i + MAX_COORDS, n);

            for (int j = 0; j < n; j += MAX_COORDS) {
                int jEnd = Math.min(j + MAX_COORDS, n);

                // Collect batch coords (only unique once!)
                List<String> coordsList = new ArrayList<>();
                for (int x = i; x < iEnd; x++) {
                    Order o = orders.get(x);
                    coordsList.add(o.getOriginLng() + "," + o.getOriginLat());
                }
                for (int y = j; y < jEnd; y++) {
                    Order o = orders.get(y);
                    coordsList.add(o.getOriginLng() + "," + o.getOriginLat());
                }

                String coords = String.join(";", coordsList);
                String url = OSRM_URL + coords + "?annotations=distance";

                Map<String, Object> response = restTemplate.getForObject(url, Map.class);
                if (response == null || !"Ok".equals(response.get("code"))) {
                    throw new RuntimeException("OSRM API error: " + response);
                }

                // OSRM returns distances as List<List<Number>>
                List<List<Number>> distances = (List<List<Number>>) response.get("distances");

                // Fill into main matrix
                for (int x = 0; x < iEnd - i; x++) {
                    for (int y = 0; y < jEnd - j; y++) {
                        distanceMatrix[i + x][j + y] = distances.get(x).get(y).doubleValue();
                    }
                }
            }
        }
        return distanceMatrix;
    }

    public record MatrixResult(
            double[][] distances, // meters
            double[][] durations // seconds
    ) {
    }

    public MatrixResult getDistanceMatrix(
            List<double[]> sources,
            List<double[]> destinations) {

        if (sources == null || sources.isEmpty()
                || destinations == null || destinations.isEmpty()) {
            return null;
        }

        try {

            StringBuilder coords = new StringBuilder();

            List<double[]> allPoints = new ArrayList<>();
            allPoints.addAll(sources);
            allPoints.addAll(destinations);

            for (int i = 0; i < allPoints.size(); i++) {
                double[] p = allPoints.get(i);
                coords.append(p[0]).append(",").append(p[1]);
                if (i < allPoints.size() - 1)
                    coords.append(";");
            }

            StringBuilder srcIdx = new StringBuilder();
            for (int i = 0; i < sources.size(); i++) {
                srcIdx.append(i);
                if (i < sources.size() - 1)
                    srcIdx.append(";");
            }

            StringBuilder destIdx = new StringBuilder();
            for (int i = 0; i < destinations.size(); i++) {
                destIdx.append(i + sources.size());
                if (i < destinations.size() - 1)
                    destIdx.append(";");
            }

            String url = OSRM_URL + coords
                    + "?sources=" + srcIdx
                    + "&destinations=" + destIdx;

            log.info("OSRM Table API call → {}", url);

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.getForObject(url, Map.class);

            if (resp == null) {
                log.warn("OSRM table response is null");
                return null;
            }

            String code = (String) resp.get("code");
            if (code == null || !code.equalsIgnoreCase("Ok")) {
                log.warn("OSRM table bad response: {}", resp);
                return null;
            }

            // ─────────────────────────────────────────────
            // Step 4: Parse durations & distances
            // ─────────────────────────────────────────────
            List<List<Number>> durationsRaw = (List<List<Number>>) resp.get("durations");

            List<List<Number>> distancesRaw = (List<List<Number>>) resp.get("distances");

            if (durationsRaw == null || distancesRaw == null) {
                log.warn("OSRM table missing matrix data");
                return null;
            }

            int rows = durationsRaw.size();
            int cols = durationsRaw.get(0).size();

            double[][] durations = new double[rows][cols];
            double[][] distances = new double[rows][cols];

            for (int i = 0; i < rows; i++) {
                for (int j = 0; j < cols; j++) {
                    durations[i][j] = durationsRaw.get(i).get(j).doubleValue();
                    distances[i][j] = distancesRaw.get(i).get(j).doubleValue();
                }
            }

            return new MatrixResult(distances, durations);

        } catch (Exception e) {
            log.error("OSRM table API failed: {}", e.getMessage());
            return null;
        }
    }
}
