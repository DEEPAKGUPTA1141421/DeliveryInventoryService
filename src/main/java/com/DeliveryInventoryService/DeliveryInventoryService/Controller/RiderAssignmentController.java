package com.DeliveryInventoryService.DeliveryInventoryService.Controller;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.RiderRouteResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.OtpLog;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel.ParcelStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.RouteAssignment;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.RouteAssignment.AssignmentStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.OrderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ParcelRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RouteAssignmentRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.kafka.ParcelLifecycleEvent;
import com.DeliveryInventoryService.DeliveryInventoryService.kafka.ParcelLifecycleProducer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
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
        private final ParcelRepository parcelRepository;
        private final ParcelLifecycleProducer lifecycleProducer;
        private final SimpMessagingTemplate messagingTemplate;

        private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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
                                        RiderRouteResponse.OrderSummary summary = o == null ? null
                                                        : new RiderRouteResponse.OrderSummary(
                                                                        o.getId(),
                                                                        o.getOriginAddress(), o.getOriginLat(),
                                                                        o.getOriginLng(),
                                                                        o.getDestAddress(), o.getDestLat(),
                                                                        o.getDestLng(),
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
                if (orderId != null)
                        orderRepository.findById(orderId).ifPresent(order -> {
                                order.setStatus(newStatus == AssignmentStatus.DELIVERED
                                                ? Order.OrderStatus.DELIVERED
                                                : Order.OrderStatus.CANCELLED);
                                orderRepository.save(order);
                        });

                // Broadcast to warehouse dashboard
                Map<String, String> wsPayload = new HashMap<>();
                wsPayload.put("type", "ORDER_STATUS");
                wsPayload.put("assignmentId", assignmentId.toString());
                wsPayload.put("riderId", riderId.toString());
                wsPayload.put("newStatus", newStatus.name());
                wsPayload.put("updatedAt", now.toString());
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

        public record UpdateStatusRequest(String status, String failureReason) {
        }

        /**
         * POST /api/v1/riders/{riderId}/assignments/{assignmentId}/pickup
         * Generates a seller OTP (if not already set), saves it on the parcel,
         * and publishes SELLER_OTP_REQUESTED to Kafka so NotificationService
         * sends an SMS to the seller.
         * Status does NOT change here — moves to PICKED only after OTP is verified.
         */
        @PostMapping("/assignments/{assignmentId}/pickup")
        public ResponseEntity<ApiResponse<Map<String, Object>>> pickupAssignment(
                        @PathVariable UUID riderId,
                        @PathVariable UUID assignmentId,
                        @RequestBody(required = false) PickupRequest body) {

                RouteAssignment assignment = assignmentRepository.findById(assignmentId).orElse(null);
                if (assignment == null || !assignment.getRiderId().equals(riderId)) {
                        return ResponseEntity.status(404)
                                        .body(new ApiResponse<>(false, "Assignment not found", null, 404));
                }
                if (assignment.getStatus() != AssignmentStatus.ASSIGNED) {
                        return ResponseEntity.badRequest()
                                        .body(new ApiResponse<>(false, "Assignment is not in ASSIGNED state", null, 400));
                }

                UUID orderId = assignment.getOrderId();
                if (orderId == null) {
                        return ResponseEntity.badRequest()
                                        .body(new ApiResponse<>(false, "No order linked to this assignment", null, 400));
                }

                Parcel parcel = parcelRepository.findByOrderId(orderId).orElse(null);
                if (parcel == null) {
                        return ResponseEntity.status(404)
                                        .body(new ApiResponse<>(false, "Parcel not found for this order", null, 404));
                }

                // Generate a fresh 6-digit OTP if warehouse admin hasn't set one
                if (parcel.getSellerPickupOtp() == null || parcel.getSellerPickupOtp().isBlank()) {
                        String otp = String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
                        parcel.setSellerPickupOtp(otp);
                        parcelRepository.save(parcel);
                }

                // sellerPhone from request body — rider provides seller's number at pickup
                String sellerPhone = (body != null) ? body.sellerPhone() : null;

                // Publish event — NotificationService sends OTP SMS to seller
                lifecycleProducer.publish(ParcelLifecycleEvent.builder()
                                .parcelId(parcel.getId())
                                .orderId(orderId)
                                .riderId(riderId)
                                .eventType(ParcelLifecycleEvent.EventType.SELLER_OTP_REQUESTED)
                                .newStatus(ParcelStatus.AWAITING_PICKUP)
                                .otpType(OtpLog.OtpType.SELLER_PICKUP)
                                .recipientPhone(sellerPhone)
                                .notes(parcel.getSellerPickupOtp())
                                .occurredAt(ZonedDateTime.now(ZoneId.of("Asia/Kolkata")))
                                .build());

                Map<String, Object> data = Map.of(
                                "assignmentId", assignmentId,
                                "message", "OTP sent to seller. Ask the seller for the code.");
                return ResponseEntity.ok(new ApiResponse<>(true, "OTP dispatched to seller", data, 200));
        }

        public record PickupRequest(String sellerPhone) {}

        /**
         * POST /api/v1/riders/{riderId}/assignments/{assignmentId}/verify-seller-otp
         * Rider enters OTP given by seller to confirm physical handover.
         * Transitions: Parcel AWAITING_PICKUP → PICKED_BY_RIDER.
         */
        @PostMapping("/assignments/{assignmentId}/verify-seller-otp")
        public ResponseEntity<ApiResponse<Map<String, Object>>> verifySellerOtp(
                        @PathVariable UUID riderId,
                        @PathVariable UUID assignmentId,
                        @RequestBody VerifyOtpRequest body) {

                RouteAssignment assignment = assignmentRepository.findById(assignmentId).orElse(null);
                if (assignment == null || !assignment.getRiderId().equals(riderId)) {
                        return ResponseEntity.status(404)
                                        .body(new ApiResponse<>(false, "Assignment not found", null, 404));
                }

                UUID orderId = assignment.getOrderId();
                if (orderId == null) {
                        return ResponseEntity.badRequest()
                                        .body(new ApiResponse<>(false, "No order linked to this assignment", null,
                                                        400));
                }

                Parcel parcel = parcelRepository.findByOrderId(orderId).orElse(null);
                if (parcel == null) {
                        return ResponseEntity.status(404)
                                        .body(new ApiResponse<>(false, "Parcel not found for this order", null, 404));
                }

                String storedOtp = parcel.getSellerPickupOtp();
                if (storedOtp == null || !storedOtp.trim().equals(body.otp().trim())) {
                        return ResponseEntity.badRequest()
                                        .body(new ApiResponse<>(false, "Invalid OTP. Please try again.", null, 400));
                }

                ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));

                // Mark parcel as collected
                parcel.setSellerPickupOtpVerified(true);
                parcel.setStatus(ParcelStatus.PICKED_BY_RIDER);
                parcel.setPickupRiderId(riderId);
                parcel.setPickedAt(now);
                parcelRepository.save(parcel);

                // Only NOW move assignment to PICKED (after OTP verified)
                assignment.setStatus(AssignmentStatus.PICKED);
                assignment.setPickedAt(now);
                assignmentRepository.save(assignment);

                if (orderId != null) {
                        orderRepository.findById(orderId).ifPresent(order -> {
                                order.setStatus(Order.OrderStatus.PICKED);
                                orderRepository.save(order);
                        });
                }

                // Notify downstream services (NotificationService, Analytics)
                lifecycleProducer.publish(ParcelLifecycleEvent.builder()
                                .parcelId(parcel.getId())
                                .orderId(orderId)
                                .riderId(riderId)
                                .eventType(ParcelLifecycleEvent.EventType.SELLER_OTP_VERIFIED)
                                .newStatus(ParcelStatus.PICKED_BY_RIDER)
                                .otpType(OtpLog.OtpType.SELLER_PICKUP)
                                .occurredAt(now)
                                .build());

                Map<String, Object> data = Map.of(
                                "assignmentId", assignmentId,
                                "assignmentStatus", "PICKED",
                                "parcelStatus", "PICKED_BY_RIDER",
                                "message", "Seller OTP verified. Parcel confirmed as collected.");
                return ResponseEntity.ok(new ApiResponse<>(true, "OTP verified", data, 200));
        }

        public record VerifyOtpRequest(String otp) {
        }
}
// jiiojkji