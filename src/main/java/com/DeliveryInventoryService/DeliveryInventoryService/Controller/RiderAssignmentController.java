package com.DeliveryInventoryService.DeliveryInventoryService.Controller;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.RiderRouteResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.RouteAssignment;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.RouteAssignment.AssignmentStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.OrderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RouteAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/riders/{riderId}")
@RequiredArgsConstructor
public class RiderAssignmentController {

    private final RouteAssignmentRepository assignmentRepository;
    private final OrderRepository orderRepository;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * GET /api/v1/riders/{riderId}/route/today
     * Returns today's ordered stop list for the rider.
     */
    @GetMapping("/route/today")
    public ResponseEntity<ApiResponse<RiderRouteResponse>> getTodayRoute(@PathVariable UUID riderId) {
        ZonedDateTime startOfDay = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
                .toLocalDate().atStartOfDay(ZoneId.of("Asia/Kolkata"));

        List<RouteAssignment> assignments = assignmentRepository.findTodayByRider(riderId, startOfDay);

        if (assignments.isEmpty()) {
            return ResponseEntity.ok(new ApiResponse<>(false, "No assignments for today", null, 200));
        }

        // Fetch all orders in one query
        List<UUID> orderIds = assignments.stream()
                .map(RouteAssignment::getOrderId)
                .filter(id -> id != null)
                .collect(Collectors.toList());
        Map<UUID, Order> orderMap = orderIds.isEmpty()
                ? Map.of()
                : orderRepository.findAllById((Iterable<UUID>) orderIds)
                        .stream().collect(Collectors.toMap(Order::getId, o -> o));

        long completed = assignments.stream()
                .filter(a -> a.getStatus() == AssignmentStatus.DELIVERED
                          || a.getStatus() == AssignmentStatus.FAILED)
                .count();

        List<RiderRouteResponse.StopDetail> stops = assignments.stream()
                .map(a -> {
                    Order o = orderMap.get(a.getOrderId());
                    RiderRouteResponse.OrderSummary summary = o == null ? null :
                            new RiderRouteResponse.OrderSummary(
                                    o.getId(), o.getDestAddress(),
                                    o.getDestLat(), o.getDestLng(),
                                    o.getWeightKg(), o.getOrderNo());
                    return new RiderRouteResponse.StopDetail(
                            a.getId(), a.getSequenceNumber(),
                            a.getStatus().name(), summary, a.getEstimatedArrivalAt());
                })
                .collect(Collectors.toList());

        RiderRouteResponse response = new RiderRouteResponse(
                assignments.get(0).getBatchRunId(),
                assignments.size(),
                (int) completed,
                null,
                stops);

        return ResponseEntity.ok(new ApiResponse<>(true, "ok", response, 200));
    }

    /**
     * PATCH /api/v1/riders/{riderId}/assignments/{assignmentId}/status
     * Updates a stop's status (DELIVERED or FAILED).
     */
    @PatchMapping("/assignments/{assignmentId}/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateStatus(
            @PathVariable UUID riderId,
            @PathVariable UUID assignmentId,
            @RequestBody UpdateStatusRequest body) {

        RouteAssignment assignment = assignmentRepository.findById(assignmentId)
                .orElse(null);
        if (assignment == null || !assignment.getRiderId().equals(riderId)) {
            return ResponseEntity.status(404)
                    .body(new ApiResponse<>(false, "Assignment not found", null, 404));
        }

        AssignmentStatus newStatus = AssignmentStatus.valueOf(body.status());
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));

        assignment.setStatus(newStatus);
        if (newStatus == AssignmentStatus.DELIVERED) {
            assignment.setDeliveredAt(now);
        } else if (newStatus == AssignmentStatus.FAILED) {
            assignment.setFailureReason(body.failureReason());
        }
        assignmentRepository.save(assignment);

        // Also update the order status
        UUID orderId = assignment.getOrderId();
        if (orderId != null) orderRepository.findById(orderId).ifPresent(order -> {
            order.setStatus(newStatus == AssignmentStatus.DELIVERED
                    ? Order.OrderStatus.DELIVERED : Order.OrderStatus.CANCELLED);
            orderRepository.save(order);
        });

        // Broadcast to warehouse dashboard
        Map<String, String> wsPayload = new HashMap<>();
        wsPayload.put("type",         "ORDER_STATUS");
        wsPayload.put("assignmentId", assignmentId.toString());
        wsPayload.put("riderId",      riderId.toString());
        wsPayload.put("newStatus",    newStatus.name());
        wsPayload.put("updatedAt",    now.toString());
        messagingTemplate.convertAndSend("/topic/warehouse/orders/status", wsPayload);

        // Find next pending stop for the rider
        ZonedDateTime startOfDay = now.toLocalDate().atStartOfDay(ZoneId.of("Asia/Kolkata"));
        List<RouteAssignment> remaining = assignmentRepository.findActiveByRider(riderId, startOfDay);
        Map<String, Object> responseData = remaining.isEmpty()
                ? Map.of("assignmentId", assignmentId, "newStatus", newStatus, "nextStop", "NONE")
                : Map.of("assignmentId", assignmentId, "newStatus", newStatus,
                         "nextStop", remaining.get(0).getSequenceNumber());

        return ResponseEntity.ok(new ApiResponse<>(true, "Status updated", responseData, 200));
    }

    public record UpdateStatusRequest(String status, String failureReason) {}
}
