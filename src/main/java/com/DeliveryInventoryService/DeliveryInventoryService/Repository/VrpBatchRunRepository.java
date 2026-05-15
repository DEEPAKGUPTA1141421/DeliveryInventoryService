package com.DeliveryInventoryService.DeliveryInventoryService.Repository;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.VrpBatchRun;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.VrpBatchRun.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface VrpBatchRunRepository extends JpaRepository<VrpBatchRun, UUID> {

    List<VrpBatchRun> findByStatusOrderByStartedAtDesc(RunStatus status);

    boolean existsByStatusAndWarehouseIdAndStartedAtBetween(
            RunStatus status,
            UUID warehouseId,
            ZonedDateTime startOfDay,
            ZonedDateTime endOfDay);
}
