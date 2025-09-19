package com.DeliveryInventoryService.DeliveryInventoryService.Utils.cronjob;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order.OrderStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.OrderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.KMeansClustering;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.OsrmDistanceMatrix;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BatchOrder {

    private final OrderRepository orderRepository;
    private final OsrmDistanceMatrix osrmDistanceMatrix;
    private final KMeansClustering kmeans = new KMeansClustering(5, 100); // ✅ Inject instead of `new`

    @Scheduled(cron = "${myapp.cron.batch_order_from_city_and_assign_to_rider}")

    public void batchOrderFromCityAndAssignToRider() {
        List<Order> orders = orderRepository.findByStatus(OrderStatus.CREATED);

        if (orders.isEmpty()) {
            System.out.println("No new orders to process.");
            return;
        }

        Map<String, List<Order>> ordersByCity = orders.stream()
                .collect(Collectors.groupingBy(Order::getOriginCity));

        for (Map.Entry<String, List<Order>> entry : ordersByCity.entrySet()) {
            String city = entry.getKey();
            List<Order> orderPerCity = entry.getValue();

            if (orderPerCity.isEmpty())
                continue;

            double[][] distanceMatrix = osrmDistanceMatrix.buildDistanceMatrix(orderPerCity);

            try {
                Map<Integer, Map<Integer, List<Integer>>> allRoutes = kmeans.clusterAndSolveVRP(orderPerCity,
                        distanceMatrix, 50, city);

                System.out.println("Currently working for " + city);

                for (var clusterEntry : allRoutes.entrySet()) {
                    int clusterId = clusterEntry.getKey();
                    System.out.println("Cluster " + clusterId);

                    for (var riderEntry : clusterEntry.getValue().entrySet()) {
                        System.out.println(" Rider " + riderEntry.getKey() + " route: " +
                                riderEntry.getValue());
                    }

                    System.out.println("=======================================================");
                }
            } catch (Exception e) {
                System.err.println("Error processing city " + city + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}
