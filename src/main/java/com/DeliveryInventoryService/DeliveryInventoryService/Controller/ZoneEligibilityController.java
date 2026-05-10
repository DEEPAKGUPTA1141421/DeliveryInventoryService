package com.DeliveryInventoryService.DeliveryInventoryService.Controller;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.zone.ServiceZoneResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.zone.ZoneCheckResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.ServiceZone;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.ZoneEligibilityService;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.admin.ServiceZoneAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/zones")
@RequiredArgsConstructor
public class ZoneEligibilityController {

    private final ZoneEligibilityService eligibilityService;
    private final ServiceZoneAdminService adminService;

    /**
     * Check if a location is within a serviceable zone.
     *
     * Used by seller app: GET /api/v1/zones/check?lat=28.5&lng=77.1&type=SELLER
     * Used by user app:   GET /api/v1/zones/check?lat=28.5&lng=77.1&type=USER
     */
    @GetMapping("/check")
    public ResponseEntity<ZoneCheckResponse> check(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam(defaultValue = "USER") ServiceZone.ZoneTarget type) {

        ZoneCheckResponse response = eligibilityService.check(lat, lng, type);
        return ResponseEntity.ok(response);
    }

    /**
     * Returns all active zone boundaries for a given target type.
     * Useful for displaying a map overlay in the seller/user app.
     */
    @GetMapping("/active")
    public ResponseEntity<List<ServiceZoneResponse>> activeZones(
            @RequestParam(defaultValue = "USER") ServiceZone.ZoneTarget type) {

        List<ServiceZoneResponse> zones = adminService.getAll().stream()
                .filter(z -> z.getStatus() == ServiceZone.ZoneStatus.ACTIVE)
                .filter(z -> z.getTarget() == type || z.getTarget() == ServiceZone.ZoneTarget.BOTH)
                .toList();

        return ResponseEntity.ok(zones);
    }
}
