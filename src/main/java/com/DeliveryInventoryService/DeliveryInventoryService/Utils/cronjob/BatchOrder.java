package com.DeliveryInventoryService.DeliveryInventoryService.Utils.cronjob;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;

import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.PathResultDTO;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order.OrderStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.OrderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.KMeansClustering;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.OsrmDistanceMatrix;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.RoutePathService;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@RestController
public class BatchOrder {

    private final OrderRepository orderRepository;
    private final RoutePathService routePathService;
    private final OsrmDistanceMatrix osrmDistanceMatrix;
    private final KMeansClustering kmeans;
    Logger logger = LoggerFactory.getLogger(BatchOrder.class);

    // @Scheduled(cron = "${myapp.cron.batch_order_from_city_and_assign_to_rider}")
    @GetMapping("/cronjob/batch-order")
    public ResponseEntity<?> batchOrderFromCityAndAssignToRider() {

        List<Order> orders = orderRepository.findByStatus(OrderStatus.CREATED);
        logger.info("Starting batch order processing..." + orders.size() + " orders found.");

        if (orders.isEmpty()) {
            logger.info("No new orders to process.");
            return ResponseEntity.ok("No new orders to process.");
        }

        logger.info("new orders to process.");

        Map<String, List<Order>> ordersByCity = orders.stream()
                .collect(Collectors.groupingBy(Order::getOriginCity));

        int processedCities = 0;

        for (Map.Entry<String, List<Order>> entry : ordersByCity.entrySet()) {
            String city = entry.getKey();
            List<Order> orderPerCity = entry.getValue();
            logger.info("Processing city: " + city + " with " + orderPerCity.size() + " orders.");
            if (orderPerCity.isEmpty())
                continue;

            double[][] distanceMatrix = osrmDistanceMatrix.buildDistanceMatrix(orderPerCity);

            if (distanceMatrix.length == 0) {
                logger.warn("Distance matrix empty for city " + city);
                continue;
            }

            try {
                Map<Integer, Map<Integer, List<Integer>>> allRoutes = kmeans.clusterAndSolveVRP(orderPerCity,
                        distanceMatrix, 50, city);

                logger.info("Currently working for " + city);

                for (var clusterEntry : allRoutes.entrySet()) {
                    int clusterId = clusterEntry.getKey();
                    logger.info("Cluster " + clusterId);

                    for (var riderEntry : clusterEntry.getValue().entrySet()) {
                        logger.info(" Rider " + riderEntry.getKey() + " route: " +
                                riderEntry.getValue());
                    }
                    logger.info("=======================================================");
                }

                processedCities++;

            } catch (Exception e) {
                logger.error("Error processing city " + city + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        if (processedCities == 0) {
            return ResponseEntity.ok("No cities processed. Possible matrix or data issue.");
        }

        return ResponseEntity.ok(
                "Batch processing completed for " + processedCities + " cities out of " + ordersByCity.size());
    }

    @GetMapping("/shortest")
    public PathResultDTO getShortestPath(
            @RequestParam String from,
            @RequestParam String to) {

        return routePathService.findShortestPath(from, to);
    }
}