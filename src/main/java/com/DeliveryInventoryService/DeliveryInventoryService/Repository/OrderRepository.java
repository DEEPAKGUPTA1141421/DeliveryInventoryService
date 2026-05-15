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

        List<Order> findByOriginCityAndStatusIn(String city, List<OrderStatus> statuses);

        List<Order> findByWareHouseIdAndStatusIn(UUID wareHouseId, List<OrderStatus> statuses);

        @Query("SELECT DISTINCT o.wareHouseId FROM Order o WHERE o.status = :status AND o.wareHouseId IS NOT NULL")
        List<UUID> findDistinctWareHouseIdsByStatus(@Param("status") OrderStatus status);

        @Query("""
                SELECT o FROM Order o
                WHERE o.wareHouseId = :warehouseId
                  AND o.status = :status
                  AND NOT EXISTS (SELECT 1 FROM Parcel p WHERE p.orderId = o.id)
                ORDER BY o.destCity, o.createdAt
                """)
        List<Order> findUnassignedByWarehouse(@Param("warehouseId") UUID warehouseId,
                                              @Param("status") OrderStatus status);

        @Query("SELECT o FROM Order o WHERE o.wareHouseId = :warehouseId ORDER BY o.createdAt DESC")
        List<Order> findByWareHouseId(@Param("warehouseId") UUID warehouseId);

        @Query("SELECT o FROM Order o WHERE o.wareHouseId = :warehouseId AND o.status = :status ORDER BY o.createdAt DESC")
        List<Order> findByWareHouseIdAndStatus(@Param("warehouseId") UUID warehouseId,
                                               @Param("status") OrderStatus status);
}

// njjoiuo9u y78y8iy7uhyu7y8ybjfhuh

// nbhyi8yhiyfnkhuiuiuiufih bgyif hiuy78iyf ut8787gyut87yfcbcdgyutuy