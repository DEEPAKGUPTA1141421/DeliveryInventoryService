package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Warehouse;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ServiceablePincodeRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.GeoUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ServiceAvailabilityService {

    private final ServiceablePincodeRepository pincodeRepository;
    private final WarehouseRepository warehouseRepository;

    // Example: each warehouse covers 15 km radius
    private static final double COVERAGE_RADIUS_KM = 15.0;

    public boolean isServiceAvailable(String pincode, double lat, double lng) {
        // Step 1: Fast check by pincode
        boolean exists = pincodeRepository.existsByPincode(pincode);
        if (!exists)
            return false;

        // Step 2: Precise check - warehouse radius
        return warehouseRepository.findAll().stream()
                .anyMatch(wh -> GeoUtils.isWithinRadius(wh, lat, lng, COVERAGE_RADIUS_KM));
    }
}

/// khiuyiui8hyy87y gututukyk
