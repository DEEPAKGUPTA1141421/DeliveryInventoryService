package com.DeliveryInventoryService.DeliveryInventoryService.Controller;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.OrderRequestDTO;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.OrderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.OrderService;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.GoogleDistanceMatrix;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.KMeansClustering;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.OsrmDistanceMatrix;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final GoogleDistanceMatrix googleDistanceMatrix;

    private final OsrmDistanceMatrix osrmDistanceMatrix;

    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody @Valid OrderRequestDTO orderRequest) {
        try {
            ApiResponse<Object> response = orderService.createOrder(orderRequest);
            return ResponseEntity.status(response.statusCode()).body(response);
        } catch (Exception e) {
            return ResponseEntity.status(500)
                    .body(new ApiResponse<>(false, "Internal server error: " + e.getMessage(), null, 500));
        }
    }

    @GetMapping
    public ResponseEntity<?> seedData() {
        try {
            orderService.seedOrdersFromFile();
            return ResponseEntity.ok("Order Service is up and running!");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.ok("Order error  Service is up and running!");
        }
    }

    @GetMapping("/distance-matrix")
    public ResponseEntity<?> getMatrix() {
        List<Order> orders = orderRepository.findAll(); // or a subset
        double[][] matrix = osrmDistanceMatrix.buildDistanceMatrix(orders);

        // checked kmean cluster
        // KMeansClustering kmeans = new KMeansClustering(5, 100); // 5 clusters, 100
        // max iterations
        // Map<Integer, List<Order>> clusters = kmeans.clusterOrders(orders);

        // for (Map.Entry<Integer, List<Order>> entry : clusters.entrySet()) {
        // System.out.println("Cluster " + entry.getKey() + ": " +
        // entry.getValue().size() + " orders");
        // }
        KMeansClustering kmeans = new KMeansClustering(5, 100);
        Map<Integer, Map<Integer, List<Integer>>> allRoutes = kmeans.clusterAndSolveVRP(orders, matrix, 50, 0);

        for (var clusterEntry : allRoutes.entrySet()) {
            int clusterId = clusterEntry.getKey();
            System.out.println("Cluster " + clusterId);
            for (var riderEntry : clusterEntry.getValue().entrySet()) {
                System.out.println("  Rider " + riderEntry.getKey() + " route: " + riderEntry.getValue());
            }
            System.out.println("=======================================================");
        }

        return ResponseEntity.ok("Success");
    }
}

// uuiuiuyi8y7 y87y8yyyy87y 67t7yy yunjkhkjil khkuhij uhiuiuojujhihj
// huihiuhgyuyhuyhbgyyhyiuuujkuihnkjijio niukj.oiu uhiu89 huiyiyo9 yuy78