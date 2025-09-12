package com.DeliveryInventoryService.DeliveryInventoryService.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.RoutePoint;

import java.util.List;
import java.util.UUID;

@Repository
public interface RoutePointRepository extends JpaRepository<RoutePoint, UUID> {
    List<RoutePoint> findByRouteIdOrderBySequenceAsc(UUID routeId);
}