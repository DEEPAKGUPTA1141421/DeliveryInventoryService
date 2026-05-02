package com.DeliveryInventoryService.DeliveryInventoryService.Controller;

import com.DeliveryInventoryService.DeliveryInventoryService.tracking.VehicleLocationEvent;
import com.DeliveryInventoryService.DeliveryInventoryService.tracking.VehicleLocationProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Receives GPS pings from vehicle apps / IoT devices and fires them
 * onto Kafka asynchronously — response returns immediately (fire-and-forget).
 */
@RestController
@RequestMapping("/api/v1/tracking")
@RequiredArgsConstructor
public class VehicleTrackingController {

    private final VehicleLocationProducer producer;

    /**
     * POST /api/v1/tracking/location
     *
     * Body example:
     * {
     *   "routeId": "...",
     *   "vehicleId": "...",
     *   "currentFromSequence": 2,
     *   "lat": 28.6139,
     *   "lon": 77.2090
     * }
     */
    @PostMapping("/location")
    public ResponseEntity<Void> updateLocation(@RequestBody LocationRequest req) {
        VehicleLocationEvent event = new VehicleLocationEvent(
                req.routeId(),
                req.vehicleId(),
                req.currentFromSequence(),
                req.lat(),
                req.lon(),
                LocalDateTime.now()
        );
        producer.publish(event);
        return ResponseEntity.accepted().build();
    }

    public record LocationRequest(
            UUID routeId,
            UUID vehicleId,
            int currentFromSequence,
            double lat,
            double lon
    ) {}
}
