package com.DeliveryInventoryService.DeliveryInventoryService.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order.OrderStatus;

import jakarta.transaction.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

        List<Order> findByStatus(OrderStatus status);

        Optional<Order> findByOrderNo(String orderNo);

        List<Order> findByOriginCityAndStatusIn(String city,
                        List<OrderStatus> statuses);
}

// njjoiuo9u y78y8iy7uhyu7y8ybjfhuh

// nbhyi8yhiyfnkhuiuiuiufih bgyif hiuy78iyf ut8787gyut87yfcbcdgyutuy