package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.Edge;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.OrderRequestDTO;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Route;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.RoutePoint;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.OrderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RouteRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.GeoUtils;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.jaxb.SpringDataJaxb.OrderDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.Collections;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final WarehouseRepository warehouseRepository;
    private final RouteRepository routeRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public void seedOrdersFromFile() throws IOException, InterruptedException {
        // read file from resources
        InputStream inputStream = getClass().getClassLoader()
                .getResourceAsStream("seed/orders_seed.json");

        if (inputStream == null) {
            throw new FileNotFoundException("orders_seed.json not found in resources/seed/");
        }

        List<OrderRequestDTO> orders = Arrays.asList(objectMapper.readValue(inputStream, OrderRequestDTO[].class));

        for (OrderRequestDTO dto : orders) {
            ApiResponse<Object> response = createOrder(dto);
            if (response.success() == false) {
                System.out.println("Failed to create order from seed data: " + response.message());
            } else {
                System.out.println("Created order from seed data: " + ((Order) response.data()).getId());
            }
            Thread.sleep(2000);
        }
    }

    public ApiResponse<Object> createOrder(OrderRequestDTO request) {
        try {
            Order order = new Order();
            order.setCustomerId(request.getCustomerId());
            order.setBookingId(request.getBookingId());
            order.setOriginAddress(request.getOriginAddress());
            order.setOriginLat(request.getOriginLat());
            order.setOriginLng(request.getOriginLng());
            order.setDestAddress(request.getDestAddress());
            order.setDestLat(request.getDestLat());
            order.setDestLng(request.getDestLng());
            order.setWeightKg(request.getWeightKg());
            order.setWareHouseId(NearestWareHouse(request));

            if (request.getServiceType() != null) {
                order.setServiceType(Order.ServiceType.valueOf(request.getServiceType()));
            }
            order = createOrder(order);
            return new ApiResponse<>(true, "Order created successfully", order, 201);
        } catch (IllegalArgumentException e) {
            return new ApiResponse<>(false, "Invalid request: " + e.getMessage(), null, 400);
        } catch (Exception e) {
            return new ApiResponse<>(false, "Failed to create order: " + e.getMessage(), null, 501);
        }
    }

    public Order createOrder(Order order) {
        // Retry loop for unique orderNo
        String orderNo;
        int attempts = 0;
        do {
            orderNo = Order.generateOrderNo();
            attempts++;
        } while (orderRepository.findByOrderNo(orderNo).isPresent() && attempts < 5);

        if (attempts == 5) {
            throw new RuntimeException("Failed to generate unique Order Number after retries");
        }

        order.setOrderNo(orderNo);
        return orderRepository.save(order);
    }

    private UUID NearestWareHouse(OrderRequestDTO request) {
        Double destLat = request.getDestLat();
        Double destLng = request.getDestLng();

        return warehouseRepository.findAll().stream()
                .min(Comparator.comparingDouble(
                        wh -> GeoUtils.distanceKm(destLat, destLng, wh.getLat(), wh.getLng())))
                .map(Warehouse::getId)
                .orElse(null); // return null if no warehouses found
    }

    private void createRoute(Order order) {
        List<Route> routes = routeRepository.findByStatus(Route.Status.ACTIVE);
        Map<String, List<Edge>> graph = new HashMap<>();
        for (Route r : routes) {
            List<RoutePoint> points = r.getPoints()
                    .stream()
                    .sorted(Comparator.comparingInt(RoutePoint::getSequence))
                    .toList();

            for (int i = 0; i < points.size() - 1; i++) {
                RoutePoint from = points.get(i);
                RoutePoint to = points.get(i + 1);

                long travelTime = r.getEndTime()
                        .atZone(ZoneId.of("Asia/Kolkata"))
                        .toEpochSecond()
                        - r.getStartTime()
                                .atZone(ZoneId.of("Asia/Kolkata"))
                                .toEpochSecond();

                graph.computeIfAbsent(from.getCity(), k -> new ArrayList<>())
                        .add(new Edge(to.getCity(), travelTime, r.getId()));
            }
        }
    }

    public List<String> findShortestPath(String startCity, String endCity, Map<String, List<Edge>> graph) {
        Map<String, Long> minTime = new HashMap<>();
        Map<String, String> previous = new HashMap<>();
        PriorityQueue<String> pq = new PriorityQueue<>(Comparator.comparingLong(minTime::get));

        for (String city : graph.keySet()) {
            minTime.put(city, Long.MAX_VALUE);
        }
        minTime.put(startCity, 0L);
        pq.add(startCity);

        while (!pq.isEmpty()) {
            String current = pq.poll();
            if (current.equals(endCity))
                break;

            for (Edge edge : graph.getOrDefault(current, List.of())) {
                long newTime = minTime.get(current) + edge.getTimeSeconds();
                if (newTime < minTime.getOrDefault(edge.getToCity(), Long.MAX_VALUE)) {
                    minTime.put(edge.getToCity(), newTime);
                    previous.put(edge.getToCity(), current);
                    pq.add(edge.getToCity());
                }
            }
        }

        // reconstruct path
        List<String> path = new ArrayList<>();
        for (String at = endCity; at != null; at = previous.get(at)) {
            path.add(at);
        }
        Collections.reverse(path);
        return path;
    }
}

// yuyuuhui yuuihui 67tg yy 7iiyyi t76t

// uiio yuhuhyuklmkllkjijioj

// uy789 t686yrfby686 7797978y7 iyuyu7y