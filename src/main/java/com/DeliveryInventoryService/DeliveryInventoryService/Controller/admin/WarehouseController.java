package com.DeliveryInventoryService.DeliveryInventoryService.Controller.admin;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.WarehouseRequest;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.admin.WarehouseService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/warehouses")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService warehouseService;

    // Create new warehouse
    @PostMapping
    public ResponseEntity<Warehouse> createWarehouse(@RequestBody WarehouseRequest request) {
        Warehouse warehouse = warehouseService.createWarehouse(request);
        return ResponseEntity.ok(warehouse);
    }

    // Get all warehouses
    @GetMapping
    public ResponseEntity<List<Warehouse>> getAllWarehouses() {
        return ResponseEntity.ok(warehouseService.getAllWarehouses());
    }

    // Get warehouse by ID
    @GetMapping("/{id}")
    public ResponseEntity<Warehouse> getWarehouseById(@PathVariable UUID id) {
        Warehouse warehouse = warehouseService.getWarehouseById(id);
        return ResponseEntity.ok(warehouse);
    }
}

// jhuihkuuihkiuhuihhuihiuhhiyhuiy