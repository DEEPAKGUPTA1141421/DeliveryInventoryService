package com.DeliveryInventoryService.DeliveryInventoryService.Controller.admin;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.zone.ServiceZoneRequest;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.zone.ServiceZoneResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.admin.ServiceZoneAdminService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/service-zones")
@RequiredArgsConstructor
public class ServiceZoneAdminController {

    private final ServiceZoneAdminService service;

    @PostMapping
    public ResponseEntity<ServiceZoneResponse> create(@Valid @RequestBody ServiceZoneRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @GetMapping
    public ResponseEntity<List<ServiceZoneResponse>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ServiceZoneResponse> getById(@PathVariable UUID id) {
        return service.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ServiceZoneResponse> update(@PathVariable UUID id,
                                                      @Valid @RequestBody ServiceZoneRequest request) {
        return service.update(id, request)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Toggle ACTIVE ↔ INACTIVE
    @PatchMapping("/{id}/status")
    public ResponseEntity<ServiceZoneResponse> toggleStatus(@PathVariable UUID id) {
        return service.toggleStatus(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return service.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    // Force-rebuild the Redis cache (useful after DB migrations or manual edits)
    @PostMapping("/cache/rebuild")
    public ResponseEntity<String> rebuildCache() {
        service.rebuildRedisCache();
        return ResponseEntity.ok("Cache rebuilt successfully");
    }
}
