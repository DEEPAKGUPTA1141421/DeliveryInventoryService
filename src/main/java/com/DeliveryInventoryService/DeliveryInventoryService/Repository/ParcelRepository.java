package com.DeliveryInventoryService.DeliveryInventoryService.Repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel;

import java.util.UUID;

@Repository

public interface ParcelRepository extends JpaRepository<Parcel, UUID> {
}
