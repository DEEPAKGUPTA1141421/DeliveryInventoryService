package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.WarehouseDTO.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order.OrderStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel.ParcelStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Rider;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.OrderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ParcelRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RiderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * OrderReceiveService
 * ─────────────────────
 * Secure "rider hands a parcel to a warehouse" flow, keyed by orderNo.
 *
 * Two paths, chosen automatically based on whether a Parcel exists:
 *
 *   A) PARCEL EXISTS → delegates to {@link ParcelService}'s warehouse-in OTP
 *      flow (state guard PICKED_BY_RIDER, OTP stored on parcel + Redis,
 *      OtpLog entries written).
 *
 *   B) NO PARCEL YET → first-mile pickup. Rider is carrying loose orders that
 *      a downstream cron will batch into Parcels. We still want a secure
 *      handover, so:
 *        • OTP is keyed on the orderId in Redis  (otp:order:{id}:WAREHOUSE_IN)
 *        • OTP is SMS'd to {@link Order#getRiderId()}'s phone
 *        • Verify mutates only the Order (status=WAREHOUSE, wareHouseId)
 *      The cron job picks these WAREHOUSE-status orders up later and creates
 *      Parcels for them.
 *
 * Security model (both paths):
 *   • Rider lock: a rider must be linked to the order (parcel.pickupRiderId
 *     in path A, order.riderId in path B). If empty, receive is refused.
 *   • State guard: parcel must be PICKED_BY_RIDER (path A); order must not
 *     already be in WAREHOUSE/IN_TRANSIT/DELIVERED/CANCELLED (path B).
 *   • OTP second factor: the SMS'd code is the only thing that lets the
 *     warehouse admin commit the handover — admin alone cannot fake it.
 *   • Idempotency: rerunning lookup on an already-received order returns
 *     alreadyReceived=true with no error.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderReceiveService {

    private final OrderRepository orderRepository;
    private final ParcelRepository parcelRepository;
    private final RiderRepository riderRepository;
    private final WarehouseRepository warehouseRepository;
    private final ParcelService parcelService;
    private final NotificationService notificationService;
    private final RedisTemplate<String, Object> etaRedisTemplate;

    private static final long OTP_TTL_MINUTES = 10;
    private static final String OTP_KEY_PREFIX = "otp:order:";
    private static final String OTP_KEY_SUFFIX = ":WAREHOUSE_IN";

    /** Order can still legitimately be received (i.e. it isn't past warehouse-in already). */
    private static final Set<OrderStatus> RECEIVABLE_ORDER_STATES = EnumSet.of(
            OrderStatus.CREATED,
            OrderStatus.PICKUP_SCHEDULED,
            OrderStatus.PICKED,
            OrderStatus.ASSIGNED,
            OrderStatus.PENDING
    );

    // ─────────────────────────────────────────────────────────────────────
    // 1. Lookup by orderNo
    // ─────────────────────────────────────────────────────────────────────

    public ApiResponse<Object> lookup(UUID warehouseId, String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return new ApiResponse<>(false, "orderNo required", null, 400);
        }
        if (warehouseId == null || warehouseRepository.findById(warehouseId).isEmpty()) {
            return new ApiResponse<>(false, "Warehouse not found", null, 404);
        }

        Order order = orderRepository.findByOrderNo(orderNo.trim().toUpperCase()).orElse(null);
        if (order == null) {
            return new ApiResponse<>(false, "Order " + orderNo + " not found", null, 404);
        }

        Parcel parcel = parcelRepository.findByOrderId(order.getId()).orElse(null);

        ReceiveOrderLookup.ReceiveOrderLookupBuilder b = ReceiveOrderLookup.builder()
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .orderStatus(order.getStatus())
                .weightKg(order.getWeightKg())
                .originCity(order.getOriginCity())
                .destCity(order.getDestCity());

        if (parcel != null) {
            b.parcelId(parcel.getId())
             .parcelStatus(parcel.getStatus())
             .currentWarehouseId(parcel.getCurrentWarehouseId())
             .destinationWarehouseId(parcel.getDestinationWarehouseId());
        }

        // Resolve the rider we'll SMS — parcel-bound rider takes priority,
        // else the order-level rider (path B).
        UUID riderId = parcel != null && parcel.getPickupRiderId() != null
                ? parcel.getPickupRiderId()
                : order.getRiderId();

        if (riderId != null) {
            Rider rider = riderRepository.findById(riderId).orElse(null);
            if (rider != null) {
                b.riderId(rider.getId())
                 .riderName(rider.getName())
                 .riderPhoneMasked(maskPhone(rider.getPhone()));
            }
        }

        // ── Idempotent: already received here ────────────────────────────
        if (order.getStatus() == OrderStatus.WAREHOUSE
                && warehouseId.equals(order.getWareHouseId())) {
            return new ApiResponse<>(true, "Already received at this warehouse",
                    b.canReceive(false).alreadyReceived(true)
                     .reason("Order already accepted here").build(),
                    200);
        }
        if (parcel != null && parcel.getStatus() == ParcelStatus.AT_WAREHOUSE
                && warehouseId.equals(parcel.getCurrentWarehouseId())) {
            return new ApiResponse<>(true, "Already received at this warehouse",
                    b.canReceive(false).alreadyReceived(true)
                     .reason("Parcel already accepted here").build(),
                    200);
        }

        // ── Path A: parcel exists → use parcel state machine ─────────────
        if (parcel != null) {
            if (parcel.getStatus() != ParcelStatus.PICKED_BY_RIDER) {
                return new ApiResponse<>(true, "OK",
                        b.canReceive(false)
                         .reason("Parcel status is " + parcel.getStatus()
                                 + " — not in rider's hands")
                         .build(),
                        200);
            }
            if (parcel.getPickupRiderId() == null) {
                return new ApiResponse<>(true, "OK",
                        b.canReceive(false)
                         .reason("No pickup rider assigned to this parcel")
                         .build(),
                        200);
            }
            return new ApiResponse<>(true, "OK", b.canReceive(true).build(), 200);
        }

        // ── Path B: no parcel yet (first-mile loose order) ───────────────
        if (order.getRiderId() == null) {
            return new ApiResponse<>(true, "OK",
                    b.canReceive(false)
                     .reason("No rider assigned to this order yet")
                     .build(),
                    200);
        }
        if (!RECEIVABLE_ORDER_STATES.contains(order.getStatus())) {
            return new ApiResponse<>(true, "OK",
                    b.canReceive(false)
                     .reason("Order status is " + order.getStatus() + " — cannot receive")
                     .build(),
                    200);
        }
        return new ApiResponse<>(true, "OK", b.canReceive(true).build(), 200);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. Initiate warehouse-in (sends OTP to rider's phone)
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public ApiResponse<Object> initiate(UUID warehouseId, String orderNo) {
        ResolveResult r = resolve(warehouseId, orderNo);
        if (r.error != null) return r.error;

        if (r.parcel != null) {
            if (r.parcel.getStatus() != ParcelStatus.PICKED_BY_RIDER) {
                return new ApiResponse<>(false,
                        "Parcel not in PICKED_BY_RIDER state (current: " + r.parcel.getStatus() + ")",
                        null, 409);
            }
            return parcelService.initiateWarehouseIn(r.parcel.getId());
        }

        // Path B: no-parcel rider handover
        if (r.order.getRiderId() == null) {
            return new ApiResponse<>(false, "No rider assigned to this order", null, 409);
        }
        if (!RECEIVABLE_ORDER_STATES.contains(r.order.getStatus())) {
            return new ApiResponse<>(false,
                    "Order status " + r.order.getStatus() + " cannot be received", null, 409);
        }

        Rider rider = riderRepository.findById(r.order.getRiderId()).orElse(null);
        if (rider == null) {
            return new ApiResponse<>(false, "Rider not found for this order", null, 404);
        }

        String otp = notificationService.generateOtp();
        storeOrderOtp(r.order.getId(), otp);

        if (rider.getPhone() != null && !rider.getPhone().isBlank()) {
            notificationService.sendOtpSms(rider.getPhone(), otp,
                    "warehouse handover for order " + r.order.getOrderNo()
                            + ". Show this OTP to warehouse admin");
        } else {
            log.warn("Rider {} has no phone — OTP {} not SMS'd for order {}",
                    rider.getId(), otp, r.order.getOrderNo());
        }

        log.info("Warehouse-in OTP issued for order {} (rider {})",
                r.order.getOrderNo(), rider.getId());
        return new ApiResponse<>(true, "OTP sent to rider", null, 200);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. Verify OTP (rider hands OTP to admin → order accepted)
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public ApiResponse<Object> verify(UUID warehouseId, VerifyReceiveRequest req) {
        if (req == null || req.getOrderNo() == null || req.getOtp() == null) {
            return new ApiResponse<>(false, "orderNo and otp required", null, 400);
        }
        ResolveResult r = resolve(warehouseId, req.getOrderNo());
        if (r.error != null) return r.error;

        // ── Path A: parcel exists — delegate to ParcelService ────────────
        if (r.parcel != null) {
            var verifyReq = new com.DeliveryInventoryService.DeliveryInventoryService
                    .DTO.WarehouseDTO.VerifyOtpRequest();
            verifyReq.setParcelId(r.parcel.getId());
            verifyReq.setOtp(req.getOtp());
            verifyReq.setPerformedBy(req.getPerformedBy());

            ApiResponse<Object> resp = parcelService.verifyWarehouseInOtp(verifyReq);
            if (!resp.success()) return resp;

            Parcel fresh = parcelRepository.findById(r.parcel.getId()).orElse(r.parcel);
            fresh.setCurrentWarehouseId(warehouseId);
            parcelRepository.save(fresh);

            r.order.setStatus(OrderStatus.WAREHOUSE);
            r.order.setWareHouseId(warehouseId);
            orderRepository.save(r.order);

            log.info("Order {} (parcel {}) accepted at warehouse {}",
                    r.order.getOrderNo(), fresh.getId(), warehouseId);
            return new ApiResponse<>(true,
                    "Order " + r.order.getOrderNo() + " received at warehouse",
                    parcelService.toResponse(fresh), 200);
        }

        // ── Path B: no parcel — verify Redis OTP, update Order only ──────
        if (!verifyOrderOtp(r.order.getId(), req.getOtp())) {
            return new ApiResponse<>(false, "Invalid or expired OTP", null, 400);
        }
        clearOrderOtp(r.order.getId());

        r.order.setStatus(OrderStatus.WAREHOUSE);
        r.order.setWareHouseId(warehouseId);
        orderRepository.save(r.order);

        log.info("Order {} accepted at warehouse {} (no parcel yet — cron will batch)",
                r.order.getOrderNo(), warehouseId);
        return new ApiResponse<>(true,
                "Order " + r.order.getOrderNo() + " received — parcel will be created by batch job",
                null, 200);
    }

    // ─────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────

    private record ResolveResult(ApiResponse<Object> error, Order order, Parcel parcel) {}

    private ResolveResult resolve(UUID warehouseId, String orderNo) {
        if (warehouseId == null) {
            return new ResolveResult(new ApiResponse<>(false, "warehouseId required", null, 400), null, null);
        }
        if (orderNo == null || orderNo.isBlank()) {
            return new ResolveResult(new ApiResponse<>(false, "orderNo required", null, 400), null, null);
        }
        if (warehouseRepository.findById(warehouseId).isEmpty()) {
            return new ResolveResult(new ApiResponse<>(false, "Warehouse not found", null, 404), null, null);
        }
        Order order = orderRepository.findByOrderNo(orderNo.trim().toUpperCase()).orElse(null);
        if (order == null) {
            return new ResolveResult(
                    new ApiResponse<>(false, "Order " + orderNo + " not found", null, 404), null, null);
        }
        Parcel parcel = parcelRepository.findByOrderId(order.getId()).orElse(null);
        return new ResolveResult(null, order, parcel);
    }

    private void storeOrderOtp(UUID orderId, String otp) {
        etaRedisTemplate.opsForValue().set(otpKey(orderId), otp, OTP_TTL_MINUTES, TimeUnit.MINUTES);
    }

    private boolean verifyOrderOtp(UUID orderId, String submitted) {
        Object cached = etaRedisTemplate.opsForValue().get(otpKey(orderId));
        return cached != null && cached.toString().equals(submitted);
    }

    private void clearOrderOtp(UUID orderId) {
        etaRedisTemplate.delete(otpKey(orderId));
    }

    private static String otpKey(UUID orderId) {
        return OTP_KEY_PREFIX + orderId + OTP_KEY_SUFFIX;
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return null;
        int n = phone.length();
        return phone.substring(0, Math.min(3, n - 4))
                + "*".repeat(Math.max(0, n - 7))
                + phone.substring(n - 4);
    }

}
