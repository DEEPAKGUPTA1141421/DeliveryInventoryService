package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.OrderRequestDTO;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.jaxb.SpringDataJaxb.OrderDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;

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

            if (request.getServiceType() != null) {
                order.setServiceType(Order.ServiceType.valueOf(request.getServiceType()));
            }
            order = orderRepository.save(order);
            return new ApiResponse<>(true, "Order created successfully", order, 201);
        } catch (IllegalArgumentException e) {
            return new ApiResponse<>(false, "Invalid request: " + e.getMessage(), null, 400);
        } catch (Exception e) {
            return new ApiResponse<>(false, "Failed to create order: " + e.getMessage(), null, 501);
        }
    }

    // private UUID NearestWareHouse(OrderRequestDTO request) {
    // Double destLat = request.getDestLat();
    // Double destLng = request.getDestLng();
    // }
}

// yuyuuhui yuuihui 67tg yy 7iiyyi t76t

// uiio yuhuhyuklmkllkjijioj