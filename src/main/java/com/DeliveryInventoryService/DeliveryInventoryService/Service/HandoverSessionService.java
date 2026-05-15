package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.WarehouseDTO.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.HandoverSession;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.HandoverSession.Status;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order.OrderStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel.ParcelStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Rider;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.OrderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ParcelRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RiderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * HandoverSessionService
 * ──────────────────────
 * Industry-standard batch handover: one OTP authenticates the rider,
 * then the admin scans each order barcode, then confirms the whole batch.
 *
 * Sessions live in Redis for 30 minutes (no DB table needed).
 *
 * API flow:
 * 1. POST /session/start → generates OTP, SMSs rider, returns sessionId
 * 2. POST /session/{id}/verify-otp → rider gives OTP to admin → session ACTIVE
 * 3. POST /session/{id}/scan → admin scans each order barcode (repeatable)
 * 4. DELETE /session/{id}/scan/{orderNo} → admin removes a mistaken scan
 * 5. GET /session/{id} → current session state + scanned list
 * 6. POST /session/{id}/confirm → commits all scanned orders → WAREHOUSE status
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HandoverSessionService {

    private final RedisTemplate<String, Object> etaRedisTemplate;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final RiderRepository riderRepository;
    private final OrderRepository orderRepository;
    private final ParcelRepository parcelRepository;
    private final WarehouseRepository warehouseRepository;

    private static final long SESSION_TTL_MINUTES = 30;
    private static final String SESSION_KEY_PREFIX = "handover:session:";

    private static final Set<OrderStatus> RECEIVABLE_STATES = EnumSet.of(
            OrderStatus.CREATED,
            OrderStatus.PICKUP_SCHEDULED,
            OrderStatus.PICKED,
            OrderStatus.ASSIGNED,
            OrderStatus.PENDING);

    // ── 1. Start session ──────────────────────────────────────────────────

    public ApiResponse<Object> startSession(UUID warehouseId, StartHandoverSessionRequest req) {
        if (warehouseId == null || warehouseRepository.findById(warehouseId).isEmpty()) {
            return new ApiResponse<>(false, "Warehouse not found", null, 404);
        }
        if (req.getRiderId() == null) {
            return new ApiResponse<>(false, "riderId required", null, 400);
        }

        Rider rider = riderRepository.findById(req.getRiderId()).orElse(null);
        if (rider == null) {
            return new ApiResponse<>(false, "Rider not found", null, 404);
        }

        HandoverSession session = new HandoverSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setRiderId(rider.getId());
        session.setWarehouseId(warehouseId);
        session.setOtp(notificationService.generateOtp());
        session.setStatus(Status.PENDING_OTP);

        saveSession(session);

        if (rider.getPhone() != null && !rider.getPhone().isBlank()) {
            notificationService.sendOtpSms(rider.getPhone(), session.getOtp(),
                    "warehouse handover at " + warehouseId
                            + ". Show this OTP to the admin to hand over all your orders");
        } else {
            log.warn("Rider {} has no phone — handover OTP not sent", rider.getId());
        }

        log.info("Handover session {} started for rider {} at warehouse {}",
                session.getSessionId(), rider.getId(), warehouseId);

        return new ApiResponse<>(true, "OTP sent to rider",
                StartHandoverSessionResponse.builder()
                        .sessionId(session.getSessionId())
                        .riderName(rider.getName())
                        .riderPhoneMasked(maskPhone(rider.getPhone()))
                        .message("OTP sent to rider's phone. Ask rider for OTP to verify identity.")
                        .build(),
                200);
    }

    // ── 2. Verify rider OTP → session becomes ACTIVE ─────────────────────

    public ApiResponse<Object> verifyRiderOtp(UUID warehouseId, String sessionId, VerifyHandoverOtpRequest req) {
        HandoverSession session = getSession(sessionId);
        if (session == null) {
            return new ApiResponse<>(false, "Session not found or expired", null, 404);
        }
        if (!session.getWarehouseId().equals(warehouseId)) {
            return new ApiResponse<>(false, "Session does not belong to this warehouse", null, 403);
        }
        if (session.getStatus() == Status.COMPLETED) {
            return new ApiResponse<>(false, "Session already completed", null, 409);
        }
        if (session.getStatus() == Status.ACTIVE) {
            return new ApiResponse<>(false, "Rider already verified for this session", null, 409);
        }
        if (!session.getOtp().equals(req.getOtp())) {
            return new ApiResponse<>(false, "Invalid OTP", null, 400);
        }

        session.setStatus(Status.ACTIVE);
        saveSession(session);

        UUID riderId = session.getRiderId();
        Rider rider = riderId != null ? riderRepository.findById(riderId).orElse(null) : null;
        log.info("Handover session {} — rider {} verified", sessionId, session.getRiderId());

        return new ApiResponse<>(true, "Rider verified — start scanning orders",
                HandoverSessionStatus.builder()
                        .sessionId(sessionId)
                        .riderId(session.getRiderId())
                        .riderName(rider != null ? rider.getName() : null)
                        .status(session.getStatus().name())
                        .totalScanned(0)
                        .scannedOrders(List.of())
                        .build(),
                200);
    }

    // ── 3. Scan an order barcode ──────────────────────────────────────────

    @Transactional
    public ApiResponse<Object> scanOrder(UUID warehouseId, String sessionId, ScanOrderRequest req) {
        HandoverSession session = getSession(sessionId);
        if (session == null) {
            return new ApiResponse<>(false, "Session not found or expired", null, 404);
        }
        if (!session.getWarehouseId().equals(warehouseId)) {
            return new ApiResponse<>(false, "Session does not belong to this warehouse", null, 403);
        }
        if (session.getStatus() != Status.ACTIVE) {
            return new ApiResponse<>(false,
                    "Session not active — verify rider OTP first (status: " + session.getStatus() + ")",
                    null, 409);
        }

        String orderNo = req.getOrderNo().trim().toUpperCase();

        if (session.getScannedOrderNos().contains(orderNo)) {
            return buildScanResponse(session, orderNo, false, "Already scanned in this session", warehouseId);
        }

        Order order = orderRepository.findByOrderNo(orderNo).orElse(null);
        if (order == null) {
            return buildScanResponse(session, orderNo, false, "Order not found", warehouseId);
        }

        // Security: order must belong to this rider
        if (!session.getRiderId().equals(order.getRiderId())) {
            return buildScanResponse(session, orderNo, false,
                    "Order does not belong to the rider in this session", warehouseId);
        }

        if (!OrderStatus.PICKED.equals(order.getStatus())) {
            return buildScanResponse(session, orderNo, false,
                    "Order status '" + order.getStatus() + "' cannot be received", warehouseId);
        }

        // If a parcel exists for this order, enforce seller OTP verification
        // and immediately advance it to AT_WAREHOUSE
        Parcel parcel = parcelRepository.findByOrderId(order.getId()).orElse(null);
        if (parcel != null) {
            if (!parcel.isSellerPickupOtpVerified()) {
                return buildScanResponse(session, orderNo, false,
                        "Seller pickup OTP not verified — rider must collect the seller OTP before handover",
                        warehouseId);
            }
            parcel.setStatus(ParcelStatus.AT_WAREHOUSE);
            parcel.setWarehouseInOtpVerified(true);
            parcel.setCurrentWarehouseId(warehouseId);
            parcel.setArrivedAtWarehouseAt(ZonedDateTime.now(ZoneId.of("Asia/Kolkata")));
            parcelRepository.save(parcel);
            log.info("Handover session {} — parcel {} → AT_WAREHOUSE", sessionId, parcel.getId());
        }

        session.getScannedOrderNos().add(orderNo);

        saveSession(session);

        log.info("Handover session {} — scanned order {} ({} total)",
                sessionId, orderNo, session.getScannedOrderNos().size());

        return buildScanResponse(session, orderNo, true, null, warehouseId);
    }

    // ── 4. Remove a mistakenly scanned order ─────────────────────────────

    @Transactional
    public ApiResponse<Object> removeOrder(UUID warehouseId, String sessionId, String orderNo) {
        HandoverSession session = getSession(sessionId);
        if (session == null) {
            return new ApiResponse<>(false, "Session not found or expired", null, 404);
        }
        if (session.getStatus() != Status.ACTIVE) {
            return new ApiResponse<>(false, "Session not active", null, 409);
        }

        String normalised = orderNo.trim().toUpperCase();
        boolean removed = session.getScannedOrderNos().remove(normalised);
        if (!removed) {
            return new ApiResponse<>(false, "Order not in scanned list", null, 404);
        }

        // Roll back the parcel that was advanced to AT_WAREHOUSE during scan
        orderRepository.findByOrderNo(normalised).ifPresent(order -> {
            parcelRepository.findByOrderId(order.getId()).ifPresent(parcel -> {
                if (parcel.getStatus() == ParcelStatus.AT_WAREHOUSE
                        && warehouseId.equals(parcel.getCurrentWarehouseId())) {
                    parcel.setStatus(ParcelStatus.PICKED_BY_RIDER);
                    parcel.setWarehouseInOtpVerified(false);
                    parcel.setArrivedAtWarehouseAt(null);
                    parcelRepository.save(parcel);
                    log.info("Handover session {} — parcel for {} rolled back to PICKED_BY_RIDER", sessionId,
                            normalised);
                }
            });
        });

        saveSession(session);
        return buildScanResponse(session, normalised, true, null, warehouseId);
    }

    // ── 5. Get session state ──────────────────────────────────────────────

    public ApiResponse<Object> getSessionStatus(UUID warehouseId, String sessionId) {
        HandoverSession session = getSession(sessionId);
        if (session == null) {
            return new ApiResponse<>(false, "Session not found or expired", null, 404);
        }
        if (!session.getWarehouseId().equals(warehouseId)) {
            return new ApiResponse<>(false, "Session does not belong to this warehouse", null, 403);
        }

        UUID riderId = session.getRiderId();
        Rider rider = riderId != null ? riderRepository.findById(riderId).orElse(null) : null;

        return new ApiResponse<>(true, "OK",
                HandoverSessionStatus.builder()
                        .sessionId(sessionId)
                        .riderId(session.getRiderId())
                        .riderName(rider != null ? rider.getName() : null)
                        .status(session.getStatus().name())
                        .totalScanned(session.getScannedOrderNos().size())
                        .scannedOrders(resolveScannedItems(session.getScannedOrderNos()))
                        .build(),
                200);
    }

    // ── 6. Confirm batch — commit all scanned orders at once ─────────────

    @Transactional
    public ApiResponse<Object> confirmHandover(UUID warehouseId, String sessionId) {
        HandoverSession session = getSession(sessionId);
        if (session == null) {
            return new ApiResponse<>(false, "Session not found or expired", null, 404);
        }
        if (!session.getWarehouseId().equals(warehouseId)) {
            return new ApiResponse<>(false, "Session does not belong to this warehouse", null, 403);
        }
        if (session.getStatus() != Status.ACTIVE) {
            return new ApiResponse<>(false, "Session not active", null, 409);
        }
        if (session.getScannedOrderNos().isEmpty()) {
            return new ApiResponse<>(false, "No orders scanned — scan at least one order before confirming", null, 400);
        }

        List<String> accepted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (String orderNo : session.getScannedOrderNos()) {
            try {
                Order order = orderRepository.findByOrderNo(orderNo).orElse(null);
                if (order == null) {
                    skipped.add(orderNo);
                    errors.add(orderNo + ": not found");
                    continue;
                }
                if (!RECEIVABLE_STATES.contains(order.getStatus())) {
                    skipped.add(orderNo);
                    errors.add(orderNo + ": status " + order.getStatus() + " cannot be received");
                    continue;
                }

                order.setStatus(OrderStatus.WAREHOUSE);
                order.setWareHouseId(warehouseId);
                orderRepository.save(order);
                accepted.add(orderNo);

            } catch (Exception e) {
                skipped.add(orderNo);
                errors.add(orderNo + ": " + e.getMessage());
                log.warn("Handover confirm: failed for order {} — {}", orderNo, e.getMessage());
            }
        }

        session.setStatus(Status.COMPLETED);
        saveSession(session);

        log.info("Handover session {} confirmed at warehouse {}: {} accepted, {} skipped",
                sessionId, warehouseId, accepted.size(), skipped.size());

        return new ApiResponse<>(true,
                accepted.size() + " orders received at warehouse",
                ConfirmHandoverResponse.builder()
                        .accepted(accepted.size())
                        .skipped(skipped.size())
                        .acceptedOrderNos(accepted)
                        .skippedOrderNos(skipped)
                        .errors(errors)
                        .build(),
                200);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private ApiResponse<Object> buildScanResponse(HandoverSession session, String orderNo,
            boolean accepted, String reason, UUID warehouseId) {
        return new ApiResponse<>(true, accepted ? "Order scanned" : reason,
                ScanOrderResponse.builder()
                        .orderNo(orderNo)
                        .accepted(accepted)
                        .reason(reason)
                        .totalScanned(session.getScannedOrderNos().size())
                        .scannedOrders(resolveScannedItems(session.getScannedOrderNos()))
                        .build(),
                200);
    }

    private List<ScannedOrderItem> resolveScannedItems(List<String> orderNos) {
        if (orderNos.isEmpty())
            return List.of();
        return orderNos.stream()
                .map(no -> orderRepository.findByOrderNo(no).orElse(null))
                .filter(Objects::nonNull)
                .map(o -> ScannedOrderItem.builder()
                        .orderNo(o.getOrderNo())
                        .orderId(o.getId())
                        .destCity(o.getDestCity())
                        .weightKg(o.getWeightKg())
                        .build())
                .collect(Collectors.toList());
    }

    private void saveSession(HandoverSession session) {
        try {
            String key = SESSION_KEY_PREFIX + session.getSessionId();
            Object json = objectMapper.writeValueAsString(session);
            etaRedisTemplate.opsForValue().set(key, json, SESSION_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save handover session to Redis", e);
        }
    }

    private HandoverSession getSession(String sessionId) {
        try {
            Object raw = etaRedisTemplate.opsForValue().get(SESSION_KEY_PREFIX + sessionId);
            if (raw == null)
                return null;
            return objectMapper.readValue(raw.toString(), HandoverSession.class);
        } catch (Exception e) {
            log.error("Failed to deserialise handover session {}", sessionId, e);
            return null;
        }
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4)
            return null;
        int n = phone.length();
        return phone.substring(0, Math.min(3, n - 4))
                + "*".repeat(Math.max(0, n - 7))
                + phone.substring(n - 4);
    }
}
