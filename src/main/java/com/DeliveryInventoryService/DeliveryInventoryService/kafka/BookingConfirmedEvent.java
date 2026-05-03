package com.DeliveryInventoryService.DeliveryInventoryService.kafka;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BookingConfirmedEvent {
    private UUID bookingId;
    private UUID customerId;
    private UUID shopId;
    private UUID deliveryAddressId;
    private String totalAmountPaise;
    private Instant confirmedAt;
}
