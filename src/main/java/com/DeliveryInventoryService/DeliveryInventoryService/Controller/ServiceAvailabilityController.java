package com.DeliveryInventoryService.DeliveryInventoryService.Controller;

import com.DeliveryInventoryService.DeliveryInventoryService.Service.ServiceAvailabilityService;
import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/service")
@RequiredArgsConstructor
public class ServiceAvailabilityController {

    private final ServiceAvailabilityService availabilityService;

    @GetMapping("/check")
    public ResponseEntity<?> checkAvailability(
            @RequestParam String pincode,
            @RequestParam double lat,
            @RequestParam double lng) {

        boolean available = availabilityService.isServiceAvailable(pincode, lat, lng);
        if (available)
            return ResponseEntity.status(200).body("AVAILABLE");
        else
            return ResponseEntity.status(200).body("NOT_AVAILABLE");
    }
}

// hy7iu gyi87yiyuiyjuihiyi87y b7yy78 nhhyiuyh,uih yuiuhiyhu
