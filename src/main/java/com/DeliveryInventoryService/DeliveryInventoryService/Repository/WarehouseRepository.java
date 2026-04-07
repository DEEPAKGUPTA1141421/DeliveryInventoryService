package com.DeliveryInventoryService.DeliveryInventoryService.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse.WarehouseStatus;

import java.util.UUID;
import java.util.List;

@Repository

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {
        List<Warehouse> findByCity(String city);

        @Query("SELECT DISTINCT w.city FROM Warehouse w WHERE w.city IS NOT NULL AND w.status = 'ACTIVE'")
        List<String> findAllDistinctCities();
}

// kjhiu hyiyhui uu8ouhuiyhiuhuhu bhh