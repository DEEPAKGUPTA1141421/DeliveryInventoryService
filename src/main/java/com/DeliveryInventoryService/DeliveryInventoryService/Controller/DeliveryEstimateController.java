package com.DeliveryInventoryService.DeliveryInventoryService.Controller;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.DeliveryEstimateResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.DeliverySegmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DeliveryEstimateController
 * ───────────────────────────
 *
 * GET /api/v1/delivery/estimate
 *
 * Returns a 3-segment delivery ETA for a shop → user pair.
 * Called by ProductClientService (via OpenFeign in Phase 3) to enrich
 * each shop card in the shop listing with an etaLabel and distanceKm.
 *
 * Parameters
 * ──────────
 *  shopLat  (required) — shop latitude
 *  shopLng  (required) — shop longitude
 *  userLat  (required) — user delivery latitude
 *  userLng  (required) — user delivery longitude
 *
 * Example
 * ───────
 *  GET /api/v1/delivery/estimate?shopLat=12.97&shopLng=77.59&userLat=13.02&userLng=77.64
 *
 * Response
 * ────────
 *  {
 *    "seg1Km": 3.4,
 *    "seg2Km": 0.0,
 *    "seg3Km": 2.1,
 *    "totalKm": 5.5,
 *    "etaMinutes": 165,
 *    "etaLabel": "2 hrs",
 *    "srcWarehouseCity": "Bengaluru",
 *    "destWarehouseCity": "Bengaluru",
 *    "sameCityDelivery": true
 *  }
 */
@RestController
@RequestMapping("/api/v1/delivery")
@RequiredArgsConstructor
@Slf4j
public class DeliveryEstimateController {

    private final DeliverySegmentService deliverySegmentService;

    @GetMapping("/estimate")
    public ResponseEntity<DeliveryEstimateResponse> estimate(
            @RequestParam double shopLat,
            @RequestParam double shopLng,
            @RequestParam double userLat,
            @RequestParam double userLng) {

        log.debug("Delivery estimate request: shop({},{}) → user({},{})",
                shopLat, shopLng, userLat, userLng);

        DeliveryEstimateResponse response =
                deliverySegmentService.calculate(shopLat, shopLng, userLat, userLng);

        return ResponseEntity.ok(response);
    }
}
