package com.DeliveryInventoryService.DeliveryInventoryService.Service;

import com.DeliveryInventoryService.DeliveryInventoryService.DTO.ApiResponse;
import com.DeliveryInventoryService.DeliveryInventoryService.DTO.WarehouseDTO.*;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Rider;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Shipment;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Shipment.ShipmentStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.ShipmentLeg;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.ShipmentLeg.ShipmentLegStatus;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.ShipmentTransferSession;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.ShipmentTransferSession.SessionType;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.ShipmentTransferSession.Status;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.Vehicle;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.VehicleSchedule;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.RiderRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ShipmentLegRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.ShipmentRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.VehicleRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.VehicleScheduleRepository;
import com.DeliveryInventoryService.DeliveryInventoryService.Repository.WarehouseRepository;
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
 * ShipmentTransferSessionService
 * ───────────────────────────────
 *
 * Manages ephemeral sessions for every physical shipment handoff.
 * Sessions live in Redis for 2 hours — one session per handoff event.
 *
 * Session types (one per leg of the inter-warehouse journey):
 * DISPATCH_OUT Warehouse admin → outgoing rider
 * HAND_TO_VEHICLE Rider → vehicle/transporter at bus stand
 * RECEIVE_FROM_VEHICLE Vehicle/transporter → destination rider
 * RECEIVE_IN Incoming rider → destination warehouse admin
 *
 * API flow (mirrors HandoverSessionService):
 * 1. POST /session/start → OTP sent to receiving party
 * 2. POST /session/{id}/verify-otp → party identity confirmed; session ACTIVE
 * 3. POST /session/{id}/scan → admin scans each shipment number
 * 4. DELETE /session/{id}/scan/{no} → undo a mistaken scan
 * 5. GET /session/{id} → current state + scanned list
 * 6. POST /session/{id}/confirm → advance all scanned shipments' status
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShipmentTransferSessionService {

    private final RedisTemplate<String, Object> etaRedisTemplate;
    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final RiderRepository riderRepository;
    private final ShipmentRepository shipmentRepository;
    private final ShipmentLegRepository shipmentLegRepository;
    private final WarehouseRepository warehouseRepository;
    private final VehicleRepository vehicleRepository;
    private final VehicleScheduleRepository vehicleScheduleRepository;
    private final ShipmentService shipmentService;

    private static final long SESSION_TTL_HOURS = 2;
    private static final String SESSION_KEY = "shipment:transfer:session:";

    // ── 1. Start session ──────────────────────────────────────────────────

    public ApiResponse<Object> startSession(UUID warehouseId, StartShipmentTransferRequest req) {
        if (warehouseRepository.findById(warehouseId).isEmpty()) {
            return new ApiResponse<>(false, "Warehouse not found", null, 404);
        }

        SessionType sessionType;
        try {
            sessionType = SessionType.valueOf(req.getSessionType().toUpperCase());
        } catch (Exception e) {
            return new ApiResponse<>(false,
                    "Invalid sessionType. Use: DISPATCH_OUT | HAND_TO_VEHICLE | RECEIVE_FROM_VEHICLE | RECEIVE_IN",
                    null, 400);
        }

        ShipmentTransferSession session = new ShipmentTransferSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setSessionType(sessionType);
        session.setWarehouseId(warehouseId);
        session.setOtp(notificationService.generateOtp());

        if (sessionType == SessionType.DISPATCH_OUT) {
            // Auto-resolve rider from the shipment's assigned riderId
            if (req.getReferenceShipmentNo() == null || req.getReferenceShipmentNo().isBlank()) {
                return new ApiResponse<>(false,
                        "referenceShipmentNo required for DISPATCH_OUT — provide any shipment number from the batch being dispatched",
                        null, 400);
            }
            Shipment refShipment = shipmentRepository
                    .findByShipmentNo(req.getReferenceShipmentNo().trim().toUpperCase())
                    .orElse(null);
            if (refShipment == null) {
                return new ApiResponse<>(false,
                        "Shipment " + req.getReferenceShipmentNo() + " not found", null, 404);
            }
            if (!warehouseId.equals(refShipment.getOriginWarehouseId())) {
                return new ApiResponse<>(false,
                        "Shipment does not originate from this warehouse", null, 400);
            }
            if (refShipment.getRiderId() == null) {
                return new ApiResponse<>(false,
                        "No rider assigned to shipment " + req.getReferenceShipmentNo()
                                + ". Assign a rider to the shipment first.",
                        null, 400);
            }
            Rider rider = riderRepository.findById(refShipment.getRiderId()).orElse(null);
            if (rider == null) {
                return new ApiResponse<>(false,
                        "Assigned rider not found for shipment " + req.getReferenceShipmentNo(), null, 404);
            }
            session.setRiderId(rider.getId());
            session.setRiderName(rider.getName());
            session.setPartyName(rider.getName());
            session.setPartyPhone(rider.getPhone());

            if (rider.getPhone() != null && !rider.getPhone().isBlank()) {
                notificationService.sendOtpSms(rider.getPhone(), session.getOtp(),
                        "picking up shipments from warehouse. Show this OTP to the admin to verify your identity.");
            } else {
                log.warn("Rider {} has no phone — dispatch OTP not sent", rider.getId());
            }

        } else if (sessionType == SessionType.RECEIVE_IN) {
            // RECEIVE_IN: admin selects the rider who delivered the arriving shipments
            if (req.getRiderId() == null) {
                return new ApiResponse<>(false, "riderId required for RECEIVE_IN", null, 400);
            }
            Rider rider = riderRepository.findById(req.getRiderId()).orElse(null);
            if (rider == null) {
                return new ApiResponse<>(false, "Rider not found", null, 404);
            }
            session.setRiderId(rider.getId());
            session.setRiderName(rider.getName());
            session.setPartyName(rider.getName());
            session.setPartyPhone(rider.getPhone());

            if (rider.getPhone() != null && !rider.getPhone().isBlank()) {
                notificationService.sendOtpSms(rider.getPhone(), session.getOtp(),
                        "delivering shipments to warehouse. Show this OTP to the admin to verify your identity.");
            } else {
                log.warn("Rider {} has no phone — receive OTP not sent", rider.getId());
            }

        } else {
            // Vehicle/transporter sessions: OTP sent to driver's phone supplied in request
            if (req.getPartyPhone() == null || req.getPartyPhone().isBlank()) {
                return new ApiResponse<>(false, "partyPhone required for " + sessionType, null, 400);
            }
            session.setPartyName(req.getPartyName() != null ? req.getPartyName() : "Driver");
            session.setPartyPhone(req.getPartyPhone().trim());

            String context = sessionType == SessionType.HAND_TO_VEHICLE
                    ? "receiving shipments from rider at transport hub"
                    : "handing over shipments to rider at destination";
            notificationService.sendOtpSms(session.getPartyPhone(), session.getOtp(),
                    context + ". Share this OTP to confirm the shipment handover.");
        }

        saveSession(session);
        log.info("ShipmentTransfer session {} started — type={} warehouse={} party={}",
                session.getSessionId(), sessionType, warehouseId, session.getPartyName());

        return new ApiResponse<>(true, "OTP sent to " + session.getPartyName(),
                StartShipmentTransferResponse.builder()
                        .sessionId(session.getSessionId())
                        .sessionType(sessionType.name())
                        .partyName(session.getPartyName())
                        .partyPhoneMasked(maskPhone(session.getPartyPhone()))
                        .message("OTP sent. Ask " + session.getPartyName()
                                + " to read their OTP so you can verify their identity.")
                        .build(),
                200);
    }

    // ── 2. Verify OTP → session ACTIVE ────────────────────────────────────

    public ApiResponse<Object> verifyOtp(UUID warehouseId, String sessionId, VerifyHandoverOtpRequest req) {
        ShipmentTransferSession session = loadSession(sessionId);
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
            return new ApiResponse<>(false, "Identity already verified for this session", null, 409);
        }
        if (!session.getOtp().equals(req.getOtp())) {
            return new ApiResponse<>(false, "Invalid OTP", null, 400);
        }

        session.setStatus(Status.ACTIVE);
        saveSession(session);

        log.info("ShipmentTransfer session {} — {} verified", sessionId, session.getPartyName());

        return new ApiResponse<>(true, session.getPartyName() + " verified — start scanning shipments",
                buildSessionStatus(session),
                200);
    }

    // ── 3. Scan a shipment number ─────────────────────────────────────────

    @Transactional(readOnly = true)
    public ApiResponse<Object> scanShipment(UUID warehouseId, String sessionId, ScanShipmentRequest req) {
        ShipmentTransferSession session = loadSession(sessionId);
        if (session == null) {
            return new ApiResponse<>(false, "Session not found or expired", null, 404);
        }
        if (!session.getWarehouseId().equals(warehouseId)) {
            return new ApiResponse<>(false, "Session does not belong to this warehouse", null, 403);
        }
        if (session.getStatus() != Status.ACTIVE) {
            return new ApiResponse<>(false,
                    "Session not active — verify OTP first (status: " + session.getStatus() + ")",
                    null, 409);
        }

        String shipmentNo = req.getShipmentNo().trim().toUpperCase();

        if (session.getScannedShipmentNos().contains(shipmentNo)) {
            return buildScanResponse(session, shipmentNo, false, "Already scanned in this session");
        }

        Shipment shipment = shipmentRepository.findByShipmentNo(shipmentNo).orElse(null);
        if (shipment == null) {
            return buildScanResponse(session, shipmentNo, false, "Shipment not found");
        }

        // Validate status + warehouse context for this session type
        String validationError = validateScanForSessionType(session, shipment);
        if (validationError != null) {
            return buildScanResponse(session, shipmentNo, false, validationError);
        }

        session.getScannedShipmentNos().add(shipmentNo);
        saveSession(session);

        log.info("ShipmentTransfer session {} — scanned {} ({} total)",
                sessionId, shipmentNo, session.getScannedShipmentNos().size());

        return buildScanResponse(session, shipmentNo, true, null);
    }

    // ── 4. Remove a mistakenly scanned shipment ───────────────────────────

    public ApiResponse<Object> removeShipment(UUID warehouseId, String sessionId, String shipmentNo) {
        ShipmentTransferSession session = loadSession(sessionId);
        if (session == null) {
            return new ApiResponse<>(false, "Session not found or expired", null, 404);
        }
        if (!session.getWarehouseId().equals(warehouseId)) {
            return new ApiResponse<>(false, "Session does not belong to this warehouse", null, 403);
        }
        if (session.getStatus() != Status.ACTIVE) {
            return new ApiResponse<>(false, "Session not active", null, 409);
        }

        String normalised = shipmentNo.trim().toUpperCase();
        if (!session.getScannedShipmentNos().remove(normalised)) {
            return new ApiResponse<>(false, "Shipment not in scanned list", null, 404);
        }

        saveSession(session);
        log.info("ShipmentTransfer session {} — removed {}", sessionId, normalised);
        return buildScanResponse(session, normalised, true, null);
    }

    // ── 5. Get session state ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ApiResponse<Object> getSession(UUID warehouseId, String sessionId) {
        ShipmentTransferSession session = loadSession(sessionId);
        if (session == null) {
            return new ApiResponse<>(false, "Session not found or expired", null, 404);
        }
        if (!session.getWarehouseId().equals(warehouseId)) {
            return new ApiResponse<>(false, "Session does not belong to this warehouse", null, 403);
        }
        return new ApiResponse<>(true, "OK", buildSessionStatus(session), 200);
    }

    // ── 6. Confirm transfer — commit all scanned shipments at once ─────────

    public ApiResponse<Object> confirmTransfer(UUID warehouseId, String sessionId) {
        ShipmentTransferSession session = loadSession(sessionId);
        if (session == null) {
            return new ApiResponse<>(false, "Session not found or expired", null, 404);
        }
        if (!session.getWarehouseId().equals(warehouseId)) {
            return new ApiResponse<>(false, "Session does not belong to this warehouse", null, 403);
        }
        if (session.getStatus() != Status.ACTIVE) {
            return new ApiResponse<>(false, "Session not active", null, 409);
        }
        if (session.getScannedShipmentNos().isEmpty()) {
            return new ApiResponse<>(false, "No shipments scanned — scan at least one before confirming", null, 400);
        }
        return doConfirm(session, sessionId);
    }

    // ── Rider sessions ────────────────────────────────────────────────────────

    public ApiResponse<Object> startRiderSession(UUID riderId, StartShipmentTransferRequest req) {
        SessionType sessionType;
        try {
            sessionType = SessionType.valueOf(req.getSessionType().toUpperCase());
        } catch (Exception e) {
            return new ApiResponse<>(false,
                    "Invalid sessionType. Use: HAND_TO_VEHICLE | RECEIVE_FROM_VEHICLE", null, 400);
        }
        if (sessionType != SessionType.HAND_TO_VEHICLE && sessionType != SessionType.RECEIVE_FROM_VEHICLE) {
            return new ApiResponse<>(false,
                    "Rider sessions only support HAND_TO_VEHICLE and RECEIVE_FROM_VEHICLE", null, 400);
        }
        if (req.getReferenceShipmentNo() == null || req.getReferenceShipmentNo().isBlank()) {
            return new ApiResponse<>(false,
                    "referenceShipmentNo required to identify shipment context", null, 400);
        }

        Shipment refShipment = shipmentRepository
                .findByShipmentNo(req.getReferenceShipmentNo().trim().toUpperCase())
                .orElse(null);
        if (refShipment == null) {
            
            return new ApiResponse<>(false,
                    "Shipment " + req.getReferenceShipmentNo() + " not found", null, 404);
        }

        // Resolve the relevant warehouse from the shipment so scan validation works
        UUID warehouseId = sessionType == SessionType.HAND_TO_VEHICLE
                ? refShipment.getOriginWarehouseId()
                : refShipment.getDestinationWarehouseId();

        // Resolve party phone — auto-resolve from the assigned vehicle driver for
        // HAND_TO_VEHICLE
        String partyPhone = req.getPartyPhone();
        String partyName = req.getPartyName();

        if (partyPhone == null || partyPhone.isBlank()) {
            Vehicle vehicle = resolveVehicle(refShipment);
            if (vehicle != null && vehicle.getRider() != null) {
                Rider driver = vehicle.getRider();
                partyPhone = driver.getPhone();
                if (partyName == null || partyName.isBlank()) {
                    partyName = driver.getName();
                }
                log.info("Auto-resolved driver {} ({}) from vehicle {} for shipment {}",
                        driver.getName(), maskPhone(partyPhone),
                        vehicle.getVehicleNumber(), refShipment.getShipmentNo());
            }
        }

        if (partyPhone == null || partyPhone.isBlank()) {
            return new ApiResponse<>(false,
                    "No driver phone found. The vehicle assigned to this shipment has no driver — "
                            + "assign a driver to the vehicle, or enter the phone manually.",
                    null, 400);
        }

        ShipmentTransferSession session = new ShipmentTransferSession();
        session.setSessionId(UUID.randomUUID().toString());
        session.setSessionType(sessionType);
        session.setWarehouseId(warehouseId);
        session.setOwnerRiderId(riderId);
        session.setOtp(notificationService.generateOtp());
        session.setPartyName(partyName != null && !partyName.isBlank() ? partyName.trim() : "Driver");
        session.setPartyPhone(partyPhone.trim());

        String context = sessionType == SessionType.HAND_TO_VEHICLE
                ? "receiving shipments from rider at transport hub"
                : "handing over shipments to rider at destination";
        notificationService.sendOtpSms(session.getPartyPhone(), session.getOtp(),
                context + ". Share this OTP to confirm the shipment handover.");

        saveSession(session);
        log.info("Rider ShipmentHandoff session {} started — type={} rider={} party={}",
                session.getSessionId(), sessionType, riderId, session.getPartyName());

        return new ApiResponse<>(true, "OTP sent to " + session.getPartyName(),
                StartShipmentTransferResponse.builder()
                        .sessionId(session.getSessionId())
                        .sessionType(sessionType.name())
                        .partyName(session.getPartyName())
                        .partyPhoneMasked(maskPhone(session.getPartyPhone()))
                        .message("OTP sent. Ask " + session.getPartyName()
                                + " to read their OTP to verify identity.")
                        .build(),
                200);
    }

    public ApiResponse<Object> verifyOtpForRider(UUID riderId, String sessionId, VerifyHandoverOtpRequest req) {
        ShipmentTransferSession session = loadSession(sessionId);
        if (session == null)
            return new ApiResponse<>(false, "Session not found or expired", null, 404);
        String err = checkRiderOwnership(session, riderId);
        if (err != null)
            return new ApiResponse<>(false, err, null, 403);
        if (session.getStatus() == Status.COMPLETED)
            return new ApiResponse<>(false, "Session already completed", null, 409);
        if (session.getStatus() == Status.ACTIVE)
            return new ApiResponse<>(false, "Identity already verified", null, 409);
        if (!session.getOtp().equals(req.getOtp()))
            return new ApiResponse<>(false, "Invalid OTP", null, 400);
        session.setStatus(Status.ACTIVE);
        saveSession(session);
        log.info("Rider session {} — {} verified", sessionId, session.getPartyName());
        return new ApiResponse<>(true, session.getPartyName() + " verified — start scanning shipments",
                buildSessionStatus(session), 200);
    }

    @Transactional(readOnly = true)
    public ApiResponse<Object> scanShipmentForRider(UUID riderId, String sessionId, ScanShipmentRequest req) {
        ShipmentTransferSession session = loadSession(sessionId);
        if (session == null)
            return new ApiResponse<>(false, "Session not found or expired", null, 404);
        String err = checkRiderOwnership(session, riderId);
        if (err != null)
            return new ApiResponse<>(false, err, null, 403);
        if (session.getStatus() != Status.ACTIVE) {
            return new ApiResponse<>(false,
                    "Session not active — verify OTP first (status: " + session.getStatus() + ")", null, 409);
        }
        String shipmentNo = req.getShipmentNo().trim().toUpperCase();
        if (session.getScannedShipmentNos().contains(shipmentNo)) {
            return buildScanResponse(session, shipmentNo, false, "Already scanned in this session");
        }
        Shipment shipment = shipmentRepository.findByShipmentNo(shipmentNo).orElse(null);
        if (shipment == null)
            return buildScanResponse(session, shipmentNo, false, "Shipment not found");
        String validationError = validateScanForSessionType(session, shipment);
        if (validationError != null)
            return buildScanResponse(session, shipmentNo, false, validationError);
        session.getScannedShipmentNos().add(shipmentNo);
        saveSession(session);
        log.info("Rider session {} — scanned {} ({} total)", sessionId, shipmentNo,
                session.getScannedShipmentNos().size());
        return buildScanResponse(session, shipmentNo, true, null);
    }

    public ApiResponse<Object> removeShipmentForRider(UUID riderId, String sessionId, String shipmentNo) {
        ShipmentTransferSession session = loadSession(sessionId);
        if (session == null)
            return new ApiResponse<>(false, "Session not found or expired", null, 404);
        String err = checkRiderOwnership(session, riderId);
        if (err != null)
            return new ApiResponse<>(false, err, null, 403);
        if (session.getStatus() != Status.ACTIVE)
            return new ApiResponse<>(false, "Session not active", null, 409);
        String normalised = shipmentNo.trim().toUpperCase();
        if (!session.getScannedShipmentNos().remove(normalised)) {
            return new ApiResponse<>(false, "Shipment not in scanned list", null, 404);
        }
        saveSession(session);
        return buildScanResponse(session, normalised, true, null);
    }

    @Transactional(readOnly = true)
    public ApiResponse<Object> getSessionForRider(UUID riderId, String sessionId) {
        ShipmentTransferSession session = loadSession(sessionId);
        if (session == null)
            return new ApiResponse<>(false, "Session not found or expired", null, 404);
        String err = checkRiderOwnership(session, riderId);
        if (err != null)
            return new ApiResponse<>(false, err, null, 403);
        return new ApiResponse<>(true, "OK", buildSessionStatus(session), 200);
    }

    public ApiResponse<Object> confirmTransferForRider(UUID riderId, String sessionId) {
        ShipmentTransferSession session = loadSession(sessionId);
        if (session == null)
            return new ApiResponse<>(false, "Session not found or expired", null, 404);
        String err = checkRiderOwnership(session, riderId);
        if (err != null)
            return new ApiResponse<>(false, err, null, 403);
        if (session.getStatus() != Status.ACTIVE)
            return new ApiResponse<>(false, "Session not active", null, 409);
        if (session.getScannedShipmentNos().isEmpty()) {
            return new ApiResponse<>(false, "No shipments scanned — scan at least one before confirming", null, 400);
        }
        return doConfirm(session, sessionId);
    }

    // ── Shared confirm logic ──────────────────────────────────────────────────

    private ApiResponse<Object> doConfirm(ShipmentTransferSession session, String sessionId) {
        String targetStatus = targetStatusFor(session.getSessionType());

        List<String> processed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (String shipmentNo : session.getScannedShipmentNos()) {
            try {
                Shipment shipment = shipmentRepository.findByShipmentNo(shipmentNo).orElse(null);
                if (shipment == null) {
                    skipped.add(shipmentNo);
                    errors.add(shipmentNo + ": not found");
                    continue;
                }

                // For DISPATCH_OUT: allow CREATED → DISPATCHED by first moving to ASSIGNED if
                // needed
                if (session.getSessionType() == SessionType.DISPATCH_OUT
                        && shipment.getStatus() == ShipmentStatus.CREATED) {
                    ApiResponse<Object> assignResp = shipmentService.updateShipmentStatus(shipment.getId(), "ASSIGNED");
                    if (!assignResp.success()) {
                        skipped.add(shipmentNo);
                        errors.add(shipmentNo + ": could not auto-assign — " + assignResp.message());
                        continue;
                    }
                }

                ApiResponse<Object> resp = shipmentService.updateShipmentStatus(shipment.getId(), targetStatus);
                if (resp.success()) {
                    processed.add(shipmentNo);
                    log.info("ShipmentTransfer session {} — {} → {}", sessionId, shipmentNo, targetStatus);
                    updateShipmentRider(session, shipment);
                    advanceLeg(session, shipment);
                } else {
                    skipped.add(shipmentNo);
                    errors.add(shipmentNo + ": " + resp.message());
                }

            } catch (Exception ex) {
                skipped.add(shipmentNo);
                errors.add(shipmentNo + ": " + ex.getMessage());
                log.warn("ShipmentTransfer confirm: failed for {} — {}", shipmentNo, ex.getMessage());
            }
        }

        session.setStatus(Status.COMPLETED);
        saveSession(session);
        log.info("ShipmentTransfer session {} confirmed: {} processed, {} skipped",
                sessionId, processed.size(), skipped.size());

        return new ApiResponse<>(true,
                processed.size() + " shipment(s) advanced to " + targetStatus,
                ConfirmTransferResponse.builder()
                        .processed(processed.size())
                        .skipped(skipped.size())
                        .processedShipmentNos(processed)
                        .skippedShipmentNos(skipped)
                        .errors(errors)
                        .build(),
                200);
    }

    /**
     * Updates shipment.riderId to reflect who is physically holding the shipment
     * after this handoff.
     *
     * DISPATCH_OUT → rider picks up from warehouse → riderId = session.riderId
     * HAND_TO_VEHICLE → rider hands to vehicle/transporter → riderId = null
     * (vehicle driver holds it)
     * RECEIVE_FROM_VEHICLE → destination rider takes from vehicle → riderId =
     * session.ownerRiderId
     * RECEIVE_IN → warehouse accepts from rider → riderId = null
     */
    private void updateShipmentRider(ShipmentTransferSession session, Shipment shipment) {
        try {
            UUID newRiderId = switch (session.getSessionType()) {
                case DISPATCH_OUT -> session.getRiderId();
                case RECEIVE_FROM_VEHICLE -> session.getOwnerRiderId();
                case HAND_TO_VEHICLE, RECEIVE_IN -> null;
            };
            shipment.setRiderId(newRiderId);
            shipmentRepository.save(shipment);
            log.info("ShipmentTransfer — shipment {} riderId updated to {}", shipment.getShipmentNo(), newRiderId);
        } catch (Exception ex) {
            log.warn("updateShipmentRider: could not update riderId for shipment {} — {}",
                    shipment.getShipmentNo(), ex.getMessage());
        }
    }

    /**
     * Advances the ShipmentLeg record to reflect the physical state after a
     * transfer session confirms.
     *
     * DISPATCH_OUT → PENDING leg becomes DISPATCHED; dispatchingRider recorded from
     * session.
     * HAND_TO_VEHICLE → DISPATCHED leg becomes IN_TRANSIT.
     * RECEIVE_FROM_VEHICLE → IN_TRANSIT leg becomes COMPLETED; receivingRider
     * recorded from session owner.
     * RECEIVE_IN → internal warehouse op; no leg change needed.
     */
    private void advanceLeg(ShipmentTransferSession session, Shipment shipment) {
        try {
            switch (session.getSessionType()) {
                case DISPATCH_OUT -> shipmentLegRepository
                        .findFirstByShipmentIdAndStatus(shipment.getId(), ShipmentLegStatus.PENDING)
                        .ifPresent(leg -> {
                            leg.setStatus(ShipmentLegStatus.DISPATCHED);
                            if (session.getRiderId() != null) {
                                riderRepository.findById(session.getRiderId()).ifPresent(r -> {
                                    leg.setDispatchingRiderId(r.getId());
                                    leg.setDispatchingRiderName(r.getName());
                                });
                            }
                            shipmentLegRepository.save(leg);
                        });

                case HAND_TO_VEHICLE -> shipmentLegRepository
                        .findFirstByShipmentIdAndStatus(shipment.getId(), ShipmentLegStatus.DISPATCHED)
                        .ifPresent(leg -> {
                            leg.setStatus(ShipmentLegStatus.IN_TRANSIT);
                            shipmentLegRepository.save(leg);
                        });

                case RECEIVE_FROM_VEHICLE -> shipmentLegRepository
                        .findFirstByShipmentIdAndStatus(shipment.getId(), ShipmentLegStatus.IN_TRANSIT)
                        .ifPresent(leg -> {
                            leg.setStatus(ShipmentLegStatus.COMPLETED);
                            leg.setActualArrival(java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata")));
                            UUID receivingId = session.getOwnerRiderId() != null
                                    ? session.getOwnerRiderId()
                                    : session.getRiderId();
                            if (receivingId != null) {
                                riderRepository.findById(receivingId).ifPresent(r -> {
                                    leg.setReceivingRiderId(r.getId());
                                    leg.setReceivingRiderName(r.getName());
                                });
                            }
                            shipmentLegRepository.save(leg);
                        });

                case RECEIVE_IN -> {
                    // No leg update needed — RECEIVE_IN is a within-warehouse handover.
                }
            }
        } catch (Exception ex) {
            log.warn("advanceLeg: could not update leg for shipment {} — {}", shipment.getShipmentNo(),
                    ex.getMessage());
        }
    }

    private String checkRiderOwnership(ShipmentTransferSession session, UUID riderId) {
        if (session.getOwnerRiderId() == null || !session.getOwnerRiderId().equals(riderId)) {
            return "Session does not belong to this rider";
        }
        return null;
    }

    // ── Validation ────────────────────────────────────────────────────────

    private String validateScanForSessionType(ShipmentTransferSession session, Shipment shipment) {
        UUID warehouseId = session.getWarehouseId();
        return switch (session.getSessionType()) {
            case DISPATCH_OUT -> {
                if (shipment.getStatus() != ShipmentStatus.CREATED
                        && shipment.getStatus() != ShipmentStatus.ASSIGNED) {
                    yield "Shipment status is " + shipment.getStatus()
                            + " — only CREATED or ASSIGNED shipments can be dispatched";
                }
                if (!warehouseId.equals(shipment.getOriginWarehouseId())) {
                    yield "Shipment does not originate from this warehouse";
                }
                yield null;
            }
            case HAND_TO_VEHICLE -> {
                if (shipment.getStatus() != ShipmentStatus.DISPATCHED) {
                    yield "Shipment status is " + shipment.getStatus()
                            + " — only DISPATCHED shipments can be handed to a vehicle";
                }
                yield null;
            }
            case RECEIVE_FROM_VEHICLE -> {
                if (shipment.getStatus() != ShipmentStatus.IN_TRANSIT) {
                    yield "Shipment status is " + shipment.getStatus()
                            + " — only IN_TRANSIT shipments can be received from a vehicle";
                }
                yield null;
            }
            case RECEIVE_IN -> {
                if (shipment.getStatus() != ShipmentStatus.AT_DESTINATION) {
                    yield "Shipment status is " + shipment.getStatus()
                            + " — only AT_DESTINATION shipments can be received at warehouse";
                }
                if (!warehouseId.equals(shipment.getDestinationWarehouseId())) {
                    yield "Shipment destination is not this warehouse";
                }
                yield null;
            }
        };
    }

    private String targetStatusFor(SessionType type) {
        return switch (type) {
            case DISPATCH_OUT -> "DISPATCHED";
            case HAND_TO_VEHICLE -> "IN_TRANSIT";
            case RECEIVE_FROM_VEHICLE -> "AT_DESTINATION";
            case RECEIVE_IN -> "DELIVERED";
        };
    }

    // ── Response builders ─────────────────────────────────────────────────

    private ApiResponse<Object> buildScanResponse(ShipmentTransferSession session,
            String shipmentNo,
            boolean accepted,
            String reason) {
        return new ApiResponse<>(true, accepted ? "Shipment scanned" : reason,
                ScanShipmentResponse.builder()
                        .shipmentNo(shipmentNo)
                        .accepted(accepted)
                        .reason(reason)
                        .totalScanned(session.getScannedShipmentNos().size())
                        .scannedShipments(resolveScannedItems(session.getScannedShipmentNos()))
                        .build(),
                200);
    }

    private ShipmentTransferSessionStatus buildSessionStatus(ShipmentTransferSession session) {
        return ShipmentTransferSessionStatus.builder()
                .sessionId(session.getSessionId())
                .sessionType(session.getSessionType().name())
                .riderId(session.getRiderId())
                .partyName(session.getPartyName())
                .partyPhoneMasked(maskPhone(session.getPartyPhone()))
                .status(session.getStatus().name())
                .totalScanned(session.getScannedShipmentNos().size())
                .scannedShipments(resolveScannedItems(session.getScannedShipmentNos()))
                .build();
    }

    private List<ScannedShipmentItem> resolveScannedItems(List<String> shipmentNos) {
        if (shipmentNos.isEmpty())
            return List.of();
        return shipmentNos.stream()
                .map(no -> shipmentRepository.findByShipmentNo(no).orElse(null))
                .filter(Objects::nonNull)
                .map(s -> ScannedShipmentItem.builder()
                        .shipmentNo(s.getShipmentNo())
                        .shipmentId(s.getId())
                        .originCity(s.getOriginCity())
                        .destinationCity(s.getDestinationCity())
                        .parcelCount(s.getParcels().size())
                        .currentStatus(s.getStatus())
                        .build())
                .collect(Collectors.toList());
    }

    // ── Redis helpers ─────────────────────────────────────────────────────

    private void saveSession(ShipmentTransferSession session) {
        try {
            String key = SESSION_KEY + session.getSessionId();
            String json = objectMapper.writeValueAsString(session);
            etaRedisTemplate.opsForValue().set(key, json, SESSION_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save shipment transfer session to Redis", e);
        }
    }

    private ShipmentTransferSession loadSession(String sessionId) {
        try {
            Object raw = etaRedisTemplate.opsForValue().get(SESSION_KEY + sessionId);
            if (raw == null)
                return null;
            return objectMapper.readValue(raw.toString(), ShipmentTransferSession.class);
        } catch (Exception e) {
            log.error("Failed to deserialise shipment transfer session {}", sessionId, e);
            return null;
        }
    }

    /**
     * Resolves the long-haul vehicle for a shipment.
     * Tries shipment.vehicleId first, then falls back to
     * shipment.vehicleScheduleId → VehicleSchedule.vehicleId.
     */
    private Vehicle resolveVehicle(Shipment shipment) {
        if (shipment.getVehicleId() != null) {
            Vehicle v = vehicleRepository.findById(shipment.getVehicleId()).orElse(null);
            if (v != null)
                return v;
        }
        if (shipment.getVehicleScheduleId() != null) {
            VehicleSchedule schedule = vehicleScheduleRepository
                    .findById(shipment.getVehicleScheduleId()).orElse(null);
            if (schedule != null && schedule.getVehicleId() != null) {
                return vehicleRepository.findById(schedule.getVehicleId()).orElse(null);
            }
        }
        return null;
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4)
            return phone;
        int n = phone.length();
        return phone.substring(0, Math.min(3, n - 4))
                + "*".repeat(Math.max(0, n - 7))
                + phone.substring(n - 4);
    }
}
