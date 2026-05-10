package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.zone.LatLngPoint;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.zone.ZoneCheckResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.ServiceZone;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ServiceZoneRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.admin.ServiceZoneAdminService;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.GeoUtils;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.GeohashUtils;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.PointInPolygon;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ZoneEligibilityService {

    private final ServiceZoneRepository repository;
    private final ServiceZoneAdminService adminService;
    private final StringRedisTemplate redisTemplate;

    /**
     * Checks whether (lat, lng) falls inside any active zone for the given target type.
     *
     * Flow:
     * 1. Redis fast-fail — compute geohash-6, SISMEMBER the active set. If miss → not serviceable.
     * 2. DB precise check — load active zones for the target, run circle/polygon test.
     */
    public ZoneCheckResponse check(double lat, double lng, ServiceZone.ZoneTarget target) {
        String redisKey = redisKey(target);
        String geohash = GeohashUtils.encode(lat, lng, 6);

        Boolean inRange = redisTemplate.opsForSet().isMember(redisKey, geohash);
        if (Boolean.FALSE.equals(inRange)) {
            return notServiceable();
        }

        List<ServiceZone> candidates = repository.findActiveByTarget(target);
        for (ServiceZone zone : candidates) {
            if (isInsideZone(zone, lat, lng)) {
                return ZoneCheckResponse.builder()
                        .serviceable(true)
                        .zoneId(zone.getId())
                        .zoneName(zone.getName())
                        .message("Delivery available in your area")
                        .build();
            }
        }

        return notServiceable();
    }

    private boolean isInsideZone(ServiceZone zone, double lat, double lng) {
        if (zone.getShapeType() == ServiceZone.ZoneShapeType.CIRCLE) {
            double distKm = GeoUtils.distanceKm(zone.getCenterLat(), zone.getCenterLng(), lat, lng);
            return distKm * 1000 <= zone.getRadiusMeters();
        } else {
            List<LatLngPoint> polygon = adminService.parsePolygon(zone.getPolygonPointsJson());
            return polygon != null && PointInPolygon.contains(polygon, lat, lng);
        }
    }

    private String redisKey(ServiceZone.ZoneTarget target) {
        return switch (target) {
            case USER   -> ServiceZoneAdminService.REDIS_USER_KEY;
            case SELLER -> ServiceZoneAdminService.REDIS_SELLER_KEY;
            // BOTH is only used internally on zones, not as a check target
            default     -> ServiceZoneAdminService.REDIS_USER_KEY;
        };
    }

    private ZoneCheckResponse notServiceable() {
        return ZoneCheckResponse.builder()
                .serviceable(false)
                .message("We are not in your area yet. We're expanding soon!")
                .build();
    }
}
