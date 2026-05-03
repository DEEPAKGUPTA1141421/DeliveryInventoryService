package com.DeliveryInventoryService.DeliveryInventoryService.kafka;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Parcel;
import com.DeliveryInventoryService.DeliveryInventoryService.Model.OtpLog;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * ParcelLifecycleEvent — published to Kafka whenever a parcel
 * changes status or an OTP event occurs.
 *
 * Topic: parcel.lifecycle.events
 * Key: parcelId (ensures ordered processing per parcel)
 *
 * Consumers:
 * - NotificationService (SMS/call triggers)
 * - OrderService (update Order.status)
 * - Analytics/audit service
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParcelLifecycleEvent {

    public enum EventType {
        PARCEL_CREATED,
        PICKUP_RIDER_ASSIGNED,
        SELLER_OTP_VERIFIED,
        WAREHOUSE_IN_OTP_SENT,
        WAREHOUSE_IN_VERIFIED,
        ASSIGNED_TO_SHIPMENT,
        SHIPMENT_DEPARTED,
        SHIPMENT_ARRIVED,
        DELIVERY_RIDER_ASSIGNED,
        WAREHOUSE_OUT_VERIFIED,
        CUSTOMER_OTP_VERIFIED,
        DELIVERED,
        FAILED
    }

    private UUID parcelId;
    private UUID orderId;
    private EventType eventType;
    private Parcel.ParcelStatus newStatus;

    // Context
    private UUID warehouseId;
    private UUID riderId;
    private UUID shipmentId;
    private String shipmentNo;

    // OTP context (set when eventType is OTP-related)
    private OtpLog.OtpType otpType;
    private String recipientPhone;

    private ZonedDateTime occurredAt;
    private String notes;
}