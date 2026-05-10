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

        /**
         * Returns active warehouses within a bounding box, ordered by lat/lng proximity.
         * This avoids a full table scan when finding the nearest warehouse to a delivery point.
         */
        @Query("""
                SELECT w FROM Warehouse w
                WHERE w.status = 'ACTIVE'
                  AND w.lat BETWEEN :minLat AND :maxLat
                  AND w.lng BETWEEN :minLng AND :maxLng
                """)
        List<Warehouse> findActiveInBoundingBox(@Param("minLat") double minLat,
                                                @Param("maxLat") double maxLat,
                                                @Param("minLng") double minLng,
                                                @Param("maxLng") double maxLng);

        List<Warehouse> findByStatus(Warehouse.WarehouseStatus status);
}

// kjhiu hyiyhui uu8ouhuiyhiuhuhu bhh