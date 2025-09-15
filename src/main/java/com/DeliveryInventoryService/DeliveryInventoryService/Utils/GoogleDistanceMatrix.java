package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.network.GoogleClient;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class GoogleDistanceMatrix {

    private final GoogleClient googleClient;

    @Value("${google.api.key}")
    private String apiKey;

    public double[][] buildDistanceMatrix(List<Order> orders) {
        int n = orders.size();
        double[][] distanceMatrix = new double[n][n];

        StringBuilder coords = new StringBuilder();
        for (int i = 0; i < n; i++) {
            Order o = orders.get(i);
            coords.append(o.getOriginLat()).append(",").append(o.getOriginLng());
            if (i < n - 1)
                coords.append("|");
        }

        Map<String, Object> response = googleClient.getDistanceMatrix(
                coords.toString(),
                coords.toString(),
                apiKey);
        System.out.println("Google API Response: " + response);

        // Parse response
        List<Map<String, Object>> rows = (List<Map<String, Object>>) response.get("rows");
        System.out.println("rows size  " + rows.size());
        for (int i = 0; i < rows.size(); i++) {
            List<Map<String, Object>> elements = (List<Map<String, Object>>) rows.get(i).get("elements");
            for (int j = 0; j < elements.size(); j++) {
                Map<String, Object> element = elements.get(j);
                if ("OK".equals(element.get("status"))) {
                    System.out.print(element.get("status"));
                    Map<String, Object> distance = (Map<String, Object>) element.get("distance");
                    double meters = ((Number) distance.get("value")).doubleValue();
                    System.out.print("distance" + meters);
                    distanceMatrix[i][j] = meters; // km
                } else {
                    distanceMatrix[i][j] = Double.POSITIVE_INFINITY; // no route
                }
            }
        }

        return distanceMatrix;
    }
}
// hukihuif khuihiuf kuhkiufr kuhiuhfrhuiyho njkhuihui hjgyiy huyiuyuiu8 hyuy

// uiu8iu89 huii89ujujiu hyiyuiyu7juinkhnjkuhjlijo iuhuiu hhiuyiuiui
// uyhyiyhuiuolioiouolulououulouo9