package com.DeliveryInventoryService.DeliveryInventoryService.Model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Audit log for every OTP event (sent / verified / failed).
 */
@Entity
@Table(name = "otp_logs")
@Data
public class OtpLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID parcelId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OtpType otpType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OtpEvent event;

    /** Phone number the OTP was sent to */
    private String sentTo;

    /** Who performed the verification (riderId / adminId / customerId) */
    private String performedBy;

    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private ZonedDateTime createdAt = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));

    public enum OtpType {
        SELLER_PICKUP,
        WAREHOUSE_IN,
        WAREHOUSE_OUT,
        CUSTOMER_DELIVERY
    }

    public enum OtpEvent {
        SENT, VERIFIED, FAILED
    }
}