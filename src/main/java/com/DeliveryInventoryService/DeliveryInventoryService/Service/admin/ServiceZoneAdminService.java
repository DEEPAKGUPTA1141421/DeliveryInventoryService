package com.DeliveryInventoryService.DeliveryInventoryService.Service.admin;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.zone.LatLngPoint;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.zone.ServiceZoneRequest;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.zone.ServiceZoneResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.ServiceZone;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ServiceZoneRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Utils.GeohashUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ServiceZoneAdminService {

    private final ServiceZoneRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    // Redis keys
public static final String REDIS_USER_KEY = "zones:active:user";
    public static final String REDIS_SELLER_KEY = "zones:active:seller";
    private static final int GEOHASH_PRECISION = 6; // ~1.2km × 0.6km cell

    // -----------------------------------------------------------------------
    // CREATE
    // -----------------------------------------------------------------------
    @Transactional
    public ServiceZoneResponse create(ServiceZoneRequest request) {
        validate(request);
        ServiceZone zone = toEntity(new ServiceZone(), request);
        zone.setStatus(ServiceZone.ZoneStatus.ACTIVE);
        ServiceZone saved = repository.save(zone);
        addToRedisCache(saved);
        return toResponse(saved);
    }

    // -----------------------------------------------------------------------
    // READ ALL
    // -----------------------------------------------------------------------
    public List<ServiceZoneResponse> getAll() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    // -----------------------------------------------------------------------
    // READ ONE
    // -----------------------------------------------------------------------
    public Optional<ServiceZoneResponse> getById(UUID id) {
        return repository.findById(id).map(this::toResponse);
    }

    // -----------------------------------------------------------------------
    // UPDATE
    // -----------------------------------------------------------------------
    @Transactional
    public Optional<ServiceZoneResponse> update(UUID id, ServiceZoneRequest request) {
        return repository.findById(id).map(existing -> {
            validate(request);
            toEntity(existing, request);
            ServiceZone saved = repository.save(existing);
            rebuildRedisCache();
            return toResponse(saved);
        });
    }

    // -----------------------------------------------------------------------
    // TOGGLE STATUS (ACTIVE ↔ INACTIVE)
    // -----------------------------------------------------------------------
    @Transactional
    public Optional<ServiceZoneResponse> toggleStatus(UUID id) {
        return repository.findById(id).map(zone -> {
            zone.setStatus(zone.getStatus() == ServiceZone.ZoneStatus.ACTIVE
                    ? ServiceZone.ZoneStatus.INACTIVE
                    : ServiceZone.ZoneStatus.ACTIVE);
            ServiceZone saved = repository.save(zone);
            rebuildRedisCache();
            return toResponse(saved);
        });
    }

    // -----------------------------------------------------------------------
    // DELETE
    // -----------------------------------------------------------------------
    @Transactional
    public boolean delete(UUID id) {
        if (!repository.existsById(id))
            return false;
        repository.deleteById(id);
        rebuildRedisCache();
        return true;
    }

    // -----------------------------------------------------------------------
    // REDIS CACHE MANAGEMENT
    // Rebuilds both sets from scratch so they always reflect DB truth.
    // Zone ops are infrequent (admin only), so full rebuild is acceptable.
    // -----------------------------------------------------------------------
    public void rebuildRedisCache() {
        redisTemplate.delete(REDIS_USER_KEY);
        redisTemplate.delete(REDIS_SELLER_KEY);

        List<ServiceZone> activeZones = repository.findByStatus(ServiceZone.ZoneStatus.ACTIVE);
        for (ServiceZone zone : activeZones) {
            addToRedisCache(zone);
        }
    }

    private void addToRedisCache(ServiceZone zone) {
        List<String> cells = computeGeohashCells(zone);
        if (cells.isEmpty())
            return;

        String[] cellArray = cells.toArray(new String[0]);

        if (zone.getTarget() == ServiceZone.ZoneTarget.USER
                || zone.getTarget() == ServiceZone.ZoneTarget.BOTH) {
            redisTemplate.opsForSet().add(REDIS_USER_KEY, cellArray);
        }
        if (zone.getTarget() == ServiceZone.ZoneTarget.SELLER
                || zone.getTarget() == ServiceZone.ZoneTarget.BOTH) {
            redisTemplate.opsForSet().add(REDIS_SELLER_KEY, cellArray);
        }
    }

    // -----------------------------------------------------------------------
    // GEOHASH COVERAGE
    // Computes all geohash-6 cells that overlap the zone's bounding box.
    // -----------------------------------------------------------------------
    List<String> computeGeohashCells(ServiceZone zone) {
        if (zone.getShapeType() == ServiceZone.ZoneShapeType.CIRCLE) {
            return cellsForCircle(zone.getCenterLat(), zone.getCenterLng(), zone.getRadiusMeters());
        } else {
            return cellsForPolygon(zone.getPolygonPointsJson());
        }
    }

    private List<String> cellsForCircle(double lat, double lng, double radiusMeters) {
        double deltaLat = (radiusMeters / 1000.0) / 111.0;
        double deltaLng = (radiusMeters / 1000.0) / (111.0 * Math.cos(Math.toRadians(lat)));
        return GeohashUtils.coverBoundingBox(
                lat - deltaLat, lng - deltaLng,
                lat + deltaLat, lng + deltaLng,
                GEOHASH_PRECISION);
    }

    private List<String> cellsForPolygon(String polygonJson) {
        List<LatLngPoint> points = parsePolygon(polygonJson);
        if (points == null || points.isEmpty())
            return List.of();

        double minLat = points.stream().mapToDouble(LatLngPoint::getLat).min().orElse(0);
        double maxLat = points.stream().mapToDouble(LatLngPoint::getLat).max().orElse(0);
        double minLng = points.stream().mapToDouble(LatLngPoint::getLng).min().orElse(0);
        double maxLng = points.stream().mapToDouble(LatLngPoint::getLng).max().orElse(0);

        return GeohashUtils.coverBoundingBox(minLat, minLng, maxLat, maxLng, GEOHASH_PRECISION);
    }

    // -----------------------------------------------------------------------
    // HELPERS
    // -----------------------------------------------------------------------
    private void validate(ServiceZoneRequest req) {
        if (req.getShapeType() == ServiceZone.ZoneShapeType.CIRCLE) {
            if (req.getCenterLat() == null || req.getCenterLng() == null || req.getRadiusMeters() == null) {
                throw new IllegalArgumentException("CIRCLE requires centerLat, centerLng, and radiusMeters");
            }
        } else {
            if (req.getPolygonPoints() == null || req.getPolygonPoints().size() < 3) {
                throw new IllegalArgumentException("POLYGON requires at least 3 polygon points");
            }
        }
    }

    private ServiceZone toEntity(ServiceZone zone, ServiceZoneRequest req) {
        zone.setName(req.getName());
        zone.setCity(req.getCity());
        zone.setDescription(req.getDescription());
        zone.setShapeType(req.getShapeType());
        zone.setTarget(req.getTarget());

        if (req.getShapeType() == ServiceZone.ZoneShapeType.CIRCLE) {
            zone.setCenterLat(req.getCenterLat());
            zone.setCenterLng(req.getCenterLng());
            zone.setRadiusMeters(req.getRadiusMeters());
            zone.setPolygonPointsJson(null);
        } else {
            zone.setPolygonPointsJson(serializePolygon(req.getPolygonPoints()));
            zone.setCenterLat(null);
            zone.setCenterLng(null);
            zone.setRadiusMeters(null);
        }
        return zone;
    }

    ServiceZoneResponse toResponse(ServiceZone zone) {
        ServiceZoneResponse res = new ServiceZoneResponse();
        res.setId(zone.getId());
        res.setName(zone.getName());
        res.setCity(zone.getCity());
        res.setDescription(zone.getDescription());
        res.setShapeType(zone.getShapeType());
        res.setTarget(zone.getTarget());
        res.setStatus(zone.getStatus());
        res.setCenterLat(zone.getCenterLat());
        res.setCenterLng(zone.getCenterLng());
        res.setRadiusMeters(zone.getRadiusMeters());
        res.setPolygonPoints(parsePolygon(zone.getPolygonPointsJson()));
        res.setCreatedAt(zone.getCreatedAt());
        res.setUpdatedAt(zone.getUpdatedAt());
        return res;
    }

    private String serializePolygon(List<LatLngPoint> points) {
        try {
            return objectMapper.writeValueAsString(points);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize polygon points", e);
        }
    }

    public List<LatLngPoint> parsePolygon(String json) {
        if (json == null || json.isBlank())
            return null;
        try {
            return objectMapper.readValue(json, new TypeReference<List<LatLngPoint>>() {
            });
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
