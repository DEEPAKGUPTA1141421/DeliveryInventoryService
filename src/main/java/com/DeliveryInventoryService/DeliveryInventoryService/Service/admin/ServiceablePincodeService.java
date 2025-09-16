package com.DeliveryInventoryService.DeliveryInventoryService.Service.admin;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.ServiceablePincode;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ServiceablePincodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ServiceablePincodeService {

    private final ServiceablePincodeRepository repository;

    // CREATE
    public ServiceablePincode create(ServiceablePincode area) {
        return repository.save(area);
    }

    // READ ALL
    public List<ServiceablePincode> getAll() {
        return repository.findAll();
    }

    // READ ONE
    public Optional<ServiceablePincode> getById(UUID id) {
        return repository.findById(id);
    }

    // UPDATE
    public Optional<ServiceablePincode> update(UUID id, ServiceablePincode updated) {
        return repository.findById(id).map(existing -> {
            existing.setPincode(updated.getPincode());
            existing.setCity(updated.getCity());
            return repository.save(existing);
        });
    }

    // DELETE
    public boolean delete(UUID id) {
        if (repository.existsById(id)) {
            repository.deleteById(id);
            return true;
        }
        return false;
    }
}
