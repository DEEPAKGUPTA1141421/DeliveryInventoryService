package com.DeliveryInventoryService.DeliveryInventoryService.Repository;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.ServiceZone;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ServiceZoneRepository extends JpaRepository<ServiceZone, UUID> {

    List<ServiceZone> findByStatus(ServiceZone.ZoneStatus status);

    @Query("SELECT z FROM ServiceZone z WHERE z.status = 'ACTIVE' AND (z.target = :target OR z.target = 'BOTH')")
    List<ServiceZone> findActiveByTarget(@Param("target") ServiceZone.ZoneTarget target);
}
