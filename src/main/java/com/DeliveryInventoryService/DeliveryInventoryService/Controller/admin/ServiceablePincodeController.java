package com.DeliveryInventoryService.DeliveryInventoryService.Controller.admin;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.ServiceablePincode;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.admin.ServiceablePincodeService;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/serviceable-areas")
@RequiredArgsConstructor
public class ServiceablePincodeController {

    private final ServiceablePincodeService service;

    // CREATE
    @PostMapping
    public ResponseEntity<ServiceablePincode> create(@RequestBody ServiceablePincode area) {
        return ResponseEntity.ok(service.create(area));
    }

    // READ ALL
    @GetMapping
    public ResponseEntity<List<ServiceablePincode>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    // READ ONE
    @GetMapping("/{id}")
    public ResponseEntity<ServiceablePincode> getById(@PathVariable UUID id) {
        return service.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // UPDATE
    @PutMapping("/{id}")
    public ResponseEntity<ServiceablePincode> update(@PathVariable UUID id,
            @RequestBody ServiceablePincode area) {
        return service.update(id, area)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // DELETE
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        return service.delete(id)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }
}

// hihyuyiuiohiyiy9uuioui