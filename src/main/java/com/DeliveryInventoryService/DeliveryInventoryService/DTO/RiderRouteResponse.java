package com.DeliveryInventoryService.DeliveryInventoryService.DTO;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

public record RiderRouteResponse(
        UUID batchRunId,
        int totalStops,
        int completedStops,
        ZonedDateTime estimatedCompletionAt,
        List<StopDetail> assignments
) {
    public record StopDetail(
            UUID assignmentId,
            int sequenceNumber,
            String status,
            OrderSummary order,
            ZonedDateTime estimatedArrivalAt
    ) {}

    public record OrderSummary(
            UUID orderId,
            String destAddress,
            double destLat,
            double destLng,
            double weightKg,
            String orderNo
    ) {}
}
