package com.DeliveryInventoryService.DeliveryInventoryService.Service.admin;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.WarehouseRequest;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;

    public Warehouse createWarehouse(WarehouseRequest request) {
        Warehouse warehouse = new Warehouse();
        warehouse.setName(request.getName());
        warehouse.setCity(request.getCity());
        warehouse.setState(request.getState());
        warehouse.setAddress(request.getAddress());
        warehouse.setLat(request.getLat());
        warehouse.setLng(request.getLng());
        warehouse.setCapacityMaxParcels(request.getCapacityMaxParcels());
        warehouse.setCapacityMaxKg(request.getCapacityMaxKg());

        if (request.getType() != null) {
            warehouse.setType(request.getType());
        }

        return warehouseRepository.save(warehouse);
    }

    public List<Warehouse> getAllWarehouses() {
        return warehouseRepository.findAll();
    }

    public Warehouse getWarehouseById(UUID id) {
        return warehouseRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Warehouse not found with ID: " + id));
    }
}
