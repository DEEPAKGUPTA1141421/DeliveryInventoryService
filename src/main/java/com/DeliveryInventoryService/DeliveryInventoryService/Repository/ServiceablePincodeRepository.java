package com.DeliveryInventoryService.DeliveryInventoryService.Repository;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.ServiceablePincode;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceablePincodeRepository extends JpaRepository<ServiceablePincode, UUID> {
    boolean existsByPincode(String pincode);
}

// hiyyhi7 hiuyy7u hiyhiyiy huhu
