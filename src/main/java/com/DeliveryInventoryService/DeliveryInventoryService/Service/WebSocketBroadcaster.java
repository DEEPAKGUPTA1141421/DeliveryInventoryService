package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reads rider GPS positions from Redis every 5 seconds and broadcasts
 * them to all connected warehouse dashboard clients via WebSocket.
 *
 * Server-side throttle: no matter how often riders POST /location,
 * the dashboard only receives one update per rider per 5-second window.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebSocketBroadcaster {

    private static final String ACTIVE_RIDERS_PATTERN = "warehouse:active_riders:*";
    private static final String LOCATION_KEY_PREFIX   = "rider:location:";

    private final StringRedisTemplate redisTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    @Scheduled(fixedDelayString = "${app.ws.broadcast.interval-ms:5000}")
    public void broadcastRiderLocations() {
        // Find all active-rider sets (one per warehouse)
        Set<String> warehouseKeys = redisTemplate.keys(ACTIVE_RIDERS_PATTERN);
        if (warehouseKeys == null || warehouseKeys.isEmpty()) return;

        for (String warehouseKey : warehouseKeys) {
            // Key format: warehouse:active_riders:{warehouseId}
            String warehouseId = warehouseKey.replace("warehouse:active_riders:", "");
            String topic = "/topic/warehouse/" + warehouseId + "/live";

            Set<String> riderIds = redisTemplate.opsForSet().members(warehouseKey);
            if (riderIds == null || riderIds.isEmpty()) continue;

            for (String riderId : riderIds) {
                Map<Object, Object> raw = redisTemplate.opsForHash()
                        .entries(LOCATION_KEY_PREFIX + riderId);
                if (raw.isEmpty()) continue; // TTL expired → rider offline

                Map<String, String> payload = new HashMap<>();
                payload.put("type", "RIDER_LOCATION");
                raw.forEach((k, v) -> payload.put(k.toString(), v != null ? v.toString() : ""));

                messagingTemplate.convertAndSend(topic, payload);
            }

            log.debug("Broadcast rider locations: warehouseId={}, riders={}", warehouseId, riderIds.size());
        }
    }
}
