package com.DeliveryInventoryService.DeliveryInventoryService.Controller;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/riders")
@RequiredArgsConstructor
@Slf4j
public class RiderLocationController {

    private static final String LOCATION_KEY_PREFIX   = "rider:location:";
    private static final String ACTIVE_RIDERS_KEY     = "warehouse:active_riders:";
    private static final long   LOCATION_TTL_MINUTES  = 30; // rider considered offline after 30min

    private final StringRedisTemplate redisTemplate;

    /**
     * POST /api/v1/riders/{riderId}/location
     * Rider app calls this every 30 seconds with current GPS position.
     */
    @PostMapping("/{riderId}/location")
    public ResponseEntity<Void> updateLocation(
            @PathVariable UUID riderId,
            @RequestBody LocationPayload payload) {

        String key = LOCATION_KEY_PREFIX + riderId;
        Map<String, String> fields = new HashMap<>();
        fields.put("riderId",   riderId.toString());
        fields.put("lat",       String.valueOf(payload.lat()));
        fields.put("lng",       String.valueOf(payload.lng()));
        fields.put("heading",   String.valueOf(payload.heading()));
        fields.put("speedKmh",  String.valueOf(payload.speedKmh()));
        fields.put("updatedAt", ZonedDateTime.now(ZoneId.of("Asia/Kolkata")).toString());

        if (payload.warehouseId() != null) {
            fields.put("warehouseId", payload.warehouseId().toString());
            // Track which riders belong to which warehouse for the dashboard
            redisTemplate.opsForSet().add(
                    ACTIVE_RIDERS_KEY + payload.warehouseId(), riderId.toString());
        }

        redisTemplate.opsForHash().putAll(key, fields);
        redisTemplate.expire(key, LOCATION_TTL_MINUTES, TimeUnit.MINUTES);

        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/v1/admin/warehouse/{warehouseId}/riders/live
     * Returns current GPS location of all active riders for a warehouse.
     * Used as the initial load for the dashboard map before WebSocket takes over.
     */
    @GetMapping("/admin/warehouse/{warehouseId}/riders/live")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getLiveRiders(
            @PathVariable UUID warehouseId) {

        var riderIds = redisTemplate.opsForSet().members(ACTIVE_RIDERS_KEY + warehouseId);
        if (riderIds == null || riderIds.isEmpty()) {
            return ResponseEntity.ok(new ApiResponse<>(true, "No active riders", List.of(), 200));
        }

        List<Map<String, Object>> riders = riderIds.stream()
                .map(riderId -> {
                    Map<Object, Object> raw = redisTemplate.opsForHash()
                            .entries(LOCATION_KEY_PREFIX + riderId);
                    Map<String, Object> loc = new HashMap<>();
                    raw.forEach((k, v) -> loc.put(k.toString(), v));
                    return loc;
                })
                .filter(m -> !m.isEmpty())
                .collect(Collectors.toList());

        return ResponseEntity.ok(new ApiResponse<>(true, "ok", riders, 200));
    }

    public record LocationPayload(
            double lat,
            double lng,
            double heading,
            double speedKmh,
            UUID warehouseId
    ) {}
}
