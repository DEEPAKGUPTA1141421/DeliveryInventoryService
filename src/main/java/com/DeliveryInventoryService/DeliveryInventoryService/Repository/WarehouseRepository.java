package com.DeliveryInventoryService.DeliveryInventoryService.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse;

import java.util.UUID;
import java.util.List;

@Repository

public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {
    List<Warehouse> findByCity(String city);
}
