package com.DeliveryInventoryService.DeliveryInventoryService.Controller;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.OrderRequestDTO;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.network.RiderIdResponseDto;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Rider;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order.OrderStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.OrderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RiderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.OrderService;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.GoogleDistanceMatrix;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.KMeansClustering;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.OsrmDistanceMatrix;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
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

    private final WarehouseRepository warehouseRepository;

    private final RiderRepository riderRepository;

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

    @GetMapping("/test")
    public ResponseEntity<?> Test() {
        List<OrderStatus> status = new ArrayList<>();
        status.add(OrderStatus.CREATED);
        // List<Order> orders = orderRepository.findByOriginCityAndStatusIn("Delhi",
        // status);
        // List<Warehouse> warehouse = warehouseRepository.findByCity("Delhi");
        List<Rider> riders = riderRepository.findByCity("Delhi");
        return ResponseEntity.ok(riders);
    }

    @GetMapping("/distance-matrix")
    public ResponseEntity<?> getMatrix() throws Exception {
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
        System.out.println(("1"));

        Map<Integer, Map<Integer, List<Integer>>> allRoutes = kmeans.clusterAndSolveVRP(orders, matrix, 50, "Delhi");

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

// hiouyl9yd789y8 66k78 t86868y77y
