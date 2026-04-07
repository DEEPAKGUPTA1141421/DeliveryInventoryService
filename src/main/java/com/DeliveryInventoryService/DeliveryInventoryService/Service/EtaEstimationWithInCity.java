package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.ServiceablePincode;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ServiceablePincodeRepository;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
public class EtaEstimationWithInCity {
    private final ServiceablePincodeRepository serviceablePincodeRepository;

    @Scheduled(fixedRate = 60000 * 5) // Run every 5 minutes
    void estimateEta() {
        List<ServiceablePincode> serviceablePincodes = serviceablePincodeRepository.findAll();
    }
}
