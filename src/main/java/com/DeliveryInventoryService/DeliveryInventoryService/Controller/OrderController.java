package com.DeliveryInventoryService.DeliveryInventoryService.Controller;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.OrderRequestDTO;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Rider;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order.OrderStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.OrderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RiderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.GeohashCacheService;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.OrderService;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.GeoUtils;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.GeohashService;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.GoogleDistanceMatrix;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.cronjob.InterCityEtaRefreshJob;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final GoogleDistanceMatrix googleDistanceMatrix;
    private final WarehouseRepository warehouseRepository;
    private final RiderRepository riderRepository;
    private final InterCityEtaRefreshJob interCityEtaRefreshJob;
    private final GeohashService geohashService;
    private final GeohashCacheService geohashCacheService;

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

    @GetMapping("/test2")
    public ResponseEntity<?> Test2() {
        interCityEtaRefreshJob.refreshAllCityPairs();
        return ResponseEntity.ok("Test 2");
    }

    /**
     * Mark an order DELIVERED and feed the actual last-mile distance back into
     * the Seg3 running-average cache so future ETA estimates improve over time.
     *
     * POST-conditions:
     *   • order.status = DELIVERED
     *   • delivery:seg3:{userCell6} running average updated in Redis
     */
    @PatchMapping("/{orderId}/delivered")
    public ResponseEntity<?> markDelivered(@PathVariable UUID orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return ResponseEntity.status(404)
                    .body(new ApiResponse<>(false, "Order not found", null, 404));
        }
        if (order.getStatus() == OrderStatus.DELIVERED) {
            return ResponseEntity.ok(
                    new ApiResponse<>(true, "Already delivered", null, 200));
        }

        // ── Find nearest active warehouse to the delivery address ─────────────
        List<Warehouse> activeWarehouses = warehouseRepository.findAll()
                .stream()
                .filter(w -> w.getStatus() == Warehouse.WarehouseStatus.ACTIVE)
                .toList();

        if (!activeWarehouses.isEmpty()) {
            double destLat = order.getDestLat();
            double destLng = order.getDestLng();

            Warehouse nearestWh = activeWarehouses.stream()
                    .min(Comparator.comparingDouble(
                            w -> GeoUtils.distanceKm(w.getLat(), w.getLng(), destLat, destLng)))
                    .get();

            double actualKm = GeoUtils.distanceKm(
                    nearestWh.getLat(), nearestWh.getLng(), destLat, destLng);

            String cellHash = geohashService.cellHash(destLat, destLng);
            geohashCacheService.updateSeg3(cellHash, actualKm);

            log.debug("Seg3 updated: orderId={} cell={} actualKm={}", orderId, cellHash, actualKm);
        }

        order.setStatus(OrderStatus.DELIVERED);
        orderRepository.save(order);

        return ResponseEntity.ok(
                new ApiResponse<>(true, "Order marked as delivered", null, 200));
    }

}

// uuiuiuyi8y7 y87y8yyyy87y 67t7yy yunjkhkjil khkuhij uhiuiuojujhihj

// hiouyl9yd789y8 66k78 t86868y77y
