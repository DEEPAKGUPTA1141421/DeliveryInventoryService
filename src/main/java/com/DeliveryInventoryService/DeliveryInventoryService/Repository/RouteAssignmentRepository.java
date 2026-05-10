package com.DeliveryInventoryService.DeliveryInventoryService.Repository;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.RouteAssignment;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.RouteAssignment.AssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RouteAssignmentRepository extends JpaRepository<RouteAssignment, UUID> {

    @Query("""
            SELECT ra FROM RouteAssignment ra
            WHERE ra.riderId = :riderId
              AND ra.createdAt >= :startOfDay
            ORDER BY ra.sequenceNumber ASC
            """)
    List<RouteAssignment> findTodayByRider(@Param("riderId") UUID riderId,
                                           @Param("startOfDay") ZonedDateTime startOfDay);

    List<RouteAssignment> findByBatchRunIdOrderBySequenceNumber(UUID batchRunId);

    Optional<RouteAssignment> findByOrderId(UUID orderId);

    @Modifying
    @Query("UPDATE RouteAssignment ra SET ra.status = :status WHERE ra.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") AssignmentStatus status);

    @Query("""
            SELECT ra FROM RouteAssignment ra
            WHERE ra.riderId = :riderId
              AND ra.status IN ('ASSIGNED', 'PICKED')
              AND ra.createdAt >= :startOfDay
            ORDER BY ra.sequenceNumber ASC
            """)
    List<RouteAssignment> findActiveByRider(@Param("riderId") UUID riderId,
                                            @Param("startOfDay") ZonedDateTime startOfDay);
}
