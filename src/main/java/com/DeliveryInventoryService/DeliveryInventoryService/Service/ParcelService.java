package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.WarehouseDTO.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel.ParcelStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ParcelService — all operations on Parcel entities.
 *
 * OTP flow per stage:
 *
 * 1. SELLER PICKUP
 * assignPickupRider() → generates sellerPickupOtp, SMS to seller, SMS to rider
 * verifySellerOtp() → rider enters OTP seller gave → status PICKED_BY_RIDER
 *
 * 2. WAREHOUSE IN
 * (rider arrives at warehouse, admin sees parcel list)
 * initiateWarehouseIn() → generates warehouseInOtp, SMS to rider
 * verifyWarehouseInOtp()→ admin enters OTP rider gives → status AT_WAREHOUSE
 *
 * 3. WAREHOUSE OUT (delivery dispatch)
 * assignDeliveryRider() → generates warehouseOutOtp, SMS to rider, SMS to
 * customer
 * verifyWarehouseOutOtp()→ rider enters OTP admin gives → status
 * OUT_FOR_DELIVERY
 *
 * 4. CUSTOMER DELIVERY
 * (rider at customer door)
 * verifyCustomerOtp() → rider enters OTP customer gives → status DELIVERED
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParcelService {

    private final ParcelRepository parcelRepository;
    private final OrderRepository orderRepository;
    private final RiderRepository riderRepository;
    private final WarehouseRepository warehouseRepository;
    private final OtpLogRepository otpLogRepository;
    private final NotificationService notificationService;
    private final RedisTemplate<String, Object> etaRedisTemplate;

    // OTP Redis TTL: 10 minutes
    private static final long OTP_TTL_MINUTES = 10;
    private static final String OTP_KEY_PREFIX = "otp:parcel:";

    // ─────────────────────────────────────────────────────────────────────
    // 1. CREATE PARCEL
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public ApiResponse<Object> createParcel(CreateParcelRequest req) {
        Order order = orderRepository.findById(req.getOrderId()).orElse(null);
        if (order == null) {
            return new ApiResponse<>(false, "Order not found", null, 404);
        }

        // Idempotency: one parcel per order
        if (parcelRepository.findByOrderId(req.getOrderId()).isPresent()) {
            return new ApiResponse<>(false, "Parcel already exists for this order", null, 409);
        }

        Parcel parcel = new Parcel();
        parcel.setOrderId(req.getOrderId());
        parcel.setWeightKg(req.getWeightKg() > 0 ? req.getWeightKg() : order.getWeightKg());
        parcel.setDimensions(req.getDimensions());
        parcel.setDescription(req.getDescription());
        parcel.setOriginWarehouseId(req.getOriginWarehouseId());
        parcel.setCurrentWarehouseId(req.getOriginWarehouseId());
        parcel.setDestinationWarehouseId(req.getDestinationWarehouseId());
        parcel.setStatus(ParcelStatus.CREATED);

        parcel = parcelRepository.save(parcel);
        log.info("Parcel {} created for order {}", parcel.getId(), req.getOrderId());

        // Cache parcel status in Redis for quick lookup
        cacheParcelStatus(parcel);

        return new ApiResponse<>(true, "Parcel created", toResponse(parcel), 201);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2. ASSIGN PICKUP RIDER (SELLER → WAREHOUSE)
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public ApiResponse<Object> assignPickupRider(AssignPickupRiderRequest req) {
        Parcel parcel = parcelRepository.findById(req.getParcelId()).orElse(null);
        if (parcel == null)
            return new ApiResponse<>(false, "Parcel not found", null, 404);

        Rider rider = riderRepository.findById(req.getRiderId()).orElse(null);
        if (rider == null)
            return new ApiResponse<>(false, "Rider not found", null, 404);

        if (parcel.getStatus() != ParcelStatus.CREATED) {
            return new ApiResponse<>(false, "Parcel not in CREATED state", null, 400);
        }

        String otp = notificationService.generateOtp();
        parcel.setPickupRiderId(req.getRiderId());
        parcel.setSellerPickupOtp(otp);
        parcel.setStatus(ParcelStatus.AWAITING_PICKUP);
        parcelRepository.save(parcel);

        // Cache OTP in Redis with 10-min TTL
        storeOtpInRedis(parcel.getId(), OtpLog.OtpType.SELLER_PICKUP, otp);
        cacheParcelStatus(parcel);

        // Get order/seller phone
        Order order = orderRepository.findById(parcel.getOrderId()).orElse(null);
        String sellerPhone = order != null ? resolveSellerPhone(order) : null;

        // SMS seller: OTP they will give to the rider
        if (sellerPhone != null) {
            notificationService.sendOtpSms(sellerPhone, otp,
                    "parcel pickup by rider. Share this OTP with the rider");
        }

        // SMS rider: which parcel to pick
        if (rider.getPhone() != null) {
            notificationService.sendSms(rider.getPhone(),
                    String.format("[DeliveryCo] Pickup assigned: Parcel %s from %s. Collect OTP from seller.",
                            parcel.getId(), order != null ? order.getOriginAddress() : ""));
        }

        logOtp(parcel.getId(), OtpLog.OtpType.SELLER_PICKUP, OtpLog.OtpEvent.SENT, sellerPhone, null);
        log.info("Pickup OTP {} sent for parcel {} → rider {}", otp, parcel.getId(), req.getRiderId());

        return new ApiResponse<>(true, "Rider assigned, OTP sent to seller", toResponse(parcel), 200);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3. VERIFY SELLER OTP (rider picks from seller)
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public ApiResponse<Object> verifySellerOtp(VerifyOtpRequest req) {
        Parcel parcel = parcelRepository.findById(req.getParcelId()).orElse(null);
        if (parcel == null)
            return new ApiResponse<>(false, "Parcel not found", null, 404);

        if (parcel.getStatus() != ParcelStatus.AWAITING_PICKUP) {
            return new ApiResponse<>(false, "Parcel not awaiting pickup", null, 400);
        }

        if (!verifyOtp(parcel.getId(), OtpLog.OtpType.SELLER_PICKUP, req.getOtp(), parcel.getSellerPickupOtp())) {
            logOtp(parcel.getId(), OtpLog.OtpType.SELLER_PICKUP, OtpLog.OtpEvent.FAILED, null, req.getPerformedBy());
            return new ApiResponse<>(false, "Invalid OTP", null, 400);
        }

        parcel.setSellerPickupOtpVerified(true);
        parcel.setStatus(ParcelStatus.PICKED_BY_RIDER);
        parcel.setPickedAt(ZonedDateTime.now(ZoneId.of("Asia/Kolkata")));
        parcelRepository.save(parcel);
        cacheParcelStatus(parcel);

        logOtp(parcel.getId(), OtpLog.OtpType.SELLER_PICKUP, OtpLog.OtpEvent.VERIFIED, null, req.getPerformedBy());
        log.info("Parcel {} picked by rider — seller OTP verified", parcel.getId());

        return new ApiResponse<>(true, "Seller OTP verified — parcel picked by rider", toResponse(parcel), 200);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 4. INITIATE WAREHOUSE-IN (rider arrives at warehouse)
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public ApiResponse<Object> initiateWarehouseIn(UUID parcelId) {
        Parcel parcel = parcelRepository.findById(parcelId).orElse(null);
        if (parcel == null)
            return new ApiResponse<>(false, "Parcel not found", null, 404);

        if (parcel.getStatus() != ParcelStatus.PICKED_BY_RIDER) {
            return new ApiResponse<>(false, "Parcel not in PICKED_BY_RIDER state", null, 400);
        }

        String otp = notificationService.generateOtp();
        parcel.setWarehouseInOtp(otp);
        parcelRepository.save(parcel);

        storeOtpInRedis(parcel.getId(), OtpLog.OtpType.WAREHOUSE_IN, otp);

        // SMS rider: give this OTP to warehouse admin
        Rider rider = riderRepository.findById(parcel.getPickupRiderId()).orElse(null);
        if (rider != null && rider.getPhone() != null) {
            notificationService.sendOtpSms(rider.getPhone(), otp,
                    "warehouse handover. Show this OTP to warehouse admin");
        }

        logOtp(parcel.getId(), OtpLog.OtpType.WAREHOUSE_IN, OtpLog.OtpEvent.SENT,
                rider != null ? rider.getPhone() : null, null);

        return new ApiResponse<>(true, "Warehouse-in OTP sent to rider", toResponse(parcel), 200);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 5. VERIFY WAREHOUSE-IN OTP (admin confirms rider handover)
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public ApiResponse<Object> verifyWarehouseInOtp(VerifyOtpRequest req) {
        Parcel parcel = parcelRepository.findById(req.getParcelId()).orElse(null);
        if (parcel == null)
            return new ApiResponse<>(false, "Parcel not found", null, 404);

        if (parcel.getStatus() != ParcelStatus.PICKED_BY_RIDER) {
            return new ApiResponse<>(false, "Parcel not in transit to warehouse", null, 400);
        }

        if (!verifyOtp(parcel.getId(), OtpLog.OtpType.WAREHOUSE_IN, req.getOtp(), parcel.getWarehouseInOtp())) {
            logOtp(parcel.getId(), OtpLog.OtpType.WAREHOUSE_IN, OtpLog.OtpEvent.FAILED, null, req.getPerformedBy());
            return new ApiResponse<>(false, "Invalid OTP", null, 400);
        }

        parcel.setWarehouseInOtpVerified(true);
        parcel.setStatus(ParcelStatus.AT_WAREHOUSE);
        parcel.setArrivedAtWarehouseAt(ZonedDateTime.now(ZoneId.of("Asia/Kolkata")));
        parcelRepository.save(parcel);
        cacheParcelStatus(parcel);

        logOtp(parcel.getId(), OtpLog.OtpType.WAREHOUSE_IN, OtpLog.OtpEvent.VERIFIED, null, req.getPerformedBy());
        log.info("Parcel {} accepted at warehouse", parcel.getId());

        return new ApiResponse<>(true, "Parcel accepted at warehouse", toResponse(parcel), 200);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 6. ASSIGN DELIVERY RIDER (warehouse → customer)
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public ApiResponse<Object> assignDeliveryRider(AssignPickupRiderRequest req) {
        Parcel parcel = parcelRepository.findById(req.getParcelId()).orElse(null);
        if (parcel == null)
            return new ApiResponse<>(false, "Parcel not found", null, 404);

        Rider rider = riderRepository.findById(req.getRiderId()).orElse(null);
        if (rider == null)
            return new ApiResponse<>(false, "Rider not found", null, 404);

        if (parcel.getStatus() != ParcelStatus.AT_DEST_WAREHOUSE
                && parcel.getStatus() != ParcelStatus.AT_WAREHOUSE) {
            return new ApiResponse<>(false, "Parcel not ready for last-mile dispatch", null, 400);
        }

        String warehouseOutOtp = notificationService.generateOtp();
        String customerOtp = notificationService.generateOtp();

        parcel.setDeliveryRiderId(req.getRiderId());
        parcel.setWarehouseOutOtp(warehouseOutOtp);
        parcel.setCustomerDeliveryOtp(customerOtp);
        parcelRepository.save(parcel);

        storeOtpInRedis(parcel.getId(), OtpLog.OtpType.WAREHOUSE_OUT, warehouseOutOtp);
        storeOtpInRedis(parcel.getId(), OtpLog.OtpType.CUSTOMER_DELIVERY, customerOtp);
        cacheParcelStatus(parcel);

        // SMS rider: warehouse-out OTP (they give to admin to pick parcel)
        if (rider.getPhone() != null) {
            notificationService.sendOtpSms(rider.getPhone(), warehouseOutOtp,
                    "collecting parcel from warehouse. Show this OTP to admin");
        }

        // SMS customer: delivery OTP (they will give to rider)
        Order order = orderRepository.findById(parcel.getOrderId()).orElse(null);
        String customerPhone = order != null ? resolveCustomerPhone(order) : null;
        if (customerPhone != null) {
            notificationService.sendOtpSms(customerPhone, customerOtp,
                    "delivery. Share this OTP with the delivery rider");
            // Also place a call for accessibility
            notificationService.sendOtpCall(customerPhone, customerOtp);
        }

        logOtp(parcel.getId(), OtpLog.OtpType.WAREHOUSE_OUT, OtpLog.OtpEvent.SENT,
                rider.getPhone(), null);
        logOtp(parcel.getId(), OtpLog.OtpType.CUSTOMER_DELIVERY, OtpLog.OtpEvent.SENT,
                customerPhone, null);

        return new ApiResponse<>(true, "Delivery rider assigned, OTPs sent", toResponse(parcel), 200);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 7. VERIFY WAREHOUSE-OUT OTP (rider collects from warehouse)
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public ApiResponse<Object> verifyWarehouseOutOtp(VerifyOtpRequest req) {
        Parcel parcel = parcelRepository.findById(req.getParcelId()).orElse(null);
        if (parcel == null)
            return new ApiResponse<>(false, "Parcel not found", null, 404);

        if (!verifyOtp(parcel.getId(), OtpLog.OtpType.WAREHOUSE_OUT, req.getOtp(), parcel.getWarehouseOutOtp())) {
            logOtp(parcel.getId(), OtpLog.OtpType.WAREHOUSE_OUT, OtpLog.OtpEvent.FAILED, null, req.getPerformedBy());
            return new ApiResponse<>(false, "Invalid OTP", null, 400);
        }

        parcel.setWarehouseOutOtpVerified(true);
        parcel.setStatus(ParcelStatus.OUT_FOR_DELIVERY);
        parcelRepository.save(parcel);
        cacheParcelStatus(parcel);

        logOtp(parcel.getId(), OtpLog.OtpType.WAREHOUSE_OUT, OtpLog.OtpEvent.VERIFIED, null, req.getPerformedBy());
        log.info("Parcel {} out for delivery — rider collected from warehouse", parcel.getId());

        return new ApiResponse<>(true, "Parcel out for delivery", toResponse(parcel), 200);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 8. VERIFY CUSTOMER DELIVERY OTP
    // ─────────────────────────────────────────────────────────────────────

    @Transactional
    public ApiResponse<Object> verifyCustomerDeliveryOtp(VerifyOtpRequest req) {
        Parcel parcel = parcelRepository.findById(req.getParcelId()).orElse(null);
        if (parcel == null)
            return new ApiResponse<>(false, "Parcel not found", null, 404);

        if (parcel.getStatus() != ParcelStatus.OUT_FOR_DELIVERY) {
            return new ApiResponse<>(false, "Parcel not out for delivery", null, 400);
        }

        if (!verifyOtp(parcel.getId(), OtpLog.OtpType.CUSTOMER_DELIVERY, req.getOtp(),
                parcel.getCustomerDeliveryOtp())) {
            logOtp(parcel.getId(), OtpLog.OtpType.CUSTOMER_DELIVERY, OtpLog.OtpEvent.FAILED, null,
                    req.getPerformedBy());
            return new ApiResponse<>(false, "Invalid OTP", null, 400);
        }

        parcel.setCustomerDeliveryOtpVerified(true);
        parcel.setStatus(ParcelStatus.DELIVERED);
        parcel.setDeliveredAt(ZonedDateTime.now(ZoneId.of("Asia/Kolkata")));

        // Also update the linked Order
        Order order = orderRepository.findById(parcel.getOrderId()).orElse(null);
        if (order != null) {
            order.setStatus(Order.OrderStatus.DELIVERED);
            orderRepository.save(order);
        }

        parcelRepository.save(parcel);
        cacheParcelStatus(parcel);

        logOtp(parcel.getId(), OtpLog.OtpType.CUSTOMER_DELIVERY, OtpLog.OtpEvent.VERIFIED, null, req.getPerformedBy());
        log.info("Parcel {} DELIVERED — customer OTP verified", parcel.getId());

        // Send delivery confirmation SMS to customer
        String customerPhone = order != null ? resolveCustomerPhone(order) : null;
        if (customerPhone != null) {
            notificationService.sendSms(customerPhone,
                    "[DeliveryCo] Your parcel has been delivered. Thank you for choosing us!");
        }

        return new ApiResponse<>(true, "Parcel delivered successfully", toResponse(parcel), 200);
    }

    // ─────────────────────────────────────────────────────────────────────
    // UPDATE / DELETE
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Update mutable Parcel fields. Status, OTPs, and rider links are *not*
     * editable here — those go through the dedicated transition methods so
     * the state machine + Redis caches stay consistent.
     */
    @Transactional
    public ApiResponse<Object> updateParcel(UUID parcelId, UpdateParcelRequest req) {
        Parcel parcel = parcelRepository.findById(parcelId).orElse(null);
        if (parcel == null) return new ApiResponse<>(false, "Parcel not found", null, 404);

        if (req.getWeightKg() != null && req.getWeightKg() > 0) parcel.setWeightKg(req.getWeightKg());
        if (req.getDimensions() != null) parcel.setDimensions(req.getDimensions());
        if (req.getDescription() != null) parcel.setDescription(req.getDescription());
        if (req.getDestinationWarehouseId() != null) parcel.setDestinationWarehouseId(req.getDestinationWarehouseId());

        parcelRepository.save(parcel);
        return new ApiResponse<>(true, "Parcel updated", toResponse(parcel), 200);
    }

    /**
     * Delete a parcel. Only safe before a rider has been involved — any later
     * state would leave a real package orphaned. CANCEL the linked shipment
     * or transition manually instead.
     */
    @Transactional
    public ApiResponse<Object> deleteParcel(UUID parcelId) {
        Parcel parcel = parcelRepository.findById(parcelId).orElse(null);
        if (parcel == null) return new ApiResponse<>(false, "Parcel not found", null, 404);

        if (parcel.getStatus() != ParcelStatus.CREATED) {
            return new ApiResponse<>(false,
                    "Cannot delete a parcel in " + parcel.getStatus() + " — use cancel/transition instead",
                    null, 409);
        }
        if (parcel.getShipment() != null) {
            return new ApiResponse<>(false, "Parcel is attached to a shipment", null, 409);
        }

        parcelRepository.delete(parcel);
        etaRedisTemplate.delete("parcel:status:" + parcelId);
        log.info("Parcel {} deleted", parcelId);
        return new ApiResponse<>(true, "Parcel deleted", null, 200);
    }

    // ─────────────────────────────────────────────────────────────────────
    // QUERIES
    // ─────────────────────────────────────────────────────────────────────

    public ApiResponse<Object> getParcel(UUID parcelId) {
        return parcelRepository.findById(parcelId)
                .map(p -> new ApiResponse<Object>(true, "OK", toResponse(p), 200))
                .orElse(new ApiResponse<>(false, "Parcel not found", null, 404));
    }

    public ApiResponse<Object> getParcelByOrder(UUID orderId) {
        return parcelRepository.findByOrderId(orderId)
                .map(p -> new ApiResponse<Object>(true, "OK", toResponse(p), 200))
                .orElse(new ApiResponse<>(false, "No parcel for this order", null, 404));
    }

    public List<ParcelResponse> getWarehouseParcels(UUID warehouseId, String status) {
        List<Parcel> parcels;
        if (status != null && !status.isBlank()) {
            parcels = parcelRepository.findByCurrentWarehouseIdAndStatus(
                    warehouseId, ParcelStatus.valueOf(status.toUpperCase()));
        } else {
            parcels = parcelRepository.findByCurrentWarehouseId(warehouseId);
        }
        return parcels.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public List<ParcelResponse> getRiderParcels(UUID riderId, String role) {
        List<Parcel> parcels = "delivery".equalsIgnoreCase(role)
                ? parcelRepository.findByDeliveryRiderIdAndStatus(riderId, ParcelStatus.OUT_FOR_DELIVERY)
                : parcelRepository.findByPickupRiderIdAndStatus(riderId, ParcelStatus.AWAITING_PICKUP);
        return parcels.stream().map(this::toResponse).collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private boolean verifyOtp(UUID parcelId, OtpLog.OtpType type, String submitted, String stored) {
        // Try Redis first (authoritative, includes expiry)
        String redisKey = OTP_KEY_PREFIX + parcelId + ":" + type.name();
        Object cached = etaRedisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            return cached.toString().equals(submitted);
        }
        // Fallback to DB value (Redis may have expired but parcel not yet updated)
        return stored != null && stored.equals(submitted);
    }

    private void storeOtpInRedis(UUID parcelId, OtpLog.OtpType type, String otp) {
        String redisKey = OTP_KEY_PREFIX + parcelId + ":" + type.name();
        etaRedisTemplate.opsForValue().set(redisKey, otp, OTP_TTL_MINUTES, TimeUnit.MINUTES);
    }

    private void cacheParcelStatus(Parcel parcel) {
        String key = "parcel:status:" + parcel.getId();
        etaRedisTemplate.opsForValue().set(key, parcel.getStatus().name());
    }

    private void logOtp(UUID parcelId, OtpLog.OtpType type, OtpLog.OtpEvent event, String sentTo, String by) {
        OtpLog log = new OtpLog();
        log.setParcelId(parcelId);
        log.setOtpType(type);
        log.setEvent(event);
        log.setSentTo(sentTo);
        log.setPerformedBy(by);
        otpLogRepository.save(log);
    }

    private String resolveSellerPhone(Order order) {
        // In production: call SellerService/UserService to get phone.
        // For now we use a placeholder stored in originAddress (or extend Order model).
        return null; // TODO: wire to seller profile service
    }

    private String resolveCustomerPhone(Order order) {
        // TODO: wire to customer profile service
        return null;
    }

    public ParcelResponse toResponse(Parcel p) {
        return ParcelResponse.builder()
                .id(p.getId())
                .orderId(p.getOrderId())
                .weightKg(p.getWeightKg())
                .dimensions(p.getDimensions())
                .description(p.getDescription())
                .originWarehouseId(p.getOriginWarehouseId())
                .destinationWarehouseId(p.getDestinationWarehouseId())
                .currentWarehouseId(p.getCurrentWarehouseId())
                .shipmentId(p.getShipment() != null ? p.getShipment().getId() : null)
                .pickupRiderId(p.getPickupRiderId())
                .deliveryRiderId(p.getDeliveryRiderId())
                .status(p.getStatus())
                .sellerPickupOtpVerified(p.isSellerPickupOtpVerified())
                .warehouseInOtpVerified(p.isWarehouseInOtpVerified())
                .warehouseOutOtpVerified(p.isWarehouseOutOtpVerified())
                .customerDeliveryOtpVerified(p.isCustomerDeliveryOtpVerified())
                .pickedAt(p.getPickedAt())
                .arrivedAtWarehouseAt(p.getArrivedAtWarehouseAt())
                .deliveredAt(p.getDeliveredAt())
                .createdAt(p.getCreatedAt())
                .build();
    }
}