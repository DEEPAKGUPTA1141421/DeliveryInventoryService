package com.DeliveryInventoryService.DeliveryInventoryService.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Route;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Route.Status;

import java.util.List;
import java.util.UUID;

@Repository
public interface RouteRepository extends JpaRepository<Route, UUID> {
    List<Route> findByVehicleId(UUID vehicleId);

    List<Route> findByStatus(Status status);

    @Query("SELECT DISTINCT r FROM Route r JOIN FETCH r.points LEFT JOIN FETCH r.vehicle WHERE r.status = :status")
    List<Route> findByStatusWithPoints(@Param("status") Route.Status status);

}
