package com.DeliveryInventoryService.DeliveryInventoryService.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.RoutePoint;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.RoutePoint.PointStatus;

import java.util.List;
import java.util.UUID;

@Repository
public interface RoutePointRepository extends JpaRepository<RoutePoint, UUID> {
    @Query("""
                SELECT rp
                FROM RoutePoint rp
                WHERE rp.route.id IN (
                    SELECT rp2.route.id
                    FROM RoutePoint rp2
                    WHERE rp2.locationName = :originCity
                       OR rp2.locationName = :destinationCity
                )
                ORDER BY rp.route.id, rp.sequence ASC
            """)

    List<RoutePoint> findValidRoutePoints(
            @Param("originCity") String originCity,
            @Param("destinationCity") String destinationCity);

    @Query("""
                SELECT rp
                FROM RoutePoint rp
                WHERE rp.locationName = :city
            """)
    List<RoutePoint> findByCity(@Param("city") String city);

    @Query("SELECT rp FROM RoutePoint rp WHERE rp.route.id = :routeId ORDER BY rp.sequence ASC")
    List<RoutePoint> findPointsByRouteIdOrdered(@Param("routeId") java.util.UUID routeId);

}
// uhiuhji uhh uhh huuh huj
// juj huhuhu kjju