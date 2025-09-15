package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class OsrmDistanceMatrix {

    private static final String OSRM_URL = "http://router.project-osrm.org/table/v1/driving/";
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
}
// jiojiojo uihiu huihuihuih hhi huihuihiuhuihuihui hhuih

// jjuiui ihuihi huihiuhuhihuihuihu
// huiyhuiyuhu8huiyi8uou8huiyuhu8 njkjiouou uououoiu9huyy hyy7ytg
// njkjjjjijjijkjijijji